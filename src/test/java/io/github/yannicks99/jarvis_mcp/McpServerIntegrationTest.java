package io.github.yannicks99.jarvis_mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yannicks99.jarvis_mcp.tools.homeassistant.StubHomeAssistant;
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
                // Bereiche aus einer YAML-Datei - derselbe Weg wie config/application.yaml im Betrieb.
                "spring.config.import=classpath:test-areas.yaml",
                // Der Health-Port darf nicht fest belegt sein, sonst kollidieren parallele Laeufe.
                "management.server.port=0"
        })
class McpServerIntegrationTest {

    private static StubHomeAssistant homeAssistant;

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
    }

    @AfterAll
    static void stopHomeAssistant() {
        homeAssistant.close();
    }

    @DynamicPropertySource
    static void homeAssistantAddress(DynamicPropertyRegistry registry) {
        registry.add("jarvis-mcp.home-assistant.base-url", homeAssistant::baseUrl);
    }

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.closeGracefully();
            client = null;
        }
        homeAssistant.calls().clear();
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
    @DisplayName("die drei Home-Assistant-Werkzeuge stehen mit ihren Parametern bereit")
    void listsTools() {
        client = connect("geheim");
        List<McpSchema.Tool> tools = client.listTools().tools();

        assertThat(tools).extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder("set_area_lights_power", "set_light_power", "run_ha_routine");

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

        McpSchema.CallToolResult result = call("run_ha_routine", Map.of("name", "Gute Nacht"));

        assertThat(text(result)).contains("Gute Nacht");
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

    private static String text(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(content -> ((McpSchema.TextContent) content).text())
                .findFirst().orElseThrow();
    }
}
