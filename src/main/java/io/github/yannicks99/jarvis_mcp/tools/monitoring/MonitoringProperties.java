package io.github.yannicks99.jarvis_mcp.tools.monitoring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Konfiguration des Monitoring-Tool-Moduls. Jede Integration bringt ihre eigene Properties-Klasse mit,
 * damit ein neues Modul nichts an den bestehenden aendern muss.
 *
 * @param enabled        Schaltet das gesamte Modul ab. Abgeschaltet taucht es nicht in
 *                       {@code tools/list} auf - die KI sieht dann gar nicht erst Werkzeuge, die
 *                       ohnehin nicht funktionieren wuerden.
 * @param baseUrl        Wurzel des Monitoring-Backends. Vorgabe ist {@code http://monitoring-backend:8080}:
 *                       Das Backend veroeffentlicht bewusst nur auf {@code 127.0.0.1} des Hosts, ist aus
 *                       einem Bridge-Container also nicht ueber {@code host.docker.internal} erreichbar.
 *                       JARVIS-MCP haengt sich stattdessen an das gemeinsame Netz
 *                       {@code jarvis-net} und spricht den Container direkt an - siehe
 *                       {@code docker-compose.yml}.
 * @param token          Geteiltes Geheimnis fuer {@code /api/integration/v1} des Monitoring Tools
 *                       (dort {@code MONITORING_INTEGRATION_TOKEN}).
 * @param connectTimeout Wartezeit auf den Verbindungsaufbau.
 * @param readTimeout    Wartezeit auf die Antwort. Bewusst knapp unter dem MCP-Request-Timeout, damit
 *                       ein haengendes Monitoring Tool als Werkzeugfehler zurueckkommt und nicht die
 *                       ganze MCP-Anfrage auflaufen laesst. Etwas groesser als bei Home Assistant, denn
 *                       ein Container-Start wartet auf die Docker Engine.
 */
@ConfigurationProperties(prefix = "jarvis-mcp.monitoring")
public record MonitoringProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("http://monitoring-backend:8080") String baseUrl,
        @DefaultValue("") String token,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout) {

    /** Ohne Adresse und Token kann kein einziger Aufruf gelingen. */
    public boolean configured() {
        return !baseUrl.isBlank() && !token.isBlank();
    }
}
