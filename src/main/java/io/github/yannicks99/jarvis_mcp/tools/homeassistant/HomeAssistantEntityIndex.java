package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

import io.github.yannicks99.jarvis_mcp.common.NameNormalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Haelt die Zuordnung "Anzeigename aus Home Assistant" → {@code entity_id} vor.
 *
 * <p>Der Grund fuer diese Klasse ist Geschwindigkeit: Sowohl {@code set_light_power} als auch
 * {@code run_ha_routine} muessen einen gesprochenen Namen aufloesen, und die einzige Quelle dafuer
 * ist {@code GET /api/states} - ein Aufruf, der saemtliche Entitaeten samt Attributen liefert. Ihn
 * bei jedem Werkzeugaufruf zu machen, wuerde jede Lichtschaltung um eine vollstaendige
 * Home-Assistant-Abfrage verlaengern, die im Bereich mehrerer hundert Millisekunden liegt.
 *
 * <p>Stattdessen wird der Index im Hintergrund im Takt der Gueltigkeitsdauer aktualisiert, sodass
 * ein Werkzeugaufruf im Normalfall nur einen Hash-Zugriff kostet. Der Index selbst ist ein
 * unveraenderliches Abbild hinter einer einzelnen Referenz: Leser brauchen keine Sperre, und ein
 * Austausch ist atomar. Nur das Neuaufbauen ist gesperrt, und zwar so, dass bei gleichzeitigen
 * Anfragen genau ein Abruf laeuft statt einer pro Aufrufer.
 */
public class HomeAssistantEntityIndex {

    private static final Logger log = LoggerFactory.getLogger(HomeAssistantEntityIndex.class);

    /** Wie viele Namen einer Fehlermeldung beigelegt werden, damit die Antwort lesbar bleibt. */
    private static final int MAX_SUGGESTIONS = 25;

    private final HomeAssistantClient client;
    private final List<String> domains;
    private final long ttlNanos;
    private final long minRefreshIntervalNanos;

    private final AtomicReference<Snapshot> current = new AtomicReference<>();
    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile long lastAttemptNanos;

    public HomeAssistantEntityIndex(HomeAssistantClient client, List<String> domains,
            Duration cacheTtl, Duration minRefreshInterval) {
        this.client = client;
        this.domains = List.copyOf(domains);
        this.ttlNanos = cacheTtl.toNanos();
        this.minRefreshIntervalNanos = minRefreshInterval.toNanos();
        // So gesetzt, dass der erste erzwungene Abruf sofort erlaubt ist - und zwar ohne mit
        // Long.MIN_VALUE zu hantieren, denn Differenzen von System.nanoTime() laufen dabei ueber.
        this.lastAttemptNanos = System.nanoTime() - this.minRefreshIntervalNanos - 1;
    }

    /**
     * Sucht eine Entitaet der angegebenen Domains anhand ihres Anzeigenamens.
     *
     * @param domainPrefixes Praefixe inklusive Punkt; die Reihenfolge entscheidet bei Gleichstand
     *                       (fuer Routinen wird {@code script.} vor {@code scene.} bevorzugt)
     */
    public EntityLookup find(String name, List<String> domainPrefixes) {
        Snapshot snapshot = fresh();
        EntityLookup result = snapshot.lookup(name, domainPrefixes);

        // Ein Fehlgriff kann schlicht heissen, dass die Entitaet neu ist. Einmal nachladen und
        // erneut suchen - aber nur, wenn der letzte Versuch lange genug her ist, damit eine Folge
        // von Fehlgriffen Home Assistant nicht mit Abrufen ueberzieht.
        if (result instanceof EntityLookup.NotFound && refreshAllowed(snapshot)) {
            Snapshot reloaded = refresh(snapshot);
            if (reloaded != snapshot) {
                return reloaded.lookup(name, domainPrefixes);
            }
        }
        return result;
    }

    /** Laedt den Index neu, falls er nicht mehr gueltig ist. */
    public Snapshot fresh() {
        Snapshot snapshot = current.get();
        if (snapshot != null && System.nanoTime() - snapshot.builtAtNanos() < ttlNanos) {
            return snapshot;
        }
        return refresh(snapshot);
    }

    /**
     * Faellt planmaessig im Hintergrund an. Scheitert der Abruf, bleibt das alte Abbild stehen -
     * ein kurzzeitig nicht erreichbares Home Assistant soll die Namensaufloesung nicht sofort
     * mitreissen.
     */
    public void refreshQuietly() {
        try {
            refresh(current.get());
        } catch (RuntimeException ex) {
            log.debug("Geplante Aktualisierung des Entitaeten-Index fehlgeschlagen: {}", ex.getMessage());
        }
    }

    private boolean refreshAllowed(Snapshot used) {
        long sinceAttempt = System.nanoTime() - lastAttemptNanos;
        return sinceAttempt >= minRefreshIntervalNanos
                && (used == null || System.nanoTime() - used.builtAtNanos() >= minRefreshIntervalNanos);
    }

