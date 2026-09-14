package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

import io.github.yannicks99.jarvis_mcp.common.NameNormalizer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JsonParser;
import tools.jackson.core.type.TypeReference;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;

/**
 * Schmaler REST-Client gegen die Home-Assistant-API. Kennt nur die beiden Aufrufe, die das
 * Tool-Modul braucht - Zustaende lesen und einen Dienst ausloesen.
 */
public class HomeAssistantClient {

    /**
     * Liefert je Licht eine Zeile {@code [entity_id, name, state, area_id, area_name]}.
     *
     * <p>{@code to_json} uebernimmt das Maskieren, {@code or ''} faengt die Lichter ab, die keinem
     * Bereich zugeordnet sind - sonst stuende dort {@code null} und die Zerlegung muesste es
     * gesondert behandeln.
     */
    private static final String LIGHT_STATES_TEMPLATE = """
            {%- set found = namespace(rows=[]) -%}
            {%- for light in states.light -%}
            {%- set found.rows = found.rows
                + [[light.entity_id, light.name, light.state,
                    area_id(light.entity_id) or '', area_name(light.entity_id) or '']] -%}
            {%- endfor -%}
            {{ found.rows | to_json }}
            """;

    private static final int LIGHT_STATE_COLUMNS = 5;

    private final RestClient restClient;
    private final ObjectMapper jsonMapper;

