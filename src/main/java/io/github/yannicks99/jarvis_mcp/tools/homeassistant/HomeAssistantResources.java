package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

import io.github.yannicks99.jarvis_mcp.common.EntityCatalog;
import io.github.yannicks99.jarvis_mcp.common.NameNormalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.mcp.annotation.McpResource;
import tools.jackson.databind.ObjectMapper;

/**
 * Die Resources des Home-Assistant-Moduls (Anforderungskatalog JARVIS-SemanticCache, Abschnitt 4).
 *
 * <p>Bewusst Resources und keine Werkzeuge: Hier wird nichts geschaltet, sondern Daten abgerufen -
 * genau die Unterscheidung, die MCP zwischen den beiden macht. Das Sprachmodell bekommt sie deshalb
 * auch nicht zu sehen; gelesen werden sie vom JARVIS-AIService selbst.
 *
 * <p>Kein eigener Zwischenspeicher: Gelesen wird derselbe warmgehaltene Stand, gegen den auch die
 * Werkzeuge ihre Namen aufloesen. Ein Abruf kostet damit keinen Aufruf bei Home Assistant.
 */
public class HomeAssistantResources {

    static final String ENTITIES_URI = "homeassistant://areas-and-entities";

    /** Die Typen heissen wie die Werkzeugparameter, die den jeweiligen Namen entgegennehmen. */
    static final String AREA = "area";
    static final String LIGHT = "light";
    /** {@code run_ha_routine} nimmt den Namen als {@code name} - "routine" sagt mehr darueber aus. */
    static final String ROUTINE = "routine";

    private final HomeAssistantEntityIndex index;
    private final AreaResolver areas;
    private final ObjectMapper jsonMapper;

    public HomeAssistantResources(HomeAssistantEntityIndex index, AreaResolver areas, ObjectMapper jsonMapper) {
        this.index = index;
        this.areas = areas;
        this.jsonMapper = jsonMapper;
    }

    @McpResource(uri = ENTITIES_URI,
            name = "areas-and-entities",
            title = "Bereiche, Lichter und Routinen",
            description = """
                    Alle Bereiche (samt konfigurierter Zusatznamen), Lichter und Routinen, unter \
                    denen die Home-Assistant-Werkzeuge ansprechbar sind - jeweils Anzeigename und \
                    interne Kennung.""",
            mimeType = EntityCatalog.MIME_TYPE)
    public String areasAndEntities() {
        return jsonMapper.writeValueAsString(catalog());
    }

    /**
     * Stellt den Katalog zusammen.
     *
     * <p>Scheitert, solange Home Assistant noch nie erreichbar war - ein leerer Katalog saehe fuer
     * den AIService aus wie ein Haus ohne Bereiche und wuerde seinen letzten guten Stand ersetzen.
     */
    EntityCatalog catalog() {
        List<EntityCatalog.Entry> entries = new ArrayList<>();
        addAreas(entries);
        for (HomeAssistantEntity entity : index.fresh().all()) {
            entries.add(new EntityCatalog.Entry(typeOf(entity), entity.name(), entity.entityId(), List.of()));
        }
        return new EntityCatalog(entries);
    }

    /**
     * Bereiche aus Home Assistant, ergaenzt um die Zusatznamen aus der Konfiguration. Ein nur
     * konfigurierter Bereich wird trotzdem aufgenommen: Die Werkzeuge loesen ihn genauso auf.
     */
    private void addAreas(List<EntityCatalog.Entry> entries) {
        Map<String, List<String>> configuredNames = new LinkedHashMap<>();
        for (AreaMapping mapping : areas.configuredAreas()) {
            configuredNames.computeIfAbsent(mapping.id(), id -> new ArrayList<>()).addAll(mapping.names());
        }

        Set<String> published = new HashSet<>();
        for (HomeAssistantArea area : areas.homeAssistantAreas()) {
            List<String> aliases = configuredNames.getOrDefault(area.areaId(), List.of());
            entries.add(new EntityCatalog.Entry(AREA, area.name(), area.areaId(),
                    aliasesBesides(area.name(), aliases)));
            published.add(area.areaId());
        }

        configuredNames.forEach((id, names) -> {
            if (!published.contains(id)) {
                String name = names.isEmpty() ? id : names.getFirst();
                entries.add(new EntityCatalog.Entry(AREA, name, id, aliasesBesides(name, names)));
            }
        });
    }

    /**
     * Die Zusatznamen ohne den Anzeigenamen selbst und ohne Dopplungen - in der Konfiguration steht
     * der Name eines Bereichs oft noch einmal unter seinen Aliassen.
     */
    private static List<String> aliasesBesides(String name, List<String> aliases) {
        Set<String> seen = new HashSet<>();
        seen.add(NameNormalizer.canonical(name));
        return aliases.stream()
                .filter(alias -> seen.add(NameNormalizer.canonical(alias)))
                .toList();
    }

    private static String typeOf(HomeAssistantEntity entity) {
        return entity.entityId().startsWith("light.") ? LIGHT : ROUTINE;
    }
}
