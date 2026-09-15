package io.github.yannicks99.jarvis_mcp.tools.monitoring;

import java.util.List;
import java.util.StringJoiner;

/**
 * Formt die Antworten des Monitoring Tools in die Saetze, die beim Sprachmodell ankommen.
 *
 * <p>Eigene Klasse und nicht in den Werkzeugmethoden - so laesst sich das, was der Nutzer am Ende zu
 * hoeren bekommt, ohne Monitoring Tool und ohne HTTP pruefen. Dieselbe Entscheidung wie bei
 * {@link io.github.yannicks99.jarvis_mcp.tools.homeassistant.LightStatusReport}.
 *
 * <p>Die Saetze sind knapp und gleichfoermig, weil der Leser ein kleines Modell ist, das daraus einen
 * gesprochenen Satz formt. Und sie sind <em>zaehlend</em> formuliert ("von 9 Anwendungen laufen 7"),
 * denn genau so ist die Frage gestellt: "ist irgendwas ausgefallen?"
 */
public final class StatusReport {

    private StatusReport() {
    }

    /** Der Blick auf alle Anwendungen. */
    public static String overview(MonitoringStatus status) {
        MonitoringStatus.Summary summary = status.summary();
        if (summary.total() == 0) {
            return "Im Monitoring Tool ist keine Anwendung hinterlegt.";
        }
        if (summary.allUp()) {
            return summary.total() == 1
                    ? "Die einzige hinterlegte Anwendung laeuft."
                    : "Alle %d Anwendungen laufen.".formatted(summary.total());
        }

        StringJoiner sentences = new StringJoiner(" ");
        sentences.add("Von %d Anwendungen %s %d.".formatted(
                summary.total(), summary.up() == 1 ? "laeuft" : "laufen", summary.up()));

        // Nach Dringlichkeit: Ein Ausfall ist die Antwort auf die Frage, ein bewusst gestoppter
        // Dienst nur eine Randnotiz.
        append(sentences, "Ausgefallen", status.inState(ApplicationState.DOWN));
        append(sentences, "Gestoert", status.inState(ApplicationState.DEGRADED));
        append(sentences, "Zustand unklar", status.inState(ApplicationState.UNKNOWN));
        append(sentences, "Absichtlich gestoppt", status.inState(ApplicationState.INACTIVE));
        return sentences.toString();
    }

    /** Der Blick auf eine einzelne, namentlich genannte Anwendung. */
    public static String single(MonitoredApplication application) {
        StringBuilder text = new StringBuilder(
                "Die Anwendung '%s' ist %s".formatted(application.name(), application.state().description()));

        if (application.message() != null && !application.message().isBlank()) {
            text.append(" (").append(application.message()).append(')');
        }
        text.append('.');

        // Der Containerzustand ist die Antwort auf "ist das Ding ueberhaupt an?" und deckt sich nicht
        // zwangslaeufig mit dem Health-Status: Ein laufender Container kann trotzdem Fehler melden.
        if (application.containerKnown()) {
            text.append(" Der Container '%s' %s.".formatted(application.containerName(),
                    application.containerRunning() ? "laeuft" : "laeuft nicht"));
        }
        if (!application.monitored()) {
            text.append(" Fuer diese Anwendung ist keine Ueberwachung eingerichtet - "
                    + "der Zustand sagt also nichts darueber, ob sie fehlerfrei arbeitet.");
        }
        return text.toString();
    }

    /** Die Rueckmeldung nach einem Eingriff. */
    public static String afterSwitching(MonitoredApplication application, String participle) {
        return "Die Anwendung '%s' wurde %s. Sie ist jetzt %s.".formatted(
                application.name(), participle, application.state().description());
    }

    private static void append(StringJoiner sentences, String label, List<MonitoredApplication> applications) {
        if (!applications.isEmpty()) {
            sentences.add("%s: %s.".formatted(label,
                    String.join(", ", applications.stream().map(MonitoredApplication::name).toList())));
        }
    }
}
