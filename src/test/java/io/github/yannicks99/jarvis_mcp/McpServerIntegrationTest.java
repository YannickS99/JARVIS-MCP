package io.github.yannicks99.jarvis_mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yannicks99.jarvis_mcp.tools.homeassistant.StubHomeAssistant;
import io.github.yannicks99.jarvis_mcp.tools.monitoring.StubMonitoringTool;
import org.assertj.core.api.InstanceOfAssertFactories;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Spricht ueber das echte MCP-Protokoll mit dem laufenden Server: Handshake, Werkzeugliste,
 * Werkzeugaufruf - genau der Weg, den der JARVIS-AIService spaeter nimmt.
 *
 * <p>Deshalb der offizielle MCP-Client statt handgeschriebener JSON-RPC-Aufrufe: Nur so wird auch
 * geprueft, dass Streamable HTTP, die erzeugten Eingabeschemata und die Token-Pruefung
 * zusammenpassen, statt nur die Java-Methoden fuer sich genommen zu testen.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "jarvis-mcp.auth.token=geheim",
                "jarvis-mcp.home-assistant.cache-ttl=5m",
                "jarvis-mcp.home-assistant.token=ha-token",
                "jarvis-mcp.monitoring.token=monitoring-geheim",
                // Bereiche aus einer YAML-Datei - derselbe Weg wie config/application.yaml im Betrieb.
                "spring.config.import=classpath:test-areas.yaml",
                // Der Health-Port darf nicht fest belegt sein, sonst kollidieren parallele Laeufe.
                "management.server.port=0"
        })
class McpServerIntegrationTest {

    private static StubHomeAssistant homeAssistant;
    private static StubMonitoringTool monitoringTool;

    @LocalServerPort
    private int port;

    private McpSyncClient client;

    @BeforeAll
    static void startHomeAssistant() throws IOException {
        homeAssistant = new StubHomeAssistant(StubHomeAssistant.states(
                StubHomeAssistant.entity("light.stehlampe", "Stehlampe"),
                StubHomeAssistant.entity("light.buero_decke", "Bürolicht"),
                StubHomeAssistant.entity("script.gute_nacht", "Gute Nacht")));
        // Die Bereiche kommen aus Home Assistant - "Arbeitszimmer" steht in keiner Konfiguration.
        homeAssistant.areas("wohnzimmer", "Wohnzimmer", "arbeitszimmer", "Arbeitszimmer");
        homeAssistant.labeled("script.gute_nacht");
        homeAssistant.lights(
                StubHomeAssistant.light("light.stehlampe", "Stehlampe", "on", "wohnzimmer", "Wohnzimmer"),
                StubHomeAssistant.light("light.buero_decke", "Bürolicht", "off",
                        "arbeitszimmer", "Arbeitszimmer"));
    }

    @BeforeAll
    static void startMonitoringTool() throws IOException {
        monitoringTool = new StubMonitoringTool();
        monitoringTool.status(StubMonitoringTool.status(3, 1, 1, 0, 0, 1,
                StubMonitoringTool.application(1L, "Monetheus", "UP", "HTTP 200", true,
                        "monetheus-backend", "running"),
                StubMonitoringTool.application(2L, "FilmPickr", "DOWN", "Verbindung abgelehnt", true,
                        "filmpickr-backend", "exited"),
                StubMonitoringTool.application(3L, "Odysseus", "INACTIVE",
                        "Ueber das Monitoring Tool gestoppt", true, "odysseus", "exited")));
    }

    @AfterAll
    static void stopStubs() {
        homeAssistant.close();
        monitoringTool.close();
    }

