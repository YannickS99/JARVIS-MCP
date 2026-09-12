package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Konfiguration des Home-Assistant-Tool-Moduls. Jede Integration bringt ihre eigene
 * Properties-Klasse mit, damit ein neues Modul nichts an den bestehenden aendern muss.
 *
 * @param enabled           Schaltet das gesamte Modul ab. Abgeschaltet taucht es nicht in
 *                          {@code tools/list} auf - die KI sieht dann gar nicht erst Werkzeuge,
 *                          die ohnehin nicht funktionieren wuerden.
 * @param baseUrl           Wurzel der Home-Assistant-Instanz, z. B.
 *                          {@code http://host.docker.internal:8123}.
 * @param token            Long-Lived Access Token aus dem Home-Assistant-Profil.
 * @param connectTimeout    Wartezeit auf den Verbindungsaufbau.
 * @param readTimeout       Wartezeit auf die Antwort. Bewusst knapp unter dem MCP-Request-Timeout,
 *                          damit ein haengendes Home Assistant als Werkzeugfehler zurueckkommt und
 *                          nicht die ganze MCP-Anfrage auflaufen laesst.
 * @param cacheTtl          Wie lange der Entitaeten-Index als frisch gilt. Im Hintergrund wird in
 *                          genau diesem Takt nachgeladen, sodass ein Werkzeugaufruf im Normalfall
 *                          nur einen Hash-Zugriff kostet statt eines HTTP-Aufrufs.
 * @param minRefreshInterval Untergrenze zwischen zwei erzwungenen Aktualisierungen. Verhindert,
 *                          dass eine Folge von Fehlgriffen (Name existiert schlicht nicht) Home
 *                          Assistant mit {@code /api/states}-Abrufen ueberzieht.
 * @param areas             Statische Zuordnung von Bereichsnamen und -aliassen auf
 *                          Home-Assistant-{@code area_id}s (Anforderungskatalog 4a). Bewusst von
 *                          Hand gepflegt statt ueber die Area Registry per WebSocket - und bewusst
 *                          eine Liste statt einer Map, siehe {@link AreaMapping}.
 */
@ConfigurationProperties(prefix = "jarvis-mcp.home-assistant")
public record HomeAssistantProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("") String baseUrl,
        @DefaultValue("") String token,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout,
        @DefaultValue("60s") Duration cacheTtl,
        @DefaultValue("5s") Duration minRefreshInterval,
        @DefaultValue List<AreaMapping> areas) {

    /** Ohne Adresse und Token kann kein einziger Aufruf gelingen. */
    public boolean configured() {
        return !baseUrl.isBlank() && !token.isBlank();
    }
}
