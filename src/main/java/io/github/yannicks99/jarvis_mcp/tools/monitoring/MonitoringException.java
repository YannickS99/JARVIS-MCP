package io.github.yannicks99.jarvis_mcp.tools.monitoring;

/**
 * Das Monitoring Tool selbst spielt nicht mit - nicht erreichbar, falsches Token, unerwartete Antwort.
 *
 * <p>Abgegrenzt von den Faellen, die als Text zurueckgehen (unbekannter Anwendungsname, geschuetzter
 * Container): Die sind fuer die KI verwertbar, dieser hier ist es nicht.
 */
public class MonitoringException extends RuntimeException {

    public MonitoringException(String message) {
        super(message);
    }

    public MonitoringException(String message, Throwable cause) {
        super(message, cause);
    }
}