    @DynamicPropertySource
    static void stubAddresses(DynamicPropertyRegistry registry) {
        registry.add("jarvis-mcp.home-assistant.base-url", homeAssistant::baseUrl);
        registry.add("jarvis-mcp.monitoring.base-url", monitoringTool::baseUrl);
    }

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.closeGracefully();
            client = null;
        }
        homeAssistant.calls().clear();
        monitoringTool.actions().clear();
    }

    private McpSyncClient connect(String token) {
        HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                        .endpoint("/mcp")
                        .httpRequestCustomizer((builder, method, uri, body, context) ->
                                builder.header("Authorization", "Bearer " + token))
                        .build();
        McpSyncClient connected = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(20))
                .build();
        connected.initialize();
        return connected;
    }

    @Test
    @DisplayName("der Handshake gelingt und der Server nennt sich jarvis-mcp")
    void handshake() {
        client = connect("geheim");
        McpSchema.InitializeResult result = client.getCurrentInitializationResult();

        assertThat(result.serverInfo().name()).isEqualTo("jarvis-mcp");
        assertThat(result.capabilities().tools()).isNotNull();
    }

    @Test
    @DisplayName("ohne gueltiges Token kommt keine Verbindung zustande")
    void rejectsWrongToken() {
        assertThatThrownBy(() -> connect("falsch")).isNotNull();
    }

    @Test
    @DisplayName("die Werkzeuge beider Module stehen mit ihren Parametern bereit")
    void listsTools() {
        client = connect("geheim");
        List<McpSchema.Tool> tools = client.listTools().tools();

        assertThat(tools).extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder("set_area_lights_power", "set_light_power", "run_ha_routine",
                        "get_lights_status", "get_light_status",
                        "get_applications_status", "set_application_power");

        McpSchema.Tool areaTool = tools.stream()
                .filter(tool -> tool.name().equals("set_area_lights_power"))
                .findFirst().orElseThrow();
        assertThat(areaTool.description()).contains("Bereichs");
        // Das Eingabeschema wird aus den Methodenparametern erzeugt - es soll genau die beiden
        // Parameter fordern, die der Anforderungskatalog nennt, und keine weiteren.
        assertThat(areaTool.inputSchema()).extracting("properties", InstanceOfAssertFactories.MAP)
                .containsOnlyKeys("area", "power");
        assertThat(areaTool.inputSchema()).extracting("required", InstanceOfAssertFactories.LIST)
                .containsExactlyInAnyOrder("area", "power");

        // Der Parameter heisst wie der Typ im Entity-Katalog - daran prueft der AIService, dass
        // das Sprachmodell wirklich eine Routine uebergeben hat und keinen Bereich.
        McpSchema.Tool routineTool = tools.stream()
                .filter(tool -> tool.name().equals("run_ha_routine"))
                .findFirst().orElseThrow();
        assertThat(routineTool.inputSchema()).extracting("properties", InstanceOfAssertFactories.MAP)
                .containsOnlyKeys("routine");

        // Beim Status ist der Bereich optional - sonst kann die KI nicht nach dem ganzen Haus
        // fragen, ohne sich einen Bereich auszudenken.
        McpSchema.Tool statusTool = tools.stream()
                .filter(tool -> tool.name().equals("get_lights_status"))
                .findFirst().orElseThrow();
        assertThat(statusTool.inputSchema()).extracting("properties", InstanceOfAssertFactories.MAP)
                .containsOnlyKeys("area");
        assertThat(statusTool.inputSchema()).extracting("required", InstanceOfAssertFactories.LIST)
                .doesNotContain("area");

        // Dasselbe beim Monitoring: Die Anwendung ist optional, damit die KI nach allen fragen kann.
        McpSchema.Tool applicationsTool = tools.stream()
                .filter(tool -> tool.name().equals("get_applications_status"))
                .findFirst().orElseThrow();
        assertThat(applicationsTool.inputSchema()).extracting("properties", InstanceOfAssertFactories.MAP)
                .containsOnlyKeys("application");
        assertThat(applicationsTool.inputSchema()).extracting("required", InstanceOfAssertFactories.LIST)
                .doesNotContain("application");
    }

    @Test
    @DisplayName("jedes Werkzeug sagt, ob es etwas veraendert und ob eine Wiederholung dasselbe bewirkt")
    void annotatesCacheability() {
        client = connect("geheim");
        Map<String, McpSchema.ToolAnnotations> annotations = client.listTools().tools().stream()
                .collect(java.util.stream.Collectors.toMap(McpSchema.Tool::name, McpSchema.Tool::annotations));

        // Zustandsveraendernd - der AIService darf sie aus dem Cache ausfuehren.
        assertThat(annotations).allSatisfy((name, hints) ->
                assertThat(!hints.readOnlyHint()).as(name).isEqualTo(List.of("set_area_lights_power",
                        "set_light_power", "set_application_power", "run_ha_routine").contains(name)));
        // Davon idempotent - nur fuer diese gelten gelernte Antworten fuer das ganze Werkzeug.
        assertThat(annotations).allSatisfy((name, hints) -> {
            boolean idempotentAction = !hints.readOnlyHint() && hints.idempotentHint();
            assertThat(idempotentAction).as(name).isEqualTo(
                    List.of("set_area_lights_power", "set_light_power", "set_application_power").contains(name));
        });
        // Routinen sind frei definiert und deshalb bewusst nicht idempotent - die Zusage gibt es
        // nur je Routine, ueber das Label im Entity-Katalog.
        assertThat(annotations.get("run_ha_routine").idempotentHint()).isFalse();
    }

    @Test
    @DisplayName("die ansprechbaren Namen stehen als Entity-Resources bereit")
    void listsEntityResources() {
        client = connect("geheim");

        assertThat(client.listResources().resources())
                .extracting(McpSchema.Resource::uri, McpSchema.Resource::mimeType)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("homeassistant://areas-and-entities",
                                "application/vnd.jarvis.entities+json"),
                        org.assertj.core.groups.Tuple.tuple("monitoring://applications",
                                "application/vnd.jarvis.entities+json"));
    }

    @Test
    @DisplayName("die Home-Assistant-Resource nennt Bereiche samt Alias, Lichter und Routinen")
    void readsHomeAssistantEntities() {
        client = connect("geheim");

        String json = resourceText("homeassistant://areas-and-entities");

        assertThat(json)
                .contains("{\"type\":\"area\",\"name\":\"Arbeitszimmer\",\"ref\":\"arbeitszimmer\",\"aliases\":[\"Büro\"]}")
                .contains("{\"type\":\"light\",\"name\":\"Stehlampe\",\"ref\":\"light.stehlampe\",\"aliases\":[]}")
                // Traegt in Home Assistant das Label jarvis-idempotent.
                .contains("{\"type\":\"routine\",\"name\":\"Gute Nacht\",\"ref\":\"script.gute_nacht\","
                        + "\"aliases\":[],\"idempotent\":true}");
        // Ein Abruf liest den warmgehaltenen Stand - geschaltet wird dabei nichts.
        assertThat(homeAssistant.calls()).isEmpty();
    }

    @Test
    @DisplayName("die Monitoring-Resource nennt die Anwendungen unter ihrem Anwendungsnamen")
    void readsApplications() {
        client = connect("geheim");

        assertThat(resourceText("monitoring://applications"))
                .contains("{\"type\":\"application\",\"name\":\"Monetheus\",\"ref\":\"1\",\"aliases\":[]}")
                .contains("\"name\":\"Odysseus\"");
        assertThat(monitoringTool.actions()).isEmpty();
    }

    @Test
    @DisplayName("die Statusabfrage der Anwendungen laeuft ueber das echte Protokoll")
    void reportsApplicationsStatus() {
        client = connect("geheim");

        McpSchema.CallToolResult result = call("get_applications_status", Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(text(result))
                .contains("Von 3 Anwendungen laeuft 1.")
                .contains("Ausgefallen: FilmPickr.")
                .contains("Absichtlich gestoppt: Odysseus.");
        // Eine Statusfrage schaltet nichts.
        assertThat(monitoringTool.actions()).isEmpty();
    }

    @Test
    @DisplayName("eine Anwendung wird ueber ihren Namen gestoppt, nicht ueber den Containernamen")
    void switchesApplicationByName() {
        client = connect("geheim");

        McpSchema.CallToolResult result = call("set_application_power",
                Map.of("application", "monetheus", "power", "aus"));

        assertThat(result.isError()).isFalse();
        assertThat(monitoringTool.actions()).singleElement().satisfies(action -> {
            assertThat(action.applicationId()).isEqualTo(1L);
            assertThat(action.action()).isEqualTo("stop");
            assertThat(action.token()).isEqualTo("Bearer monitoring-geheim");
        });
    }

    @Test
    @DisplayName("ohne Bereich kommt der Status des ganzen Hauses")
    void reportsStatusForWholeHouse() {
        client = connect("geheim");

        McpSchema.CallToolResult result = call("get_lights_status", Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(text(result))
                .contains("Wohnzimmer: Stehlampe")
                .contains("Ganz aus ist der Bereich Arbeitszimmer");
        // Eine Statusfrage schaltet nichts.
        assertThat(homeAssistant.calls()).isEmpty();
    }

    @Test
    @DisplayName("mit Bereich beschraenkt sich der Status auf diesen - auch ueber einen Alias")
    void reportsStatusForArea() {
        client = connect("geheim");

        McpSchema.CallToolResult result = call("get_lights_status", Map.of("area", "büro"));

        assertThat(text(result))
                .contains("Arbeitszimmer")
                .contains("kein Licht an")
                .doesNotContain("Stehlampe");
    }

    @Test
    @DisplayName("ein einzelnes Licht wird auch fuer die Statusfrage ueber seinen Namen aufgeloest")
    void reportsStatusForSingleLight() {
        client = connect("geheim");

        assertThat(text(call("get_light_status", Map.of("light", "bürolicht"))))
                .isEqualTo("Das Licht 'Bürolicht' im Bereich 'Arbeitszimmer' ist aus.");
        assertThat(text(call("get_light_status", Map.of("light", "Stehlampe"))))
                .isEqualTo("Das Licht 'Stehlampe' im Bereich 'Wohnzimmer' ist an.");
    }

    @Test
    @DisplayName("ein unbekanntes Licht fuehrt auch beim Status zu Vorschlaegen statt zu einem Fehler")
    void unknownLightIsAnswerableOnStatus() {
        client = connect("geheim");

        McpSchema.CallToolResult result = call("get_light_status", Map.of("light", "Gartenlicht"));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("Gartenlicht").contains("Stehlampe");
    }

    @Test
    @DisplayName("ein Bereich aus Home Assistant wird ohne jede Konfiguration gefunden")
    void switchesAreaFromHomeAssistant() {
        client = connect("geheim");

        McpSchema.CallToolResult result = call("set_area_lights_power",
                Map.of("area", "Arbeitszimmer", "power", "on"));

        assertThat(result.isError()).isFalse();
        assertThat(homeAssistant.calls()).singleElement().satisfies(serviceCall -> {
            assertThat(serviceCall.service()).isEqualTo("turn_on");
            assertThat(serviceCall.body()).contains("\"area_id\":\"arbeitszimmer\"");
        });
    }

    @Test
    @DisplayName("ein Bereichsalias landet als area_id bei Home Assistant")
    void switchesAreaByAlias() {
        client = connect("geheim");

        McpSchema.CallToolResult result = call("set_area_lights_power", Map.of("area", "büro", "power", "off"));

        assertThat(result.isError()).isFalse();
        assertThat(homeAssistant.calls()).singleElement().satisfies(serviceCall -> {
            assertThat(serviceCall.domain()).isEqualTo("light");
            assertThat(serviceCall.service()).isEqualTo("turn_off");
            assertThat(serviceCall.body()).contains("\"area_id\":\"arbeitszimmer\"");
        });
    }

    @Test
    @DisplayName("ein einzelnes Licht wird ueber seinen Anzeigenamen aufgeloest")
    void switchesSingleLight() {
        client = connect("geheim");

        McpSchema.CallToolResult result = call("set_light_power", Map.of("light", "bürolicht", "power", "on"));

        assertThat(text(result)).contains("Bürolicht").contains("eingeschaltet");
        assertThat(homeAssistant.calls()).singleElement().satisfies(serviceCall -> {
            assertThat(serviceCall.service()).isEqualTo("turn_on");
            assertThat(serviceCall.body()).contains("\"entity_id\":\"light.buero_decke\"");
        });
    }

    @Test
    @DisplayName("eine Routine wird als Skript ausgeloest")
    void runsRoutine() {
        client = connect("geheim");

        McpSchema.CallToolResult result = call("run_ha_routine", Map.of("routine", "Gute Nacht"));

        assertThat(text(result)).isEqualTo("Die Routine 'Gute Nacht' wurde ausgelöst.");
        assertThat(homeAssistant.calls()).singleElement().satisfies(serviceCall -> {
            assertThat(serviceCall.domain()).isEqualTo("script");
            assertThat(serviceCall.service()).isEqualTo("turn_on");
            assertThat(serviceCall.body()).contains("\"entity_id\":\"script.gute_nacht\"");
        });
    }

    @Test
    @DisplayName("ein unbekannter Name fuehrt zu einer Antwort mit Vorschlaegen, nicht zu einem Fehler")
    void unknownLightIsAnswerable() {
        client = connect("geheim");

        McpSchema.CallToolResult result = call("set_light_power", Map.of("light", "Gartenlicht", "power", "on"));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("Gartenlicht").contains("Stehlampe");
        assertThat(homeAssistant.calls()).isEmpty();
    }

    @Test
    @DisplayName("ein unsinniger Schaltzustand wird nicht geraten")
    void invalidPowerIsRejected() {
        client = connect("geheim");

        assertThat(text(call("set_light_power", Map.of("light", "Stehlampe", "power", "heller"))))
                .contains("\"on\"");
        assertThat(homeAssistant.calls()).isEmpty();
    }

    private McpSchema.CallToolResult call(String tool, Map<String, Object> arguments) {
        return client.callTool(new McpSchema.CallToolRequest(tool, arguments));
    }

    private String resourceText(String uri) {
        McpSchema.ReadResourceResult result = client.readResource(new McpSchema.ReadResourceRequest(uri));
        assertThat(result.contents()).singleElement()
                .extracting(McpSchema.ResourceContents::mimeType)
                .isEqualTo("application/vnd.jarvis.entities+json");
        return ((McpSchema.TextResourceContents) result.contents().getFirst()).text();
    }

    private static String text(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(content -> ((McpSchema.TextContent) content).text())
                .findFirst().orElseThrow();
    }
}
