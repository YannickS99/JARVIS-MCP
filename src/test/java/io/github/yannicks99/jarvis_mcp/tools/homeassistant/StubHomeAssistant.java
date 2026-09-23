package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Ein Home Assistant, das nur so viel kann, wie die Tests sehen muessen: Zustaende ausliefern und
 * Dienstaufrufe mitschreiben.
 *
 * <p>Bewusst der HTTP-Server aus dem JDK statt eines Mock-Servers als zusaetzliche Abhaengigkeit -
 * getestet wird hier der echte Weg ueber das Netz samt Datenstrom-Verarbeitung, und genau das
 * waere mit einem gemockten RestClient nicht mehr der Fall.
 */
public final class StubHomeAssistant implements AutoCloseable {

    /** Was an POST /api/services/… hereinkam, in Reihenfolge. */
    public record ServiceCall(String domain, String service, String body) {
    }

    private final HttpServer server;
    private final List<ServiceCall> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger stateRequests = new AtomicInteger();
    private final AtomicInteger templateRequests = new AtomicInteger();

    private volatile String statesBody;
    /** Antwort auf /api/template - das Rendern selbst bildet der Stub nicht nach. */
    private volatile String templateBody = "[[],[]]";
    /** Antwort auf das Lichtzustands-Template - erkannt am Wort "states.light" im Request. */
    private volatile String lightsBody = "[]";
    /** Antwort auf das Label-Template - erkannt an "label_entities" im Request. */
    private volatile String labelsBody = "[]";
    private volatile int labelsStatus = 200;
    private volatile int templateStatus = 200;
    private volatile int statesStatus = 200;
    private volatile int serviceStatus = 200;

    public StubHomeAssistant(String statesBody) throws IOException {
        this.statesBody = statesBody;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/states", this::handleStates);
        server.createContext("/api/template", this::handleTemplate);
        server.createContext("/api/services/", this::handleService);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<ServiceCall> calls() {
        return calls;
    }

    public int stateRequests() {
        return stateRequests.get();
    }

    public void statesBody(String body) {
        this.statesBody = body;
    }

    /** Bereiche, die {@code areas()} liefern soll - als id/name-Paare. */
    public void areas(String... idNamePairs) {
        StringBuilder ids = new StringBuilder("[");
        StringBuilder names = new StringBuilder("[");
        for (int i = 0; i < idNamePairs.length; i += 2) {
            if (i > 0) {
                ids.append(',');
                names.append(',');
            }
            ids.append('"').append(idNamePairs[i]).append('"');
            names.append('"').append(idNamePairs[i + 1]).append('"');
        }
        this.templateBody = ids.append("],").append(names).append("]]").insert(0, "[").toString();
    }

    public void templateStatus(int status) {
        this.templateStatus = status;
    }

    /** Die entity_ids, die das Label-Template liefern soll. */
    public void labeled(String... entityIds) {
        this.labelsBody = Arrays.stream(entityIds)
                .map(id -> '"' + id + '"')
                .collect(Collectors.joining(",", "[", "]"));
    }

    /** Nur das Label-Template scheitern lassen - etwa ein Home Assistant ohne label_entities. */
    public void labelsStatus(int status) {
        this.labelsStatus = status;
    }

    /**
     * Mehrere Templates gehen ueber denselben Endpunkt - der Stub rendert nicht, er unterscheidet
     * nur, welches gefragt war, und gibt die passende vorbereitete Antwort.
     */
    private void handleTemplate(HttpExchange exchange) throws IOException {
        templateRequests.incrementAndGet();
        String requested;
        try (InputStream in = exchange.getRequestBody()) {
            requested = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (templateStatus != 200) {
            respond(exchange, templateStatus, "unauthorized");
            return;
        }
        if (requested.contains("label_entities")) {
            respond(exchange, labelsStatus, labelsStatus == 200 ? labelsBody : "template error");
            return;
        }
        respond(exchange, 200, requested.contains("states.light") ? lightsBody : templateBody);
    }

    /** Lichter, die das Zustands-Template liefern soll - Zeilen aus {@link #light}. */
    public void lights(String... rows) {
        this.lightsBody = "[" + String.join(",", rows) + "]";
    }

    /** Eine Zeile des Zustands-Templates: {@code [entity_id, name, state, area_id, area_name]}. */
    public static String light(String entityId, String name, String state, String areaId,
            String areaName) {
        return "[\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"]"
                .formatted(entityId, name, state, areaId, areaName);
    }

    public int templateRequests() {
        return templateRequests.get();
    }

    public void statesStatus(int status) {
        this.statesStatus = status;
    }

    public void serviceStatus(int status) {
        this.serviceStatus = status;
    }

    private void handleStates(HttpExchange exchange) throws IOException {
        stateRequests.incrementAndGet();
        respond(exchange, statesStatus, statesStatus == 200 ? statesBody : "{\"message\":\"nope\"}");
    }

    private void handleService(HttpExchange exchange) throws IOException {
        String[] parts = exchange.getRequestURI().getPath().split("/");
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        calls.add(new ServiceCall(parts[parts.length - 2], parts[parts.length - 1], body));
        respond(exchange, serviceStatus, "[]");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** Eine Zustandsliste, wie Home Assistant sie liefert - inklusive Ballast, der ignoriert wird. */
    public static String states(String... entities) {
        return "[" + String.join(",", entities) + "]";
    }

    public static String entity(String entityId, String friendlyName) {
        return """
                {"entity_id":"%s","state":"off","last_changed":"2026-09-12T10:00:00Z",
                 "attributes":{"min_color_temp_kelvin":2000,"supported_color_modes":["brightness"],
                 "friendly_name":"%s","icon":"mdi:lamp"},
                 "context":{"id":"01","parent_id":null,"user_id":null}}"""
                .formatted(entityId, friendlyName);
    }
}