    public HomeAssistantClient(RestClient restClient, ObjectMapper jsonMapper) {
        this.restClient = restClient;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Liest {@code GET /api/states} und gibt nur die Entitaeten der angefragten Domains zurueck.
     *
     * <p>Die Antwort enthaelt saemtliche Entitaeten samt aller Attribute und ist damit schnell
     * einige hundert Kilobyte gross - fuer die Werkzeuge sind davon aber nur {@code entity_id} und
     * {@code friendly_name} interessant. Deshalb wird der Datenstrom Token fuer Token gelesen und
     * jeder nicht gebrauchte Teilbaum uebersprungen, statt die gesamte Antwort erst in Objekte oder
     * einen Baum zu verwandeln: Es entsteht nur Muell fuer die wenigen tatsaechlich behaltenen
     * Felder, und der Body muss nie vollstaendig im Speicher liegen.
     *
     * @param domains Praefixe der {@code entity_id} inklusive Punkt, z. B. {@code "light."}
     */
    public List<HomeAssistantEntity> states(List<String> domains) {
        return restClient.get()
                .uri("/api/states")
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new HomeAssistantException("Home Assistant antwortete auf /api/states mit "
                                + response.getStatusCode());
                    }
                    try (JsonParser parser = jsonMapper.createParser(response.getBody())) {
                        return parseStates(parser, domains);
                    }
                });
    }

    /**
     * Liest die Bereiche aus Home Assistants Area Registry.
     *
     * <p>Die Registry hat keinen eigenen REST-Endpunkt - wohl aber die Template-Engine, und die
     * ist ueber {@code POST /api/template} erreichbar. {@code areas()} liefert die Kennungen,
     * {@code area_name()} den jeweiligen Anzeigenamen. Damit entfaellt der WebSocket-Client, der
     * urspruenglich fuer diesen Zweck angedacht war, und vor allem entfaellt die Handpflege: Ein
     * neuer Bereich in Home Assistant ist hier sofort bekannt.
     *
     * <p>{@code to_json} uebernimmt das Maskieren - ein Bereichsname mit Anfuehrungszeichen oder
     * Umlauten kommt dadurch unbeschadet an, statt die Antwort zu zerlegen.
     */
    public List<HomeAssistantArea> areas() {
        String template = "{{ [areas(), areas() | map('area_name') | list] | to_json }}";

        return parseAreas(renderTemplate(template));
    }

    /**
     * Liest den aktuellen Zustand aller Lichter samt Bereichszuordnung.
     *
     * <p>Wieder ueber die Template-Engine, und zwar aus zwei Gruenden: Erstens steht die
     * Bereichszuordnung einer Entitaet in keiner REST-Antwort - {@code GET /api/states} kennt sie
     * nicht, die Registry hat keinen Endpunkt. Zweitens kommt so ein Aufruf mit ein paar hundert
     * Byte zurueck statt mit den mehreren hundert Kilobyte, die {@code /api/states} fuer eine
     * einzige Frage nach dem Lichtzustand liefern wuerde.
     *
     * <p>Anders als der Entitaeten-Index wird hier nichts zwischengespeichert: Ein Zustand ist nur
     * so lange richtig, bis jemand einen Schalter drueckt.
     */
    public List<HomeAssistantLightStatus> lightStates() {
        return parseLightStates(renderTemplate(LIGHT_STATES_TEMPLATE));
    }

    /** Schickt ein Jinja-Template an Home Assistant und gibt das Ergebnis unveraendert zurueck. */
    private String renderTemplate(String template) {
        return restClient.post()
                .uri("/api/template")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_PLAIN)
                .body(Map.of("template", template))
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new HomeAssistantException("Home Assistant antwortete auf /api/template mit "
                                + response.getStatusCode()
                                + " - erlaubt das Long-Lived Access Token das Rendern von Templates?");
                    }
                    return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                });
    }

    /** Erwartet eine Zeile je Licht: {@code [entity_id, name, state, area_id, area_name]}. */
    private List<HomeAssistantLightStatus> parseLightStates(String rendered) {
        List<List<String>> rows;
        try {
            rows = jsonMapper.readValue(rendered, new TypeReference<List<List<String>>>() { });
        } catch (RuntimeException ex) {
            throw new HomeAssistantException(
                    "Unerwartete Antwort auf /api/template: " + rendered.strip(), ex);
        }

        List<HomeAssistantLightStatus> lights = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            if (row.size() != LIGHT_STATE_COLUMNS) {
                throw new HomeAssistantException("Unerwartete Antwort auf /api/template: " + rendered.strip());
            }
            lights.add(new HomeAssistantLightStatus(row.get(0), row.get(1), row.get(2),
                    row.get(3), row.get(4)));
        }
        return lights;
    }

    /** Erwartet {@code [["id1","id2"],["Name 1","Name 2"]]} - so baut es das Template oben. */
    private List<HomeAssistantArea> parseAreas(String rendered) {
        List<List<String>> pair;
        try {
            pair = jsonMapper.readValue(rendered, new TypeReference<List<List<String>>>() { });
        } catch (RuntimeException ex) {
            throw new HomeAssistantException(
                    "Unerwartete Antwort auf /api/template: " + rendered.strip(), ex);
        }
        if (pair.size() != 2 || pair.get(0).size() != pair.get(1).size()) {
            throw new HomeAssistantException("Unerwartete Antwort auf /api/template: " + rendered.strip());
        }

        List<String> ids = pair.get(0);
        List<String> names = pair.get(1);
        List<HomeAssistantArea> areas = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            areas.add(new HomeAssistantArea(ids.get(i), names.get(i)));
        }
        return areas;
    }

    /**
     * Loest {@code POST /api/services/<domain>/<service>} aus, z. B. {@code light/turn_on}.
     *
     * <p>Die Antwort (eine Liste der veraenderten Zustaende) wird verworfen: Sie kann gross sein
     * und sagt nichts, was die Werkzeuge zurueckmelden wuerden - entscheidend ist der Statuscode.
     */
    public void callService(String domain, String service, Map<String, Object> payload) {
        restClient.post()
                .uri("/api/services/{domain}/{service}", domain, service)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new HomeAssistantException("Home Assistant lehnte %s/%s ab (%s)"
                                .formatted(domain, service, response.getStatusCode()));
                    }
                    return null;
                });
    }

    private static List<HomeAssistantEntity> parseStates(JsonParser parser, List<String> domains) {
        List<HomeAssistantEntity> entities = new ArrayList<>();
        if (parser.nextToken() != JsonToken.START_ARRAY) {
            throw new HomeAssistantException("Unerwartete Antwort auf /api/states - kein JSON-Array");
        }

        while (parser.nextToken() == JsonToken.START_OBJECT) {
            String entityId = null;
            String friendlyName = null;

            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "entity_id" -> entityId = parser.getString();
                    case "attributes" -> friendlyName = readFriendlyName(parser);
                    // "state", "last_changed", "context" und Konsorten interessieren hier nicht.
                    default -> parser.skipChildren();
                }
            }

            if (entityId != null && friendlyName != null && matchesDomain(entityId, domains)) {
                entities.add(new HomeAssistantEntity(entityId, friendlyName,
                        NameNormalizer.canonical(friendlyName)));
            }
        }
        return entities;
    }

    /** Liest {@code friendly_name} aus dem Attribut-Objekt und ueberspringt den Rest. */
    private static String readFriendlyName(JsonParser parser) {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return null;
        }
        String friendlyName = null;
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            boolean wanted = "friendly_name".equals(parser.currentName());
            parser.nextToken();
            if (wanted && parser.currentToken() == JsonToken.VALUE_STRING) {
                friendlyName = parser.getString();
            } else {
                parser.skipChildren();
            }
        }
        return friendlyName;
    }

    private static boolean matchesDomain(String entityId, List<String> domains) {
        // Kleine, feste Liste (drei Eintraege) - eine Schleife ist hier schneller als ein Set.
        for (int i = 0; i < domains.size(); i++) {
            if (entityId.startsWith(domains.get(i))) {
                return true;
            }
        }
        return false;
    }
}
