package io.github.yannicks99.jarvis_mcp.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Haengt die Token-Pruefung vor den MCP-Endpunkt.
 *
 * <p>Registriert wird ausschliesslich der MCP-Pfad, nicht die gesamte Anwendung: Der
 * Health-Endpunkt liegt auf dem eigenen Management-Port und muss fuer das Monitoring Tool ohne
 * Token erreichbar bleiben.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    @Bean
    FilterRegistrationBean<BearerTokenFilter> bearerTokenFilter(AuthProperties properties,
            @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String mcpEndpoint) {

        FilterRegistrationBean<BearerTokenFilter> registration = new FilterRegistrationBean<>();
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns(mcpEndpoint, mcpEndpoint + "/*");

        if (!properties.enabled()) {
            log.warn("jarvis-mcp.auth.token ist nicht gesetzt - der MCP-Endpunkt {} ist ungeschuetzt "
                    + "erreichbar. Fuer den Betrieb auf JARVIS bitte JARVIS_MCP_AUTH_TOKEN setzen.", mcpEndpoint);
            registration.setEnabled(false);
            registration.setFilter(new BearerTokenFilter(""));
            return registration;
        }

        log.info("MCP-Endpunkt {} ist durch ein Bearer-Token geschuetzt.", mcpEndpoint);
        registration.setFilter(new BearerTokenFilter(properties.token()));
        return registration;
    }
}
