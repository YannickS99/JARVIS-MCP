package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class HomeAssistantEntityIndexTest {

    private static final List<String> DOMAINS = List.of("light.", "script.", "scene.");
    private static final List<String> LIGHTS = List.of("light.");
    private static final List<String> ROUTINES = List.of("script.", "scene.");

    private StubHomeAssistant homeAssistant;
    private HomeAssistantClient client;

    @BeforeEach
    void startHomeAssistant() throws IOException {
        homeAssistant = new StubHomeAssistant(StubHomeAssistant.states(
                StubHomeAssistant.entity("light.stehlampe_wohnzimmer", "Stehlampe Wohnzimmer"),
                StubHomeAssistant.entity("light.buero_decke", "Bürolicht"),
                StubHomeAssistant.entity("script.gute_nacht", "Gute Nacht"),
                StubHomeAssistant.entity("scene.kino", "Kinoabend"),
                // Nicht indiziert - die Domain interessiert kein Werkzeug.
                StubHomeAssistant.entity("sensor.temperatur", "Temperatur")));

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newHttpClient());
        factory.setReadTimeout(Duration.ofSeconds(5));
        RestClient restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(homeAssistant.baseUrl())
                .build();
        client = new HomeAssistantClient(restClient, JsonMapper.builder().build());
    }

    @AfterEach
    void stopHomeAssistant() {
        homeAssistant.close();
    }

    private HomeAssistantEntityIndex index(Duration ttl, Duration minRefresh) {
        return new HomeAssistantEntityIndex(client, DOMAINS, ttl, minRefresh);
    }

    @Test
    @DisplayName("nur die gefragten Domains landen im Index")
    void filtersDomains() {
        HomeAssistantEntityIndex index = index(Duration.ofMinutes(5), Duration.ofSeconds(5));
        assertThat(index.fresh().all()).extracting(HomeAssistantEntity::entityId)
                .containsExactlyInAnyOrder("light.stehlampe_wohnzimmer", "light.buero_decke",
                        "script.gute_nacht", "scene.kino");
    }

    @Test
    @DisplayName("Treffer unabhaengig von Gross-/Kleinschreibung und Umlautschreibweise")
    void findsRegardlessOfSpelling() {
        HomeAssistantEntityIndex index = index(Duration.ofMinutes(5), Duration.ofSeconds(5));

        assertThat(index.find("bürolicht", LIGHTS))
                .isEqualTo(new EntityLookup.Found(new HomeAssistantEntity(
                        "light.buero_decke", "Bürolicht", "buerolicht")));
        assertThat(index.find("BUEROLICHT", LIGHTS)).isInstanceOf(EntityLookup.Found.class);
        assertThat(index.find("burolicht", LIGHTS)).isInstanceOf(EntityLookup.Found.class);
    }

    @Test
    @DisplayName("ein verkuerzter Name trifft, solange er eindeutig bleibt")
    void findsByPartialName() {
        HomeAssistantEntityIndex index = index(Duration.ofMinutes(5), Duration.ofSeconds(5));

        assertThat(index.find("Stehlampe", LIGHTS))
                .isInstanceOf(EntityLookup.Found.class)
                .extracting(lookup -> ((EntityLookup.Found) lookup).entity().entityId())
                .isEqualTo("light.stehlampe_wohnzimmer");
    }

    @Test
    @DisplayName("mehrdeutige Namen werden nicht geraten, sondern zurueckgemeldet")
    void reportsAmbiguity() {
        homeAssistant.statesBody(StubHomeAssistant.states(
                StubHomeAssistant.entity("light.a", "Lampe links"),
                StubHomeAssistant.entity("light.b", "Lampe rechts")));
        HomeAssistantEntityIndex index = index(Duration.ofMinutes(5), Duration.ofSeconds(5));

        assertThat(index.find("Lampe", LIGHTS))
                .isInstanceOf(EntityLookup.Ambiguous.class)
                .extracting(lookup -> ((EntityLookup.Ambiguous) lookup).candidates())
                .isEqualTo(List.of("Lampe links", "Lampe rechts"));
    }

    @Test
    @DisplayName("Fehlgriff nennt die verfuegbaren Namen")
    void listsAvailableOnMiss() {
        HomeAssistantEntityIndex index = index(Duration.ofMinutes(5), Duration.ofSeconds(5));

        assertThat(index.find("Gartenlicht", LIGHTS))
                .isInstanceOf(EntityLookup.NotFound.class)
                .extracting(lookup -> ((EntityLookup.NotFound) lookup).available())
                .isEqualTo(List.of("Bürolicht", "Stehlampe Wohnzimmer"));
    }

    @Test
    @DisplayName("Skript hat Vorrang vor einem gleichnamigen Szenario")
    void scriptBeatsScene() {
        homeAssistant.statesBody(StubHomeAssistant.states(
                StubHomeAssistant.entity("scene.gute_nacht_szene", "Gute Nacht Szene"),
                StubHomeAssistant.entity("script.gute_nacht_skript", "Gute Nacht Skript")));
        HomeAssistantEntityIndex index = index(Duration.ofMinutes(5), Duration.ofSeconds(5));

        assertThat(index.find("Gute Nacht", ROUTINES))
                .extracting(lookup -> ((EntityLookup.Found) lookup).entity().entityId())
                .isEqualTo("script.gute_nacht_skript");
    }

    @Test
    @DisplayName("wiederholte Treffer kosten keinen weiteren Abruf bei Home Assistant")
    void servesFromCache() {
        HomeAssistantEntityIndex index = index(Duration.ofMinutes(5), Duration.ofSeconds(5));

        for (int i = 0; i < 50; i++) {
            assertThat(index.find("Stehlampe Wohnzimmer", LIGHTS)).isInstanceOf(EntityLookup.Found.class);
        }
        assertThat(homeAssistant.stateRequests()).isEqualTo(1);
    }

    @Test
    @DisplayName("ein Fehlgriff laedt nach, falls der Index nicht gerade eben erst entstand")
    void refreshesOnMiss() {
        HomeAssistantEntityIndex index = index(Duration.ofMinutes(5), Duration.ZERO);

        // Aufbau plus ein Nachladen: Der Name koennte eine frisch angelegte Entitaet sein.
        assertThat(index.find("Gartenlicht", LIGHTS)).isInstanceOf(EntityLookup.NotFound.class);
        assertThat(homeAssistant.stateRequests()).isEqualTo(2);
    }

    @Test
    @DisplayName("wiederholte Fehlgriffe ueberziehen Home Assistant nicht mit Abrufen")
    void throttlesRepeatedMisses() {
        HomeAssistantEntityIndex index = index(Duration.ofMinutes(5), Duration.ofSeconds(30));
        index.fresh();
        assertThat(homeAssistant.stateRequests()).isEqualTo(1);

        for (int i = 0; i < 20; i++) {
            assertThat(index.find("Gartenlicht", LIGHTS)).isInstanceOf(EntityLookup.NotFound.class);
        }
        assertThat(homeAssistant.stateRequests()).isEqualTo(1);
    }

    @Test
    @DisplayName("neu angelegte Entitaeten werden nach dem Nachladen gefunden")
    void picksUpNewEntities() {
        HomeAssistantEntityIndex index = index(Duration.ofMinutes(5), Duration.ZERO);
        assertThat(index.find("Gartenlicht", LIGHTS)).isInstanceOf(EntityLookup.NotFound.class);

        homeAssistant.statesBody(StubHomeAssistant.states(
                StubHomeAssistant.entity("light.garten", "Gartenlicht")));

        assertThat(index.find("Gartenlicht", LIGHTS)).isInstanceOf(EntityLookup.Found.class);
    }

    @Test
    @DisplayName("ein Ausfall von Home Assistant laesst den bestehenden Index stehen")
    void keepsStaleIndexWhenHomeAssistantFails() {
        HomeAssistantEntityIndex index = index(Duration.ofMinutes(5), Duration.ofSeconds(5));
        assertThat(index.find("Stehlampe Wohnzimmer", LIGHTS)).isInstanceOf(EntityLookup.Found.class);

        homeAssistant.statesStatus(503);
        index.refreshQuietly();

        assertThat(index.find("Stehlampe Wohnzimmer", LIGHTS)).isInstanceOf(EntityLookup.Found.class);
    }

    @Test
    @DisplayName("ohne jeden Index schlaegt ein Ausfall durch")
    void failsWithoutAnyIndex() {
        homeAssistant.statesStatus(503);
        HomeAssistantEntityIndex index = index(Duration.ofMinutes(5), Duration.ofSeconds(5));

        assertThatThrownBy(() -> index.find("Stehlampe", LIGHTS))
                .isInstanceOf(HomeAssistantException.class)
                .hasMessageContaining("503");
    }
}
