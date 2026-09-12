package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

/**
 * Eine Home-Assistant-Entitaet, reduziert auf das, was die Werkzeuge brauchen.
 *
 * @param entityId   technische Kennung, z. B. {@code light.stehlampe_wohnzimmer}
 * @param name       Anzeigename aus Home Assistant ({@code attributes.friendly_name})
 * @param normalized vorberechnete kanonische Form des Anzeigenamens - damit die Suche nach einem
 *                   Teilstring ohne erneutes Normalisieren ueber alle Entitaeten laufen kann
 */
public record HomeAssistantEntity(String entityId, String name, String normalized) {
}
