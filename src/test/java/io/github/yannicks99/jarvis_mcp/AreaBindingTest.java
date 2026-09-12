package io.github.yannicks99.jarvis_mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yannicks99.jarvis_mcp.tools.homeassistant.AreaMapping;
import io.github.yannicks99.jarvis_mcp.tools.homeassistant.HomeAssistantProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Haelt fest, dass Umlaute in Bereichsnamen die Konfiguration heil verlassen.
 *
 * <p>Der Test hat einen konkreten Anlass: Als Map-Schluessel wurde aus {@code Büro} still
 * {@code Bro}, weil Spring Schluessel ueber seinen Property-Namensraum bindet und dabei alles
 * ausser Buchstaben und Ziffern verwirft. Die Liste aus {@link AreaMapping} umgeht das - dieser
 * Test sorgt dafuer, dass niemand versehentlich zur Map zurueckkehrt.
 */
@SpringBootTest(properties = {"spring.config.import=classpath:test-areas.yaml",
        "management.server.port=0", "server.port=0"})
class AreaBindingTest {

    @Autowired
    HomeAssistantProperties properties;

    @Test
    @DisplayName("Bereichsnamen mit Umlaut kommen unveraendert an")
    void umlautsSurviveBinding() {
        assertThat(properties.areas())
                .contains(new AreaMapping("arbeitszimmer", List.of("Arbeitszimmer", "Büro")));
    }
}
