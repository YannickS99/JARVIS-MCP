package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

import io.github.yannicks99.jarvis_mcp.common.NameNormalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loest Bereichsnamen und deren Aliasse auf Home-Assistant-{@code area_id}s auf.
 *
 * <p>Bewusst eine statische Zuordnung aus der Konfiguration (Anforderungskatalog 4a): Die echten
 * Bereichsaliasse stehen nur in Home Assistants Area Registry, und die gibt weder die Template-
 * Engine noch die REST-API heraus - nur die WebSocket-API. Ein zweiter Client mit eigenem
 * Handshake und eigenem Zwischenspeicher waere fuer eine Handvoll Bereiche, die sich praktisch nie
 * aendern, unverhaeltnismaessig.
 *
 * <p>Die Zuordnung wird einmal beim Start in ihre Suchform gebracht; zur Laufzeit ist jede
 * Aufloesung ein Hash-Zugriff.
 */
public class AreaResolver {

    private final Map<String, String> byName;
    private final List<String> knownNames;

    public AreaResolver(List<AreaMapping> configured) {
        Map<String, String> index = HashMap.newHashMap(configured.size() * 4);
        List<String> names = new ArrayList<>(configured.size());

        for (AreaMapping area : configured) {
            // Die area_id selbst ist immer auch ein gueltiger Name - sonst muesste jeder Bereich,
            // dessen Name ohnehin schon der Kennung entspricht, doppelt eingetragen werden.
            register(index, area.id(), area.id());
            for (String name : area.names()) {
                register(index, name, area.id());
                names.add(name);
            }
            if (area.names().isEmpty()) {
                names.add(area.id());
            }
        }

        this.byName = Map.copyOf(index);
        this.knownNames = List.copyOf(names);
    }

    private static void register(Map<String, String> index, String name, String areaId) {
        for (String variant : NameNormalizer.variants(name)) {
            index.putIfAbsent(variant, areaId);
        }
    }

    /** Leer, wenn der Name in keiner Schreibweise bekannt ist. */
    public Optional<String> resolve(String area) {
        for (String variant : NameNormalizer.variants(area)) {
            String areaId = byName.get(variant);
            if (areaId != null) {
                return Optional.of(areaId);
            }
        }
        return Optional.empty();
    }

    /** Die konfigurierten Bereichsnamen in Eingabereihenfolge - fuer Fehlermeldungen. */
    public List<String> knownNames() {
        return knownNames;
    }
}
