package io.github.yannicks99.jarvis_mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * JARVIS-MCP - die Integrationsschicht zwischen dem JARVIS-AIService und den uebrigen Systemen im
 * Haus. Die Anwendung haelt selbst keine Fachlogik vor, sondern macht bestehende Schnittstellen
 * ueber das MCP-Protokoll als Werkzeuge fuer die KI verfuegbar.
 *
 * <p>Jede Integration liegt in einem eigenen Paket unterhalb von {@code tools} und bringt ihre
 * Werkzeuge, ihren Client und ihre Konfiguration selbst mit. Eine neue Integration wird ergaenzt,
 * indem ein weiteres solches Paket dazukommt - hier ist dafuer nichts zu aendern.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class JarvisMcpApplication {

	public static void main(String[] args) {
		SpringApplication.run(JarvisMcpApplication.class, args);
	}

}
