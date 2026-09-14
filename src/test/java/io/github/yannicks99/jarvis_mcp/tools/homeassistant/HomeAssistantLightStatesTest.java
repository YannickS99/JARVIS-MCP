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
 * Der Weg, auf dem die Lichtzustaende hereinkommen: ueber dieselbe Template-Engine wie die
 * Bereiche. Bewusst ueber HTTP gegen den Stub - gerade das Zusammenspiel aus Antwortformat und
 * Zerlegung soll abgesichert sein.
 */
class HomeAssistantLightStatesTest {

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
    @DisplayName("Zustand und Bereich kommen je Licht zusammen an")
    void readsStateAndArea() {
        homeAssistant.lights(
                StubHomeAssistant.light("light.stehlampe", "Stehlampe", "on", "wohnzimmer", "Wohnzimmer"),
                StubHomeAssistant.light("light.herd", "Herdlicht", "off", "kueche", "Küche"));

        assertThat(client.lightStates()).containsExactly(
                new HomeAssistantLightStatus("light.stehlampe", "Stehlampe", "on",
                        "wohnzimmer", "Wohnzimmer"),
                new HomeAssistantLightStatus("light.herd", "Herdlicht", "off", "kueche", "Küche"));
    }

    @Test
    @DisplayName("ein Licht ohne Bereich kommt mit leerer Zuordnung an, nicht mit null")
    void lightWithoutArea() {
        homeAssistant.lights(StubHomeAssistant.light("light.kette", "Lichterkette", "on", "", ""));

        assertThat(client.lightStates()).singleElement()
                .satisfies(light -> {
                    assertThat(light.hasArea()).isFalse();
                    assertThat(light.areaName()).isEmpty();
                });
    }

    @Test
    @DisplayName("kein Licht eingerichtet ist kein Fehler")
    void emptyIsFine() {
        assertThat(client.lightStates()).isEmpty();
    }

    @Test
    @DisplayName("Zustaende werden nie zwischengespeichert - jede Frage fragt Home Assistant")
    void alwaysAsksHomeAssistant() {
        homeAssistant.lights(StubHomeAssistant.light("light.a", "Lampe", "off", "flur", "Flur"));

        client.lightStates();
        homeAssistant.lights(StubHomeAssistant.light("light.a", "Lampe", "on", "flur", "Flur"));

        assertThat(client.lightStates()).singleElement()
                .extracting(HomeAssistantLightStatus::on).isEqualTo(true);
    }

    @Test
    @DisplayName("lehnt Home Assistant das Template ab, sagt die Meldung warum")
    void reportsRejection() {
        homeAssistant.templateStatus(401);

        assertThatThrownBy(() -> client.lightStates())
                .isInstanceOf(HomeAssistantException.class)
                .hasMessageContaining("401");
    }
}
