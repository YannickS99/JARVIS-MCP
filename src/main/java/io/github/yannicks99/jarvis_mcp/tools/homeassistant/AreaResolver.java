package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

import io.github.yannicks99.jarvis_mcp.common.NameNormalizer;
import io.github.yannicks99.jarvis_mcp.common.RefreshingCache;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedSet;

/**
 * Loest Bereichsnamen auf Home-Assistant-{@code area_id}s auf.
 *
 * <p>Die Bereiche kommen aus Home Assistant selbst. Sie dort <em>und</em> hier zu pflegen waere
 * doppelte Arbeit fuer dieselbe Information, und die beiden Stellen wuerden unweigerlich
 * auseinanderlaufen. Geholt werden sie ueber die Template-Engine (siehe
 * {@link HomeAssistantClient#areas()}); die urspruenglich dafuer angedachte WebSocket-Anbindung an
 * die Area Registry ist damit unnoetig.
 *
 * <p>Die statische Konfiguration bleibt, aber nur noch als <em>Ergaenzung</em> fuer zusaetzliche
 * Namen: Home Assistant kennt zwar eigene Bereichsaliasse, gibt sie aber ueber keine
 * REST-Schnittstelle heraus. Wer einen Bereich anders ansprechen will, als er in Home Assistant
 * heisst, traegt das hier ein - alle anderen brauchen die Konfiguration gar nicht mehr.
 */
public class AreaResolver {

    private final RefreshingCache<AreaIndex> areas;

    /** Zusaetzliche Namen aus der Konfiguration, einmal beim Start in Suchform gebracht. */
    private final Map<String, String> configuredAliases;
    private final List<String> configuredNames;

    public AreaResolver(RefreshingCache<AreaIndex> areas, List<AreaMapping> configured) {
        this.areas = areas;

        Map<String, String> aliases = HashMap.newHashMap(configured.size() * 4);
        List<String> names = new ArrayList<>(configured.size());
        for (AreaMapping area : configured) {
            register(aliases, area.id(), area.id());
            for (String name : area.names()) {
                register(aliases, name, area.id());
                names.add(name);
            }
        }
        this.configuredAliases = Map.copyOf(aliases);
        this.configuredNames = List.copyOf(names);
    }

    /**
     * Leer, wenn der Name weder in Home Assistant noch in der Konfiguration vorkommt.
     *
     * <p>Zuerst Home Assistant, dann die konfigurierten Aliasse. Bleibt beides ohne Treffer, wird
     * einmal neu geladen - der Bereich koennte gerade erst angelegt worden sein.
     */
    public Optional<String> resolve(String area) {
        AreaIndex index = areas.get();

        Optional<String> hit = lookup(index.byName(), area);
        if (hit.isPresent()) {
            return hit;
        }
        Optional<String> alias = lookup(configuredAliases, area);
        if (alias.isPresent()) {
            return alias;
        }

        AreaIndex reloaded = areas.refreshIfAllowed();
        return reloaded == index ? Optional.empty() : lookup(reloaded.byName(), area);
    }

    /**
     * Die ansprechbaren Bereichsnamen - fuer Fehlermeldungen, damit die KI es mit einem passenden
     * Namen erneut versuchen kann. Ist Home Assistant gerade nicht erreichbar, bleiben die
     * konfigurierten Namen uebrig.
     */
    public List<String> knownNames() {
        SequencedSet<String> names = new LinkedHashSet<>();
        try {
            names.addAll(areas.get().names());
        } catch (RuntimeException ex) {
            // Ohne Home Assistant gibt es nichts zu ergaenzen - der Aufrufer meldet das ohnehin.
        }
        names.addAll(configuredNames);
        return List.copyOf(names);
    }

    /** Ob die Bereiche schon einmal aus Home Assistant geladen werden konnten. */
    public boolean loadedFromHomeAssistant() {
        return areas.isLoaded();
    }

    private static Optional<String> lookup(Map<String, String> index, String area) {
        for (String variant : NameNormalizer.variants(area)) {
            String areaId = index.get(variant);
            if (areaId != null) {
                return Optional.of(areaId);
            }
        }
        return Optional.empty();
    }

    private static void register(Map<String, String> index, String name, String areaId) {
        for (String variant : NameNormalizer.variants(name)) {
            index.putIfAbsent(variant, areaId);
        }
    }

    /**
     * Unveraenderliches Abbild der Bereiche aus Home Assistant: alle Schreibvarianten der Namen
     * auf die jeweilige {@code area_id}, dazu die Anzeigenamen in Reihenfolge.
     */
    public record AreaIndex(Map<String, String> byName, List<String> names) {

        public static AreaIndex of(List<HomeAssistantArea> areas) {
            Map<String, String> byName = HashMap.newHashMap(areas.size() * 4);
            List<String> names = new ArrayList<>(areas.size());
            for (HomeAssistantArea area : areas) {
                // Die area_id ist immer auch ein gueltiger Name - bei den meisten Bereichen ist
                // sie ohnehin nur die kleingeschriebene Fassung des Namens.
                register(byName, area.areaId(), area.areaId());
                register(byName, area.name(), area.areaId());
                names.add(area.name());
            }
            return new AreaIndex(Map.copyOf(byName), List.copyOf(names));
        }
    }
}
