package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

/**
 * Ein Bereich aus Home Assistants Area Registry.
 *
 * @param areaId technische Kennung, z. B. {@code wohnzimmer} - sie bleibt stabil, auch wenn der
 *               Bereich umbenannt wird
 * @param name   Anzeigename, so wie er in Home Assistant steht
 */
public record HomeAssistantArea(String areaId, String name) {
}
