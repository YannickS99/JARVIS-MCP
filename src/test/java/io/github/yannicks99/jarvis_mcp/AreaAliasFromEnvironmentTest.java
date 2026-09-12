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
 * Sichert den Weg ab, auf dem im Betrieb ein zusaetzlicher Bereichsname gesetzt wird.
 *
 * <p>Bereiche kommen aus Home Assistant; ein Eintrag ist nur noetig, wenn ein Bereich anders
 * angesprochen werden soll, als er dort heisst. Dafuer eine eigene Konfigurationsdatei samt
 * Volume-Mount vorzuhalten waere zu viel Umstand fuer einen Ausnahmefall - und der fehlende Mount
 * hat beim ersten Betrieb auf JARVIS bereits einen Abend gekostet. Stattdessen eine einzelne
 * Umgebungsvariable in der docker-compose.yml, hier als {@code spring.application.json} geprueft.
 */
@SpringBootTest(properties = {
        "spring.application.json={\"jarvis-mcp\":{\"home-assistant\":{\"areas\":"
                + "[{\"id\":\"arbeitszimmer\",\"names\":[\"Büro\",\"Studierzimmer\"]}]}}}",
        "management.server.port=0", "server.port=0"})
class AreaAliasFromEnvironmentTest {

    @Autowired
    HomeAssistantProperties properties;

    @Test
    @DisplayName("zusaetzliche Bereichsnamen lassen sich ueber SPRING_APPLICATION_JSON setzen")
    void bindsAliasesFromJson() {
        assertThat(properties.areas())
                .containsExactly(new AreaMapping("arbeitszimmer", List.of("Büro", "Studierzimmer")));
    }
}
