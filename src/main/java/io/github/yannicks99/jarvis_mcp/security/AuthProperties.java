package io.github.yannicks99.jarvis_mcp.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Geteiltes Geheimnis zwischen JARVIS-AIService und JARVIS-MCP - dasselbe Muster wie zwischen
 * JARVIS-Pilot und AIService sowie zwischen GitHub-Webhook und DeploymentService.
 *
 * @param token Erwarteter Wert des {@code Authorization: Bearer …}-Headers. Leer bedeutet: Die
 *              Pruefung ist abgeschaltet - der Dienst laeuft dann ungeschuetzt und meldet das beim
 *              Start deutlich. Bewusst so, damit ein Konfigurationsfehler den Dienst nicht still
 *              unbrauchbar macht; er liegt ohnehin nur im internen Docker-/Tailscale-Netz.
 */
@ConfigurationProperties(prefix = "jarvis-mcp.auth")
public record AuthProperties(@DefaultValue("") String token) {

    public boolean enabled() {
        return !token.isBlank();
    }
}
