package io.github.yannicks99.jarvis_mcp.tools.monitoring;

import io.github.yannicks99.jarvis_mcp.common.EntityCatalog;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpResource;
import tools.jackson.databind.ObjectMapper;

/**
 * Die Resources des Monitoring-Tool-Moduls (Anforderungskatalog JARVIS-SemanticCache, Abschnitt 4).
 *
 * <p>Gegenstueck zu den Bereichen und Lichtern des Home-Assistant-Moduls: Der JARVIS-AIService
 * maskiert auch Anwendungsnamen ("Stoppe Monetheus" wird zu "Stoppe {application}"), sonst koennte er
 * {@code set_application_power} nicht zwischenspeichern, ohne den Namen der zuerst gesprochenen
 * Anwendung auf jede weitere zu uebertragen.
 *
 * <p>Ohne eigenen Zwischenspeicher, aus demselben Grund wie bei den Werkzeugen: Namen und Zustaende
 * kommen in derselben schlanken Antwort, und der AIService fragt ohnehin nur im Hintergrundtakt.
 */
public class MonitoringResources {

    static final String APPLICATIONS_URI = "monitoring://applications";

    /** Heisst wie der Parameter von {@code set_application_power}, der den Namen entgegennimmt. */
    static final String APPLICATION = "application";

    private final MonitoringClient client;
    private final ObjectMapper jsonMapper;

    public MonitoringResources(MonitoringClient client, ObjectMapper jsonMapper) {
        this.client = client;
        this.jsonMapper = jsonMapper;
    }

    @McpResource(uri = APPLICATIONS_URI,
            name = "applications",
            title = "Anwendungen",
            description = """
                    Alle im Monitoring Tool hinterlegten Anwendungen, unter deren Namen die \
                    Monitoring-Werkzeuge ansprechbar sind - jeweils Anzeigename und Kennung.""",
            mimeType = EntityCatalog.MIME_TYPE)
    public String applications() {
        return jsonMapper.writeValueAsString(catalog());
    }

    EntityCatalog catalog() {
        List<EntityCatalog.Entry> entries = client.status().applications().stream()
                .map(application -> new EntityCatalog.Entry(APPLICATION, application.name(),
                        application.id() == null ? null : application.id().toString(), List.of()))
                .toList();
        return new EntityCatalog(entries);
    }
}
