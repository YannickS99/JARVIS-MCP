package io.github.yannicks99.jarvis_mcp.tools.monitoring;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ein Monitoring Tool, das nur so viel kann, wie die Tests sehen muessen: den Zustand ausliefern und
 * Container-Aktionen mitschreiben.
 *
 * <p>Bewusst der HTTP-Server aus dem JDK statt eines Mock-Servers als zusaetzliche Abhaengigkeit -
 * getestet wird hier der echte Weg ueber das Netz samt Token-Header und Fehlerformat, und genau das
 * waere mit einem gemockten RestClient nicht mehr der Fall. Dieselbe Entscheidung wie bei
 * {@link io.github.yannicks99.jarvis_mcp.tools.homeassistant.StubHomeAssistant}.
 */
public final class StubMonitoringTool implements AutoCloseable {

    /** Was an POST /applications/{id}/{start|stop} hereinkam, in Reihenfolge. */
    public record Action(long applicationId, String action, String token) {
    }

    private final HttpServer server;
    private final List<Action> actions = new CopyOnWriteArrayList<>();

    private volatile String statusBody = status(0, 0, 0, 0, 0, 0);
    private volatile int statusCode = 200;
    /** Antwort auf eine Container-Aktion; {@code null} heisst: die geschaltete Anwendung zurueckgeben. */
    private volatile String actionBody;
    private volatile int actionCode = 200;

    public StubMonitoringTool() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/integration/v1/status", this::handleStatus);
        server.createContext("/api/integration/v1/applications/", this::handleAction);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Action> actions() {
        return actions;
    }

    /** Der Zustand, den {@code GET /status} liefern soll. */
    public void status(String body) {
        this.statusBody = body;
    }

    public void statusCode(int code) {
        this.statusCode = code;
    }

    /** Die Antwort auf die naechste Container-Aktion, samt Statuscode. */
    public void actionResponse(int code, String body) {
        this.actionCode = code;
        this.actionBody = body;
    }

    /** Eine Fehlerantwort nach RFC 9457, wie das Monitoring Tool sie liefert. */
    public static String problem(int status, String title, String detail) {
        return """
                {"type":"https://monitoring.jarvis/errors/test","title":"%s","status":%d,
                 "detail":"%s","timestamp":"2026-09-15T10:00:00Z"}"""
                .formatted(title, status, detail);
    }

    /** Eine Statusantwort aus Zaehlung und Anwendungszeilen aus {@link #application}. */
    public static String status(int total, int up, int down, int degraded, int unknown, int inactive,
            String... applications) {
        return """
                {"generatedAt":"2026-09-15T10:00:00Z",
                 "summary":{"total":%d,"up":%d,"down":%d,"degraded":%d,"unknown":%d,"inactive":%d},
                 "applications":[%s]}"""
                .formatted(total, up, down, degraded, unknown, inactive, String.join(",", applications));
    }

    /**
     * Eine Anwendungszeile - inklusive Feldern, die JARVIS-MCP nicht auswertet ({@code category},
     * {@code desiredState}, {@code lastCheckAt}). Sie stehen bewusst drin: Die Antwort des Monitoring
     * Tools enthaelt sie, und dass sie beim Einlesen einfach wegfallen, soll mitgeprueft werden.
     */
    public static String application(long id, String name, String state, String message,
            boolean monitored, String containerName, String containerState) {
        return """
                {"id":%d,"name":"%s","category":"Schrotech","state":"%s","message":"%s",
                 "monitored":%b,"desiredState":"RUNNING","containerName":%s,"containerState":%s,
                 "lastCheckAt":"2026-09-15T09:59:00Z"}"""
                .formatted(id, name, state, message, monitored, quoted(containerName),
                        quoted(containerState));
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (statusCode != 200) {
            respond(exchange, statusCode, "{\"error\":\"nope\"}");
            return;
        }
        respond(exchange, 200, statusBody);
    }

    private void handleAction(HttpExchange exchange) throws IOException {
        // .../applications/{id}/{start|stop}
        String[] parts = exchange.getRequestURI().getPath().split("/");
        String action = parts[parts.length - 1];
        long id = Long.parseLong(parts[parts.length - 2]);
        actions.add(new Action(id, action, exchange.getRequestHeaders().getFirst("Authorization")));

        if (actionCode != 200) {
            respond(exchange, actionCode, actionBody);
            return;
        }
        respond(exchange, 200, actionBody != null ? actionBody
                : application(id, "Anwendung " + id, "start".equals(action) ? "DEGRADED" : "INACTIVE",
                        "geschaltet", true, "container-" + id,
                        "start".equals(action) ? "running" : "exited"));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",
                status >= 400 ? "application/problem+json" : "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
