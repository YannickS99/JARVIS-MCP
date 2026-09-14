package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

/**
 * Der aktuelle Zustand eines Lichts, so wie ihn die Status-Werkzeuge brauchen.
 *
 * <p>Bewusst getrennt von {@link HomeAssistantEntity}: Jenes ist ein zwischengespeichertes Abbild
 * fuer die Namensaufloesung und darf ruhig eine Minute alt sein, dieses wird bei jeder Frage
 * frisch geholt. Ein Zustand aus dem Zwischenspeicher waere schlicht falsch - Lichter werden auch
 * am Schalter bedient, nicht nur ueber JARVIS.
 *
 * @param entityId  technische Kennung, z. B. {@code light.stehlampe_wohnzimmer}
 * @param name      Anzeigename aus Home Assistant
 * @param state     Rohzustand, wie Home Assistant ihn meldet: {@code on}, {@code off},
 *                  {@code unavailable} oder {@code unknown}
 * @param areaId    {@code area_id} des Bereichs, in dem das Licht haengt - leer, wenn es keinem
 *                  Bereich zugeordnet ist
 * @param areaName  Anzeigename dieses Bereichs, ebenfalls leer ohne Zuordnung
 */
public record HomeAssistantLightStatus(String entityId, String name, String state,
        String areaId, String areaName) {

    public boolean on() {
        return "on".equals(state);
    }

    public boolean off() {
        return "off".equals(state);
    }

    /**
     * Weder an noch aus. Home Assistant meldet {@code unavailable} oder {@code unknown}, wenn das
     * Geraet nicht antwortet - so ein Licht darf in einer Statusantwort weder als "an" noch als
     * "aus" auftauchen, sonst behauptet die KI etwas, das niemand geprueft hat.
     */
    public boolean unreachable() {
        return !on() && !off();
    }

    /** Ob das Licht einem Bereich zugeordnet ist. */
    public boolean hasArea() {
        return !areaId.isBlank();
    }
}
