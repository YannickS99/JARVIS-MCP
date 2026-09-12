package io.github.yannicks99.jarvis_mcp.common;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Haelt einen teuer zu beschaffenden Wert vor und erneuert ihn im Hintergrund.
 *
 * <p>Beide Dinge, die JARVIS-MCP von Home Assistant braucht - die Entitaeten und die Bereiche -
 * haben dieselbe Eigenart: Sie aendern sich selten, kosten aber einen vollstaendigen HTTP-Aufruf.
 * Diesen Aufruf in einen Werkzeugaufruf zu legen, wuerde jede Lichtschaltung um die Antwortzeit
 * von Home Assistant verlaengern. Also liegt er hier.
 *
 * <p>Der Wert selbst ist unveraenderlich und steht hinter einer einzelnen Referenz: Leser brauchen
 * keine Sperre, ein Austausch ist atomar. Gesperrt wird nur das Neuladen, und zwar so, dass bei
 * gleichzeitigen Anfragen genau ein Abruf laeuft statt einer pro Aufrufer.
 *
 * @param <T> der gehaltene Wert
 */
public class RefreshingCache<T> {

    private static final Logger log = LoggerFactory.getLogger(RefreshingCache.class);

    private final String name;
    private final Supplier<T> loader;
    private final long ttlNanos;
    private final long minRefreshIntervalNanos;

    private final AtomicReference<Entry<T>> current = new AtomicReference<>();
    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile long lastAttemptNanos;

    /**
     * @param name                fuer Protokollmeldungen
     * @param ttl                 wie lange der Wert als frisch gilt
     * @param minRefreshInterval  Untergrenze zwischen zwei erzwungenen Neuladungen
     */
    public RefreshingCache(String name, Supplier<T> loader, Duration ttl, Duration minRefreshInterval) {
        this.name = name;
        this.loader = loader;
        this.ttlNanos = ttl.toNanos();
        this.minRefreshIntervalNanos = minRefreshInterval.toNanos();
        // So gesetzt, dass das erste erzwungene Neuladen sofort erlaubt ist - und zwar ohne mit
        // Long.MIN_VALUE zu hantieren, denn Differenzen von System.nanoTime() laufen dabei ueber.
        this.lastAttemptNanos = System.nanoTime() - this.minRefreshIntervalNanos - 1;
    }

    /** Der aktuelle Wert; laedt neu, falls er nicht mehr gueltig ist. */
    public T get() {
        Entry<T> entry = current.get();
        if (entry != null && System.nanoTime() - entry.loadedAtNanos() < ttlNanos) {
            return entry.value();
        }
        return load(entry).value();
    }

    /**
     * Erzwingt ein Neuladen, sofern das letzte lange genug her ist.
     *
     * <p>Gedacht fuer den Fall, dass eine Suche ins Leere lief: Der gesuchte Name koennte neu sein.
     * Die Sperrfrist verhindert, dass eine Folge von Fehlgriffen - ein Name, den es schlicht nicht
     * gibt - Home Assistant mit Abrufen ueberzieht.
     *
     * @return der neue Wert, oder der bisherige, falls die Sperrfrist noch laeuft
     */
    public T refreshIfAllowed() {
        Entry<T> entry = current.get();
        if (entry == null) {
            return get();
        }
        long now = System.nanoTime();
        if (now - lastAttemptNanos < minRefreshIntervalNanos
                || now - entry.loadedAtNanos() < minRefreshIntervalNanos) {
            return entry.value();
        }
        return load(entry).value();
    }

    /**
     * Faellt planmaessig im Hintergrund an. Scheitert der Abruf, bleibt der alte Wert stehen - ein
     * kurzzeitig nicht erreichbares Home Assistant soll die Namensaufloesung nicht mitreissen.
     */
    public void refreshQuietly() {
        try {
            load(current.get());
        } catch (RuntimeException ex) {
            log.debug("Geplante Aktualisierung von {} fehlgeschlagen: {}", name, ex.getMessage());
        }
    }

    /** Ob ueberhaupt schon einmal etwas geladen werden konnte. */
    public boolean isLoaded() {
        return current.get() != null;
    }

    private Entry<T> load(Entry<T> stale) {
        // Wer nicht sofort an die Sperre kommt, wartet nicht: Ein anderer Aufrufer laedt gerade,
        // und ein leicht veralteter Wert ist besser als eine blockierte Werkzeugausfuehrung.
        if (!refreshLock.tryLock()) {
            if (stale != null) {
                return stale;
            }
            refreshLock.lock();
        }
        try {
            Entry<T> latest = current.get();
            if (latest != null && latest != stale) {
                // Ein anderer Aufrufer war schneller.
                return latest;
            }
            lastAttemptNanos = System.nanoTime();
            Entry<T> reloaded = new Entry<>(loader.get(), System.nanoTime());
            current.set(reloaded);
            log.debug("{} neu geladen", name);
            return reloaded;
        } finally {
            refreshLock.unlock();
        }
    }

    private record Entry<T>(T value, long loadedAtNanos) {
    }
}
