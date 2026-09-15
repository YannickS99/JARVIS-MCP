package io.github.yannicks99.jarvis_mcp.tools.monitoring;

import io.github.yannicks99.jarvis_mcp.common.Power;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * Die Monitoring-Tool-Werkzeuge, die JARVIS-MCP der KI anbietet.
 *
 * <p>Wie bei den Home-Assistant-Werkzeugen sind alle Rueckgaben kurze deutsche Saetze, denn der
 * Empfaenger ist das Sprachmodell, das daraus seine Antwort formt. Auch Fehlschlaege kommen als Text
 * zurueck, solange die KI etwas damit anfangen kann - ein falsch verstandener Anwendungsname soll zu
 * einem zweiten, besseren Versuch fuehren, und ein geschuetzter Container zu der Auskunft, dass es
 * nicht geht. Geworfen wird nur, wenn das Monitoring Tool selbst nicht mitspielt.
 *
 * <p>Angesprochen wird ueber den <em>Anwendungsnamen</em>, nicht ueber den Containernamen: "Monetheus"
 * statt "monetheus-backend-1". Der Containername ist technisch gewachsen, der Anwendungsname ist der,
 * unter dem der Dienst im Haus bekannt ist.
 */
public class MonitoringTools {

    private static final Logger log = LoggerFactory.getLogger(MonitoringTools.class);

    private final MonitoringClient client;

    public MonitoringTools(MonitoringClient client) {
        this.client = client;
    }

    @McpTool(name = "get_applications_status",
            description = """
                    Sagt, wie es den auf dem Server laufenden Anwendungen und Diensten geht - \
                    wie viele laufen, ob etwas ausgefallen oder absichtlich gestoppt ist. \
                    Fuer Fragen nach dem Betriebszustand des Servers, z. B. "ist irgendwas \
                    ausgefallen?", "wie viele Dienste sind aus?" oder "laeuft Monetheus?". \
                    Ohne Angabe kommt der Ueberblick ueber alle Anwendungen; wird eine \
                    namentlich genannt, nur deren Zustand. Nicht fuer Lichter oder Geraete \
                    im Haus - dafuer sind die Home-Assistant-Werkzeuge da.""")
    public String getApplicationsStatus(
            @McpToolParam(required = false,
                    description = "Optional: Name der Anwendung, z. B. \"Monetheus\". "
                            + "Weglassen, wenn nach allen Anwendungen gefragt ist.")
            String application) {

        MonitoringStatus status = client.status();

        if (application == null || application.isBlank()) {
            return StatusReport.overview(status);
        }

        return switch (ApplicationResolver.resolve(status.applications(), application)) {
            case ApplicationLookup.Found(MonitoredApplication found) -> StatusReport.single(found);
            case ApplicationLookup.NotFound(List<String> available) -> unknown(application, available);
            case ApplicationLookup.Ambiguous(List<String> candidates) -> ambiguous(application, candidates);
        };
    }

    @McpTool(name = "set_application_power",
            description = """
                    Startet oder stoppt eine auf dem Server laufende Anwendung bzw. einen Dienst, \
                    indem deren Docker-Container gestartet oder gestoppt wird. Fuer Anweisungen \
                    wie "stoppe Monetheus" oder "starte den Filebrowser wieder". Eine so \
                    gestoppte Anwendung gilt als absichtlich ausgeschaltet und nicht als Ausfall. \
                    Nicht fuer Lichter oder Geraete im Haus - dafuer sind die \
                    Home-Assistant-Werkzeuge da.""")
    public String setApplicationPower(
            @McpToolParam(required = true,
                    description = "Name der Anwendung, so wie sie im Monitoring Tool heisst, "
                            + "z. B. \"Monetheus\".")
            String application,
            @McpToolParam(required = true, description = "\"on\" zum Starten, \"off\" zum Stoppen.")
            String power) {

        Optional<Power> parsed = Power.parse(power);
        if (parsed.isEmpty()) {
            return "'%s' ist kein gueltiger Schaltzustand - erwartet wird \"on\" oder \"off\"."
                    .formatted(power);
        }

        // Der Zustand wird ohnehin gebraucht, um den Namen aufzuloesen - und liefert gleich mit, ob
        // der Eingriff ueberhaupt etwas aendern wuerde.
        MonitoringStatus status = client.status();

        return switch (ApplicationResolver.resolve(status.applications(), application)) {
            case ApplicationLookup.Found(MonitoredApplication found) -> switchPower(found, parsed.get());
            case ApplicationLookup.NotFound(List<String> available) -> unknown(application, available);
            case ApplicationLookup.Ambiguous(List<String> candidates) -> ambiguous(application, candidates);
        };
    }

    private String switchPower(MonitoredApplication application, Power power) {
        // Die Kennung adressiert den Eingriff - ohne sie liesse sich nur der Aufruf raten. Das
        // Monitoring Tool liefert sie immer mit; die Pruefung faengt ab, dass eine unerwartete
        // Antwort hier als NullPointerException endet statt als lesbare Auskunft.
        if (application.id() == null) {
            return ("Das Monitoring Tool hat zur Anwendung '%s' keine Kennung geliefert - "
                    + "sie laesst sich darueber nicht schalten.").formatted(application.name());
        }
        if (application.containerName() == null || application.containerName().isBlank()) {
            return ("Der Anwendung '%s' ist im Monitoring Tool kein Docker-Container zugeordnet - "
                    + "sie laesst sich darueber nicht schalten.").formatted(application.name());
        }

        // Schon im gewuenschten Zustand: Das Monitoring Tool wuerde den Aufruf anstandslos ausfuehren,
        // aber die ehrlichere Auskunft ist, dass nichts zu tun war. Ist der Containerzustand unbekannt
        // (Docker nicht erreichbar), wird nicht geraten, sondern geschaltet.
        boolean wanted = power == Power.ON;
        if (application.containerKnown() && application.containerRunning() == wanted) {
            return "Die Anwendung '%s' %s bereits.".formatted(
                    application.name(), wanted ? "laeuft" : "ist bereits gestoppt");
        }

        try {
            MonitoredApplication updated = client.setPower(application.id(), power);
            String participle = wanted ? "gestartet" : "gestoppt";
            log.info("Anwendung {} (#{}) {}", application.name(), application.id(), participle);
            return StatusReport.afterSwitching(updated, participle);
        } catch (MonitoringRejectedException ex) {
            // Das Monitoring Tool hat einen Grund genannt - geschuetzter Container, Steuerung
            // abgeschaltet, Docker nicht erreichbar. Den kann die KI weitergeben.
            log.info("Schalten von '{}' abgelehnt: {}", application.name(), ex.getMessage());
            return "Die Anwendung '%s' konnte nicht %s werden: %s".formatted(
                    application.name(), wanted ? "gestartet" : "gestoppt", ex.getMessage());
        }
    }

    /**
     * Zwei verschiedene Ursachen, zwei verschiedene Antworten: Die KI soll einen falschen Namen noch
     * einmal versuchen koennen, bei einer leeren Liste waere jeder weitere Versuch vergeblich.
     */
    private static String unknown(String name, List<String> available) {
        return available.isEmpty()
                ? "Im Monitoring Tool ist keine Anwendung hinterlegt."
                : "Es gibt keine Anwendung namens '%s'. Hinterlegt sind: %s."
                        .formatted(name, String.join(", ", available));
    }

    private static String ambiguous(String name, List<String> candidates) {
        return "'%s' passt auf mehrere Anwendungen: %s. Bitte eine davon genau benennen."
                .formatted(name, String.join(", ", candidates));
    }
}
