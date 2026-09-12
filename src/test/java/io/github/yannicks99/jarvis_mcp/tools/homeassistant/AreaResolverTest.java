package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yannicks99.jarvis_mcp.common.RefreshingCache;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AreaResolverTest {

    /** Was Home Assistant liefern wuerde - im Test ohne HTTP. */
    private final AtomicReference<List<HomeAssistantArea>> fromHomeAssistant = new AtomicReference<>(List.of(
            new HomeAssistantArea("wohnzimmer", "Wohnzimmer"),
            new HomeAssistantArea("arbeitszimmer", "Arbeitszimmer"),
            new HomeAssistantArea("kueche", "Küche")));

    private final AtomicInteger loads = new AtomicInteger();

    private AreaResolver resolver(List<AreaMapping> configured) {
        return resolver(configured, Duration.ofMinutes(5), Duration.ofSeconds(30));
    }

    private AreaResolver resolver(List<AreaMapping> configured, Duration ttl, Duration minRefresh) {
        RefreshingCache<AreaResolver.AreaIndex> cache = new RefreshingCache<>("Bereiche", () -> {
            loads.incrementAndGet();
            return AreaResolver.AreaIndex.of(fromHomeAssistant.get());
        }, ttl, minRefresh);
        return new AreaResolver(cache, configured);
    }

    @Test
    @DisplayName("Bereiche aus Home Assistant brauchen keine Konfiguration")
    void resolvesFromHomeAssistant() {
        AreaResolver resolver = resolver(List.of());

        assertThat(resolver.resolve("Wohnzimmer")).contains("wohnzimmer");
        assertThat(resolver.resolve("Küche")).contains("kueche");
        assertThat(resolver.resolve("Arbeitszimmer")).contains("arbeitszimmer");
    }

    @Test
    @DisplayName("Schreibweise des Namens ist egal")
    void spellingIsIrrelevant() {
        AreaResolver resolver = resolver(List.of());

        assertThat(resolver.resolve("KÜCHE")).contains("kueche");
        assertThat(resolver.resolve("kueche")).contains("kueche");
        assertThat(resolver.resolve("kuche")).contains("kueche");
    }

    @Test
    @DisplayName("die area_id selbst ist immer ein gueltiger Name")
    void areaIdIsAlwaysAccepted() {
        assertThat(resolver(List.of()).resolve("arbeitszimmer")).contains("arbeitszimmer");
    }

    @Test
    @DisplayName("die Konfiguration ergaenzt zusaetzliche Namen, ersetzt sie aber nicht")
    void configuredAliasesSupplement() {
        AreaResolver resolver = resolver(List.of(new AreaMapping("arbeitszimmer", List.of("Büro"))));

        assertThat(resolver.resolve("Büro")).contains("arbeitszimmer");
        // Der echte Name funktioniert weiterhin, ohne dass er eingetragen waere.
        assertThat(resolver.resolve("Arbeitszimmer")).contains("arbeitszimmer");
    }

    @Test
    @DisplayName("ein neuer Bereich in Home Assistant wird nach dem Nachladen gefunden")
    void picksUpNewAreas() {
        AreaResolver resolver = resolver(List.of(), Duration.ofMinutes(5), Duration.ZERO);
        assertThat(resolver.resolve("Bad")).isEmpty();

        fromHomeAssistant.set(List.of(new HomeAssistantArea("bad", "Bad")));

        assertThat(resolver.resolve("Bad")).contains("bad");
    }

    @Test
    @DisplayName("wiederholte Treffer kosten keinen weiteren Abruf")
    void servesFromCache() {
        AreaResolver resolver = resolver(List.of());

        for (int i = 0; i < 50; i++) {
            assertThat(resolver.resolve("Wohnzimmer")).contains("wohnzimmer");
        }
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("wiederholte Fehlgriffe ueberziehen Home Assistant nicht mit Abrufen")
    void throttlesRepeatedMisses() {
        AreaResolver resolver = resolver(List.of());
        resolver.resolve("Wohnzimmer");
        assertThat(loads.get()).isEqualTo(1);

        for (int i = 0; i < 20; i++) {
            assertThat(resolver.resolve("Dachboden")).isEmpty();
        }
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("bekannte Namen nennen Home Assistant und die Konfiguration")
    void knownNames() {
        AreaResolver resolver = resolver(List.of(new AreaMapping("arbeitszimmer", List.of("Büro"))));

        assertThat(resolver.knownNames())
                .containsExactly("Wohnzimmer", "Arbeitszimmer", "Küche", "Büro");
    }
}
