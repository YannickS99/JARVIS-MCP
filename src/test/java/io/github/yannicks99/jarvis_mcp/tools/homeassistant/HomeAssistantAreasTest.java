package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prueft den Weg, auf dem die Bereiche tatsaechlich hereinkommen: ueber die Template-Engine von
 * Home Assistant. Bewusst ueber HTTP gegen den Stub statt gegen einen gemockten Client - genau
 * dieses Zusammenspiel aus Template, Antwortformat und Zerlegung soll abgesichert sein.
 */
class HomeAssistantAreasTest {

    private StubHomeAssistant homeAssistant;
    private HomeAssistantClient client;

    @BeforeEach
    void start() throws IOException {
        homeAssistant = new StubHomeAssistant(StubHomeAssistant.states());
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newHttpClient());
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = new HomeAssistantClient(RestClient.builder()
                .requestFactory(factory)
                .baseUrl(homeAssistant.baseUrl())
                .build(), JsonMapper.builder().build());
    }

    @AfterEach
    void stop() {
        homeAssistant.close();
    }

    @Test
    @DisplayName("Bereiche werden als id/name-Paare gelesen")
    void readsAreas() {
        homeAssistant.areas("wohnzimmer", "Wohnzimmer", "arbeitszimmer", "Arbeitszimmer");

        assertThat(client.areas()).containsExactly(
                new HomeAssistantArea("wohnzimmer", "Wohnzimmer"),
                new HomeAssistantArea("arbeitszimmer", "Arbeitszimmer"));
    }

    @Test
    @DisplayName("Umlaute im Bereichsnamen kommen unbeschadet an")
    void keepsUmlauts() {
        homeAssistant.areas("kueche", "Küche");

        assertThat(client.areas()).singleElement()
                .extracting(HomeAssistantArea::name).isEqualTo("Küche");
    }

    @Test
    @DisplayName("keine Bereiche eingerichtet ist kein Fehler")
    void emptyIsFine() {
        assertThat(client.areas()).isEmpty();
    }

    @Test
    @DisplayName("lehnt Home Assistant das Template ab, sagt die Meldung warum")
    void reportsRejection() {
        homeAssistant.templateStatus(401);

        assertThatThrownBy(() -> client.areas())
                .isInstanceOf(HomeAssistantException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("Token");
    }
}
