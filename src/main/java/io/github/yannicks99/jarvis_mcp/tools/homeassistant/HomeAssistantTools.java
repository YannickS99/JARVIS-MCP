package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * Die Home-Assistant-Werkzeuge, die JARVIS-MCP der KI anbietet (Anforderungskatalog 4a).
 *
 * <p>Alle Rueckgaben sind kurze deutsche Saetze, denn der Empfaenger ist das Sprachmodell, das
 * daraus seine Antwort formt. Auch Fehlschlaege kommen als Text zurueck statt als Ausnahme,
 * solange die KI etwas damit anfangen kann - ein falsch verstandener Lichtname soll zu einem
 * zweiten, besseren Versuch fuehren und nicht zu einem Protokollfehler, der beim Modell nur als
 * "Werkzeug kaputt" ankommt. Geworfen wird nur, wenn Home Assistant selbst nicht mitspielt.
 */
public class HomeAssistantTools {

    private static final Logger log = LoggerFactory.getLogger(HomeAssistantTools.class);

    private static final String LIGHT_DOMAIN = "light.";
    private static final String SCRIPT_DOMAIN = "script.";
    private static final String SCENE_DOMAIN = "scene.";

    private static final List<String> LIGHTS = List.of(LIGHT_DOMAIN);
    /** Reihenfolge ist Vorrang: Ein Skript schlaegt ein gleich benanntes Szenario. */
    private static final List<String> ROUTINES = List.of(SCRIPT_DOMAIN, SCENE_DOMAIN);

    private final HomeAssistantClient client;
    private final HomeAssistantEntityIndex index;
    private final AreaResolver areas;

    public HomeAssistantTools(HomeAssistantClient client, HomeAssistantEntityIndex index, AreaResolver areas) {
        this.client = client;
        this.index = index;
        this.areas = areas;
    }

    @McpTool(name = "set_area_lights_power",
            description = """
                    Schaltet alle Lichter eines Raums bzw. Bereichs im Haus gemeinsam an oder aus. \
                    Fuer Anweisungen, die einen Raum nennen statt einer einzelnen Lampe, \
                    z. B. "mach das Licht im Wohnzimmer aus".""")
    public String setAreaLightsPower(
            @McpToolParam(required = true,
                    description = "Name des Raums bzw. Bereichs, z. B. \"Wohnzimmer\" oder \"Büro\".")
            String area,
            @McpToolParam(required = true, description = "\"on\" zum Einschalten, \"off\" zum Ausschalten.")
            String power) {

        Optional<Power> parsed = Power.parse(power);
        if (parsed.isEmpty()) {
            return invalidPower(power);
        }

        Optional<String> areaId = areas.resolve(area);
        if (areaId.isEmpty()) {
            return unknownArea(area);
        }

        client.callService("light", parsed.get().service(), Map.of("area_id", areaId.get()));
        log.info("Lichter im Bereich {} ({}) {}", area, areaId.get(), parsed.get().participle());
        return "Alle Lichter im Bereich '%s' wurden %s.".formatted(area, parsed.get().participle());
    }

    @McpTool(name = "set_light_power",
            description = """
                    Schaltet ein einzelnes, namentlich genanntes Licht an oder aus. \
                    Fuer Anweisungen, die eine bestimmte Lampe nennen, z. B. \
                    "schalte die Stehlampe ein". Fuer einen ganzen Raum stattdessen \
                    set_area_lights_power verwenden.""")
    public String setLightPower(
            @McpToolParam(required = true,
                    description = "Name des Lichts, so wie es in Home Assistant heisst, z. B. \"Stehlampe\".")
            String light,
            @McpToolParam(required = true, description = "\"on\" zum Einschalten, \"off\" zum Ausschalten.")
            String power) {

        Optional<Power> parsed = Power.parse(power);
        if (parsed.isEmpty()) {
            return invalidPower(power);
        }

        return switch (index.find(light, LIGHTS)) {
            case EntityLookup.Found(HomeAssistantEntity entity) -> {
                client.callService("light", parsed.get().service(), Map.of("entity_id", entity.entityId()));
                log.info("Licht {} ({}) {}", entity.name(), entity.entityId(), parsed.get().participle());
                yield "Das Licht '%s' wurde %s.".formatted(entity.name(), parsed.get().participle());
            }
            case EntityLookup.NotFound(List<String> available) -> unknownLight(light, available);
            case EntityLookup.Ambiguous(List<String> candidates) -> ambiguousLight(light, candidates);
        };
    }

    @McpTool(name = "run_ha_routine",
            description = """
                    Loest eine in Home Assistant hinterlegte Routine aus - eine Szene oder ein \
                    Skript, das mehrere Geraete auf einmal schaltet, z. B. "Gute Nacht". \
                    Fuer Anweisungen, die eine solche Routine beim Namen nennen, statt einzelne \
                    Geraete zu benennen.""")
    public String runRoutine(
            @McpToolParam(required = true,
                    description = "Name der Routine, so wie sie in Home Assistant heisst, z. B. \"Gute Nacht\".")
            String name) {

        return switch (index.find(name, ROUTINES)) {
            case EntityLookup.Found(HomeAssistantEntity entity) -> {
                // Szenen und Skripte sind zwei Domains mit demselben Dienstnamen - welcher es ist,
                // steht in der entity_id.
                String domain = entity.entityId().startsWith(SCRIPT_DOMAIN) ? "script" : "scene";
                client.callService(domain, "turn_on", Map.of("entity_id", entity.entityId()));
                log.info("Routine {} ({}) ausgeloest", entity.name(), entity.entityId());
                yield "Die Routine '%s' wurde ausgeloest.".formatted(entity.name());
            }
            case EntityLookup.NotFound(List<String> available) -> available.isEmpty()
                    ? "In Home Assistant ist keine Routine mit dem Namen '%s' hinterlegt.".formatted(name)
                    : "Es gibt keine Routine namens '%s'. Verfuegbar sind: %s."
                            .formatted(name, String.join(", ", available));
            case EntityLookup.Ambiguous(List<String> candidates) ->
                    "'%s' passt auf mehrere Routinen: %s. Bitte eine davon genau benennen."
                            .formatted(name, String.join(", ", candidates));
        };
    }

