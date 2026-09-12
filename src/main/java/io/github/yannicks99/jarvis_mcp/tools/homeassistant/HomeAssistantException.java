package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

/** Home Assistant war nicht erreichbar oder hat einen Aufruf abgelehnt. */
public class HomeAssistantException extends RuntimeException {

    public HomeAssistantException(String message) {
        super(message);
    }

    public HomeAssistantException(String message, Throwable cause) {
        super(message, cause);
    }
}
