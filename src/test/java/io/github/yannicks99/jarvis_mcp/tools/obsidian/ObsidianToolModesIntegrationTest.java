package io.github.yannicks99.jarvis_mcp.tools.obsidian;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Ueber den echten MCP-Endpunkt: Die Vault-Werkzeuge ordnen sich in {@code _meta} dem Arbeitsmodus
 * des JARVIS-AIService zu, alle anderen nicht.
 *
 * <p>Das ist die Absprache, ueber die der AIService ohne einen einzigen Werkzeugnamen entscheidet,
 * was er im Alltagsmodus anbietet ({@code tools.mcp_provider.MODES_META_KEY} dort).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "jarvis-mcp.auth.token=geheim",
                "jarvis-mcp.obsidian.enabled=true",
                "management.server.port=0"
        })
class ObsidianToolModesIntegrationTest {

    private static Path vault;

    @LocalServerPort
    private int port;

    private McpSyncClient client;

    @DynamicPropertySource
    static void vault(DynamicPropertyRegistry registry) throws IOException {
        vault = Files.createTempDirectory("vault");
        registry.add("jarvis-mcp.obsidian.root", vault::toString);
    }

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.closeGracefully();
        }
    }

    @Test
    @DisplayName("die Vault-Werkzeuge gehoeren in den Arbeitsmodus, die uebrigen gelten ueberall")
    void vaultToolsAreMarkedForWorkMode() {
        client = connect();
        Map<String, McpSchema.Tool> tools = client.listTools().tools().stream()
                .collect(Collectors.toMap(McpSchema.Tool::name, tool -> tool));

        List<String> vaultTools = List.of("read_note", "list_notes", "search_notes", "create_note",
                "append_note", "replace_section", "replace_text");
        assertThat(tools).containsKeys(vaultTools.toArray(String[]::new));
        tools.forEach((name, tool) -> {
            Object modes = tool.meta() == null ? null : tool.meta().get(WorkModeOnly.MODES_KEY);
            if (vaultTools.contains(name)) {
                assertThat(modes).as(name).isEqualTo(List.of("work"));
            } else {
                assertThat(modes).as(name).isNull();
            }
        });
    }

    private McpSyncClient connect() {
        HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                        .endpoint("/mcp")
                        .httpRequestCustomizer((builder, method, uri, body, context) ->
                                builder.header("Authorization", "Bearer geheim"))
                        .build();
        McpSyncClient connected = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
        connected.initialize();
        return connected;
    }
}