    @McpTool(name = "get_lights_status",
            description = """
                    Sagt, welche Lichter gerade an sind - im ganzen Haus oder, wenn ein Bereich \
                    genannt wird, nur in diesem. Fuer Fragen nach dem Zustand statt nach einer \
                    Schaltung, z. B. "wo brennt noch Licht?" oder "ist im Wohnzimmer noch Licht \
                    an?". Fuer eine einzelne, namentlich genannte Lampe stattdessen \
                    get_light_status verwenden.""")
    public String getLightsStatus(
            @McpToolParam(required = false,
                    description = "Optional: Name des Raums bzw. Bereichs, z. B. \"Wohnzimmer\". "
                            + "Weglassen, wenn nach dem ganzen Haus gefragt ist.")
            String area) {

        List<HomeAssistantLightStatus> lights = client.lightStates();

        if (area == null || area.isBlank()) {
            return LightStatusReport.overview(lights);
        }

        Optional<String> areaId = areas.resolve(area);
        if (areaId.isEmpty()) {
            return unknownArea(area);
        }

        List<HomeAssistantLightStatus> inArea = lights.stream()
                .filter(light -> areaId.get().equals(light.areaId()))
                .toList();
        // Der Bereichsname aus Home Assistant statt der gesprochenen Fassung: Wer nach "buero"
        // fragt, soll "Büro" zu hoeren bekommen. Ohne Licht im Bereich bleibt nur die Eingabe.
        String name = inArea.isEmpty() ? area : inArea.getFirst().areaName();
        return LightStatusReport.forArea(name, inArea);
    }

    @McpTool(name = "get_light_status",
            description = """
                    Sagt, ob ein einzelnes, namentlich genanntes Licht gerade an oder aus ist - \
                    fuer Fragen wie "ist die Stehlampe an?". Fuer einen ganzen Raum oder das \
                    ganze Haus stattdessen get_lights_status verwenden.""")
    public String getLightStatus(
            @McpToolParam(required = true,
                    description = "Name des Lichts, so wie es in Home Assistant heisst, z. B. \"Stehlampe\".")
            String light) {

        // Aufgeloest wird ueber den warmgehaltenen Index - Namen aendern sich selten. Der Zustand
        // selbst kommt frisch, denn genau danach ist gefragt.
        return switch (index.find(light, LIGHTS)) {
            case EntityLookup.Found(HomeAssistantEntity entity) -> client.lightStates().stream()
                    .filter(status -> status.entityId().equals(entity.entityId()))
                    .findFirst()
                    .map(LightStatusReport::single)
                    // Der Index ist bis zu einer Zwischenspeicher-Laufzeit alt; das Licht kann in
                    // der Zwischenzeit in Home Assistant verschwunden sein.
                    .orElseGet(() -> "Das Licht '%s' gibt es in Home Assistant nicht mehr."
                            .formatted(entity.name()));
            case EntityLookup.NotFound(List<String> available) -> unknownLight(light, available);
            case EntityLookup.Ambiguous(List<String> candidates) -> ambiguousLight(light, candidates);
        };
    }

    /**
     * Drei verschiedene Ursachen, drei verschiedene Antworten: Die KI soll einen falschen Namen
     * noch einmal versuchen koennen, bei den beiden anderen Faellen waere jeder weitere Versuch
     * vergeblich.
     */
    private String unknownArea(String area) {
        if (!areas.loadedFromHomeAssistant()) {
            return "Die Bereiche konnten nicht aus Home Assistant gelesen werden - "
                    + "Home Assistant ist gerade nicht erreichbar.";
        }
        List<String> known = areas.knownNames();
        return known.isEmpty()
                ? "In Home Assistant sind keine Bereiche eingerichtet."
                : "Der Bereich '%s' ist nicht bekannt. Bekannte Bereiche: %s."
                        .formatted(area, String.join(", ", known));
    }

    private static String unknownLight(String light, List<String> available) {
        return available.isEmpty()
                ? "Es ist kein Licht mit dem Namen '%s' eingerichtet.".formatted(light)
                : "Es gibt kein Licht namens '%s'. Verfuegbar sind: %s."
                        .formatted(light, String.join(", ", available));
    }

    private static String ambiguousLight(String light, List<String> candidates) {
        return "'%s' passt auf mehrere Lichter: %s. Bitte eines davon genau benennen."
                .formatted(light, String.join(", ", candidates));
    }

    private static String invalidPower(String power) {
        return "'%s' ist kein gueltiger Schaltzustand - erwartet wird \"on\" oder \"off\".".formatted(power);
    }
}
