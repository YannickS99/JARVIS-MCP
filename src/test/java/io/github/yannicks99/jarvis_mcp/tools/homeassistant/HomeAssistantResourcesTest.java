package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yannicks99.jarvis_mcp.common.EntityCatalog;
import io.github.yannicks99.jarvis_mcp.common.RefreshingCache;
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

class HomeAssistantResourcesTest {

    private StubHomeAssistant homeAssistant;
    private HomeAssistantClient client;

    @BeforeEach
    void startHomeAssistant() throws IOException {
        homeAssistant = new StubHomeAssistant(StubHomeAssistant.states(
                StubHomeAssistant.entity("light.stehlampe", "Stehlampe"),
                StubHomeAssistant.entity("script.gute_nacht", "Gute Nacht"),
                StubHomeAssistant.entity("scene.kino", "Kinoabend")));
        homeAssistant.areas("wohnzimmer", "Wohnzimmer", "arbeitszimmer", "Arbeitszimmer");

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newHttpClient());
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = new HomeAssistantClient(
                RestClient.builder().requestFactory(factory).baseUrl(homeAssistant.baseUrl()).build(),
                JsonMapper.builder().build());
    }

    @AfterEach
    void stopHomeAssistant() {
        homeAssistant.close();
    }

    private HomeAssistantResources resources(List<AreaMapping> configured) {
        HomeAssistantEntityIndex index = new HomeAssistantEntityIndex(client,
                List.of("light.", "script.", "scene."), Duration.ofMinutes(5), Duration.ofSeconds(5));
        RefreshingCache<AreaResolver.AreaIndex> areas = new RefreshingCache<>("Bereiche",
                () -> AreaResolver.AreaIndex.of(client.areas()), Duration.ofMinutes(5), Duration.ofSeconds(5));
        RefreshingCache<IdempotentEntities> idempotent = new RefreshingCache<>("Label",
                () -> new IdempotentEntities(client.labeledEntities(IdempotentEntities.LABEL)),
                Duration.ofMinutes(5), Duration.ofSeconds(5));
        return new HomeAssistantResources(index, new AreaResolver(areas, configured), idempotent,
                JsonMapper.builder().build());
    }

    @Test
    @DisplayName("Bereiche, Lichter und Routinen stehen mit Typ und Kennung im Katalog")
    void listsAllNames() {
        EntityCatalog catalog = resources(List.of()).catalog();

        assertThat(catalog.entities()).containsExactly(
                new EntityCatalog.Entry("area", "Wohnzimmer", "wohnzimmer", List.of()),
                new EntityCatalog.Entry("area", "Arbeitszimmer", "arbeitszimmer", List.of()),
                // Entitaeten in der festen Reihenfolge des Index, also nach entity_id.
                new EntityCatalog.Entry("light", "Stehlampe", "light.stehlampe", List.of()),
                new EntityCatalog.Entry("routine", "Kinoabend", "scene.kino", List.of()),
                new EntityCatalog.Entry("routine", "Gute Nacht", "script.gute_nacht", List.of()));
    }

    @Test
    @DisplayName("konfigurierte Zusatznamen haengen am Bereich, ohne den Namen selbst zu wiederholen")
    void mergesConfiguredAliases() {
        EntityCatalog catalog = resources(List.of(
                new AreaMapping("arbeitszimmer", List.of("Arbeitszimmer", "Büro")))).catalog();

        assertThat(catalog.entities()).contains(
                new EntityCatalog.Entry("area", "Arbeitszimmer", "arbeitszimmer", List.of("Büro")));
    }

    @Test
    @DisplayName("ein nur konfigurierter Bereich ist ebenso ansprechbar und steht deshalb im Katalog")
    void includesConfigOnlyAreas() {
        EntityCatalog catalog = resources(List.of(
                new AreaMapping("garten", List.of("Garten", "Terrasse")))).catalog();

        assertThat(catalog.entities()).contains(
                new EntityCatalog.Entry("area", "Garten", "garten", List.of("Terrasse")));
    }

    @Test
    @DisplayName("ohne erreichbares Home Assistant scheitert der Abruf, statt einen leeren Katalog zu melden")
    void failsWithoutHomeAssistant() {
        homeAssistant.templateStatus(401);

        assertThatThrownBy(() -> resources(List.of()).catalog()).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("die Resource liefert den Katalog als JSON")
    void rendersJson() {
        assertThat(resources(List.of()).areasAndEntities())
                .startsWith("{\"entities\":[")
                .contains("{\"type\":\"light\",\"name\":\"Stehlampe\",\"ref\":\"light.stehlampe\",\"aliases\":[]}");
    }

    @Test
    @DisplayName("eine Routine mit dem Label jarvis-idempotent traegt die Zusage, alle anderen nicht")
    void marksLabeledEntitiesAsIdempotent() {
        homeAssistant.labeled("script.gute_nacht");

        EntityCatalog catalog = resources(List.of()).catalog();

        assertThat(catalog.entities()).contains(
                new EntityCatalog.Entry("routine", "Gute Nacht", "script.gute_nacht", List.of(), true),
                new EntityCatalog.Entry("routine", "Kinoabend", "scene.kino", List.of(), false));
    }

    @Test
    @DisplayName("die Zusage steht nur im JSON, wenn sie gegeben ist")
    void rendersIdempotentOnlyWhenSet() {
        homeAssistant.labeled("script.gute_nacht");

        assertThat(resources(List.of()).areasAndEntities())
                .contains("{\"type\":\"routine\",\"name\":\"Gute Nacht\",\"ref\":\"script.gute_nacht\","
                        + "\"aliases\":[],\"idempotent\":true}")
                .contains("{\"type\":\"routine\",\"name\":\"Kinoabend\",\"ref\":\"scene.kino\",\"aliases\":[]}");
    }

    @Test
    @DisplayName("ist das Label nicht lesbar, kommt der Katalog trotzdem - nur ohne Zusagen")
    void catalogSurvivesUnreadableLabel() {
        homeAssistant.labeled("script.gute_nacht");
        homeAssistant.labelsStatus(400);

        EntityCatalog catalog = resources(List.of()).catalog();

        assertThat(catalog.entities()).isNotEmpty().noneMatch(EntityCatalog.Entry::idempotent);
    }
}
