package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

import io.github.yannicks99.jarvis_mcp.common.Power;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * Die Home-Assistant-Werkzeuge, die JARVIS-MCP der KI anbietet (Anforderungskatalog 4a).
 *
 * <p>Alle Rueckgaben sind kurze deutsche Saetze, denn der Empfaenger ist das Sprachmodell, das
 * daraus seine Antwort formt. Auch Fehlschlaege kommen als Text zurueck statt als Ausnahme,
 * solange die KI etwas damit anfangen kann - ein falsch verstandener Lichtname soll zu einem
 * zweiten, besseren Versuch fuehren und nicht zu einem Protokollfehler, der beim Modell nur als
 * "Werkzeug kaputt" ankommt. Geworfen wird nur, wenn Home Assistant selbst nicht mitspielt.
 *
 * <p>Jedes Werkzeug traegt die MCP-{@code ToolAnnotations} (Anforderungskatalog JARVIS-SemanticCache,
 * Abschnitt 4, und JARVIS-CacheDifferenzierung). Der JARVIS-AIService liest daraus, welche Werkzeuge
 * er ohne erneute Rueckfrage beim Sprachmodell ausfuehren darf (alle mit {@code readOnlyHint = false}),
 * ob er dazu gelernte Antworten wiederverwenden darf und ob er einen Aufruf nach einem
 * Verbindungsabbruch wiederholen darf (beides nur mit {@code idempotentHint = true}). Die Hinweise
 * sind damit keine Dokumentation, sondern steuern Verhalten - eine falsche Angabe hier fuehrt dort zu
 * einer doppelt ausgefuehrten Aktion oder einer Antwort, die nicht stimmt.
 *
 * <p>Die Parameter, die einen Namen entgegennehmen, heissen wie die Typen im Entity-Katalog
 * ({@link HomeAssistantResources}). Daran prueft der AIService, dass das Sprachmodell einen Namen der
 * richtigen Art uebergeben hat, bevor er einen Aufruf lernt.
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
            annotations = @McpAnnotations(readOnlyHint = false, idempotentHint = true,
                    destructiveHint = false, openWorldHint = false),
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

        client.callService("light", service(parsed.get()), Map.of("area_id", areaId.get()));
        log.info("Lichter im Bereich {} ({}) {}", area, areaId.get(), participle(parsed.get()));
        return "Alle Lichter im Bereich '%s' wurden %s.".formatted(area, participle(parsed.get()));
    }

    @McpTool(name = "set_light_power",
            annotations = @McpAnnotations(readOnlyHint = false, idempotentHint = true,
                    destructiveHint = false, openWorldHint = false),
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
                client.callService("light", service(parsed.get()), Map.of("entity_id", entity.entityId()));
                log.info("Licht {} ({}) {}", entity.name(), entity.entityId(), participle(parsed.get()));
                yield "Das Licht '%s' wurde %s.".formatted(entity.name(), participle(parsed.get()));
            }
            case EntityLookup.NotFound(List<String> available) -> unknownLight(light, available);
            case EntityLookup.Ambiguous(List<String> candidates) -> ambiguousLight(light, candidates);
        };
    }

    // Bewusst nicht idempotent: Szenen und Skripte werden in Home Assistant frei definiert und
    // garantieren keine reine Zustandssetzung - ein Skript darf etwa etwas umschalten. Wer eine
    // Routine erneut ausloest, bekommt unter Umstaenden nicht dasselbe Ergebnis. Fuer einzelne
    // Routinen sagt das Label jarvis-idempotent es trotzdem zu (siehe IdempotentEntities).
    //
    // Die Rueckgaben mit echten Umlauten: Ohne Idempotenz-Zusage spricht der AIService sie
    // unveraendert als Antwort, statt sie vom Sprachmodell umformulieren zu lassen.
    @McpTool(name = "run_ha_routine",
            annotations = @McpAnnotations(readOnlyHint = false, idempotentHint = false,
                    destructiveHint = true, openWorldHint = false),
            description = """
                    Loest eine in Home Assistant hinterlegte Routine aus - eine Szene oder ein \
                    Skript, das mehrere Geraete auf einmal schaltet, z. B. "Gute Nacht". \
                    Fuer Anweisungen, die eine solche Routine beim Namen nennen, statt einzelne \
                    Geraete zu benennen.""")
    public String runRoutine(
            @McpToolParam(required = true,
                    description = "Name der Routine, so wie sie in Home Assistant heisst, z. B. \"Gute Nacht\".")
            String routine) {

        return switch (index.find(routine, ROUTINES)) {
            case EntityLookup.Found(HomeAssistantEntity entity) -> {
                // Szenen und Skripte sind zwei Domains mit demselben Dienstnamen - welcher es ist,
                // steht in der entity_id.
                String domain = entity.entityId().startsWith(SCRIPT_DOMAIN) ? "script" : "scene";
                client.callService(domain, "turn_on", Map.of("entity_id", entity.entityId()));
                log.info("Routine {} ({}) ausgeloest", entity.name(), entity.entityId());
                yield "Die Routine '%s' wurde ausgelöst.".formatted(entity.name());
            }
            case EntityLookup.NotFound(List<String> available) -> available.isEmpty()
                    ? "In Home Assistant ist keine Routine mit dem Namen '%s' hinterlegt.".formatted(routine)
                    : "Es gibt keine Routine namens '%s'. Verfügbar sind: %s."
                            .formatted(routine, String.join(", ", available));
            case EntityLookup.Ambiguous(List<String> candidates) ->
                    "'%s' passt auf mehrere Routinen: %s. Bitte eine davon genau benennen."
                            .formatted(routine, String.join(", ", candidates));
        };
    }

    @McpTool(name = "get_lights_status",
            annotations = @McpAnnotations(readOnlyHint = true, idempotentHint = true,
                    destructiveHint = false, openWorldHint = false),
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
            annotations = @McpAnnotations(readOnlyHint = true, idempotentHint = true,
                    destructiveHint = false, openWorldHint = false),
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

    /** Der Name des Home-Assistant-Dienstes zu einem Schaltzustand. */
    private static String service(Power power) {
        return power == Power.ON ? "turn_on" : "turn_off";
    }

    /** Fuer die Rueckmeldung an die KI, z. B. "eingeschaltet". */
    private static String participle(Power power) {
        return power == Power.ON ? "eingeschaltet" : "ausgeschaltet";
    }

    private static String invalidPower(String power) {
        return "'%s' ist kein gueltiger Schaltzustand - erwartet wird \"on\" oder \"off\".".formatted(power);
    }
}
