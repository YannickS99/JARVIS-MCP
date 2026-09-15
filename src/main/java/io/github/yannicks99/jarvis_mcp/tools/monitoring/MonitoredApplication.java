package io.github.yannicks99.jarvis_mcp.tools.monitoring;

import io.github.yannicks99.jarvis_mcp.common.NameNormalizer;

/**
 * Eine Anwendung des Monitoring Tools in dem Zuschnitt, den die Werkzeuge brauchen.
 *
 * <p>Die Antwort des Monitoring Tools enthaelt mehr Felder; was hier nicht steht, wird beim Einlesen
 * verworfen. Umgekehrt darf dort etwas dazukommen, ohne dass hier etwas zu aendern ist.
 *
 * @param id             Kennung, ueber die die Container-Aktionen adressiert werden
 * @param name           Anzeigename - hierueber spricht der Nutzer die Anwendung an
 * @param state          normalisierter Gesamtstatus
 * @param message        Begruendung des Status, z. B. "Verbindung abgelehnt"
 * @param monitored      {@code false}, wenn die Anwendung ohne Health-Check gefuehrt wird; ihr Status
 *                       ist dann keine Aussage ueber ihren Zustand
 * @param containerName  zugeordneter Container, {@code null} wenn keiner eingetragen ist
 * @param containerState Zustand laut Docker ({@code running}, {@code exited}, ...); {@code null}, wenn
 *                       kein Container zugeordnet oder Docker nicht erreichbar ist
 */
public record MonitoredApplication(
        Long id,
        String name,
        ApplicationState state,
        String message,
        boolean monitored,
        String containerName,
        String containerState) {

    /** Die kanonische Form des Namens - der Schluessel, unter dem gesucht wird. */
    public String normalized() {
        return NameNormalizer.canonical(name);
    }

    /** {@code true}, wenn der zugeordnete Container laut Docker gerade laeuft. */
    public boolean containerRunning() {
        return "running".equalsIgnoreCase(containerState);
    }

    /** {@code true}, wenn Docker einen Zustand zu diesem Container gemeldet hat. */
    public boolean containerKnown() {
        return containerState != null && !containerState.isBlank();
    }
}