    private Snapshot refresh(Snapshot stale) {
        // Wer nicht sofort an die Sperre kommt, wartet nicht: Ein anderer Aufrufer laedt gerade,
        // und ein leicht veraltetes Abbild ist besser als eine blockierte Werkzeugausfuehrung.
        if (!refreshLock.tryLock()) {
            if (stale != null) {
                return stale;
            }
            refreshLock.lock();
        }
        try {
            Snapshot latest = current.get();
            if (latest != null && latest != stale) {
                // Ein anderer Aufrufer war schneller.
                return latest;
            }
            lastAttemptNanos = System.nanoTime();
            Snapshot rebuilt = Snapshot.of(client.states(domains));
            current.set(rebuilt);
            log.debug("Entitaeten-Index aufgebaut: {} Entitaeten", rebuilt.all().size());
            return rebuilt;
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * Unveraenderliches Abbild des Index.
     *
     * @param byKey alle Schreibvarianten der Anzeigenamen auf die jeweilige Entitaet - der
     *              Normalfall ist damit ein einzelner Hash-Zugriff
     * @param all   dieselben Entitaeten in Reihenfolge, fuer die Teilstring-Suche und die
     *              Kandidatenliste in Fehlermeldungen
     */
    public record Snapshot(Map<String, HomeAssistantEntity> byKey, List<HomeAssistantEntity> all,
            long builtAtNanos) {

        static Snapshot of(List<HomeAssistantEntity> entities) {
            // Reihenfolge festzurren, damit bei zwei gleich benannten Entitaeten immer dieselbe
            // gewinnt - sonst haengt das Verhalten davon ab, wie Home Assistant gerade sortiert.
            List<HomeAssistantEntity> sorted = new ArrayList<>(entities);
            sorted.sort((left, right) -> left.entityId().compareTo(right.entityId()));

            Map<String, HomeAssistantEntity> byKey = HashMap.newHashMap(sorted.size() * 2);
            for (HomeAssistantEntity entity : sorted) {
                for (String variant : NameNormalizer.variants(entity.name())) {
                    HomeAssistantEntity previous = byKey.putIfAbsent(variant, entity);
                    if (previous != null && !previous.entityId().equals(entity.entityId())) {
                        log.warn("Zwei Entitaeten heissen gleich ('{}'): {} gewinnt, {} ist per Name "
                                + "nicht erreichbar.", entity.name(), previous.entityId(), entity.entityId());
                    }
                }
            }
            return new Snapshot(Map.copyOf(byKey), List.copyOf(sorted), System.nanoTime());
        }

        EntityLookup lookup(String name, List<String> domainPrefixes) {
            for (String variant : NameNormalizer.variants(name)) {
                HomeAssistantEntity hit = byKey.get(variant);
                if (hit != null && inDomains(hit, domainPrefixes)) {
                    return new EntityLookup.Found(hit);
                }
            }

            // Zweiter Versuch je Domain, in der uebergebenen Reihenfolge: Bei Routinen soll ein
            // Skript ein gleich benanntes Szenario schlagen, nicht die Sortierung entscheiden.
            String needle = NameNormalizer.canonical(name);
            EntityLookup ambiguous = null;
            for (String prefix : domainPrefixes) {
                EntityLookup result = partialMatch(needle, prefix);
                if (result instanceof EntityLookup.Found) {
                    return result;
                }
                if (ambiguous == null && result instanceof EntityLookup.Ambiguous) {
                    ambiguous = result;
                }
            }
            return ambiguous != null ? ambiguous : new EntityLookup.NotFound(names(domainPrefixes));
        }

        /**
         * Zweiter Versuch ueber Teilstrings: deckt ab, dass gesprochene Namen kuerzer ausfallen als
         * der in Home Assistant hinterlegte ("Stehlampe" statt "Stehlampe Wohnzimmer links").
         * Bleibt nur ein Treffer uebrig, ist die Sache eindeutig; bei mehreren wird bewusst nicht
         * geraten.
         */
        private EntityLookup partialMatch(String needle, String domainPrefix) {
            if (needle.isEmpty()) {
                return new EntityLookup.NotFound(List.of());
            }
            List<HomeAssistantEntity> matches = new ArrayList<>(2);
            for (HomeAssistantEntity entity : all) {
                if (entity.entityId().startsWith(domainPrefix) && entity.normalized().contains(needle)) {
                    matches.add(entity);
                }
            }
            return switch (matches.size()) {
                case 0 -> new EntityLookup.NotFound(List.of());
                case 1 -> new EntityLookup.Found(matches.getFirst());
                default -> new EntityLookup.Ambiguous(matches.stream().map(HomeAssistantEntity::name).toList());
            };
        }

        /** Anzeigenamen der Entitaeten in den gefragten Domains, gedeckelt fuer Fehlermeldungen. */
        List<String> names(List<String> domainPrefixes) {
            return all.stream()
                    .filter(entity -> inDomains(entity, domainPrefixes))
                    .map(HomeAssistantEntity::name)
                    .limit(MAX_SUGGESTIONS)
                    .toList();
        }

        private static boolean inDomains(HomeAssistantEntity entity, List<String> domainPrefixes) {
            for (int i = 0; i < domainPrefixes.size(); i++) {
                if (entity.entityId().startsWith(domainPrefixes.get(i))) {
                    return true;
                }
            }
            return false;
        }
    }
}
