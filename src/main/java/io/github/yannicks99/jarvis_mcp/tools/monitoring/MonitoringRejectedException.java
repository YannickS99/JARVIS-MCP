package io.github.yannicks99.jarvis_mcp.tools.monitoring;

/**
 * Das Monitoring Tool hat den Eingriff abgelehnt und dafuer einen Grund genannt - etwa: Der Container
 * ist vor Eingriffen geschuetzt, der Anwendung ist kein Container zugeordnet, oder die Docker Engine
 * ist nicht erreichbar.
 *
 * <p>Abgegrenzt von {@link MonitoringException}: Das ist ein Grund, den die KI dem Nutzer sagen kann,
 * und keine Stoerung. Die Meldung stammt aus der Fehlerantwort des Monitoring Tools (RFC 9457) und ist
 * dort schon auf Deutsch formuliert - sie wird deshalb durchgereicht statt neu getextet.
 */
public class MonitoringRejectedException extends MonitoringException {

    public MonitoringRejectedException(String reason) {
        super(reason);
    }
}
