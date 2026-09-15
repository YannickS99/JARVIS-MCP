package io.github.yannicks99.jarvis_mcp.tools.monitoring;

import java.util.List;

/**
 * Der Gesamtzustand aller hinterlegten Anwendungen, wie {@code GET /api/integration/v1/status} ihn
 * liefert.
 *
 * @param summary      Zaehlung nach Zustand - kommt fertig vom Monitoring Tool, damit hier nicht
 *                     dieselbe Logik ein zweites Mal steht
 * @param applications alle Anwendungen in der Anzeigereihenfolge des Dashboards
 */
public record MonitoringStatus(Summary summary, List<MonitoredApplication> applications) {

    /**
     * Zaehlung der Anwendungen nach Zustand.
     *
     * @param total    Anzahl aller Anwendungen
     * @param up       laufen ordnungsgemaess
     * @param down     ausgefallen
     * @param degraded gestoert bzw. gerade hochfahrend
     * @param unknown  noch ungeprueft oder nicht deutbar
     * @param inactive ueber das Monitoring Tool gestoppt - bewusst kein Ausfall
     */
    public record Summary(int total, int up, int down, int degraded, int unknown, int inactive) {

        /** {@code true}, wenn jede Anwendung laeuft - dann braucht es keine Aufzaehlung. */
        public boolean allUp() {
            return total > 0 && up == total;
        }
    }

    /** Alle Anwendungen in einem der genannten Zustaende, in Reihenfolge. */
    public List<MonitoredApplication> inState(ApplicationState... states) {
        List<ApplicationState> wanted = List.of(states);
        return applications.stream()
                .filter(application -> wanted.contains(application.state()))
                .toList();
    }
}
