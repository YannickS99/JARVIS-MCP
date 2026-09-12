package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AreaResolverTest {

    private final AreaResolver resolver = new AreaResolver(List.of(
            new AreaMapping("wohnzimmer", List.of("Wohnzimmer")),
            new AreaMapping("arbeitszimmer", List.of("Arbeitszimmer", "Büro")),
            new AreaMapping("flur", List.of())));

    @Test
    @DisplayName("Alias zeigt auf die hinterlegte area_id")
    void alias() {
        assertThat(resolver.resolve("Büro")).contains("arbeitszimmer");
    }

    @Test
    @DisplayName("Schreibweise des Namens ist egal")
    void spellingIsIrrelevant() {
        assertThat(resolver.resolve("BÜRO")).contains("arbeitszimmer");
        assertThat(resolver.resolve("buero")).contains("arbeitszimmer");
        assertThat(resolver.resolve("buro")).contains("arbeitszimmer");
    }

    @Test
    @DisplayName("die area_id selbst muss nicht eigens eingetragen werden")
    void areaIdIsAlwaysAccepted() {
        assertThat(resolver.resolve("arbeitszimmer")).contains("arbeitszimmer");
        assertThat(resolver.resolve("Flur")).contains("flur");
    }

    @Test
    @DisplayName("unbekannter Bereich bleibt leer, die bekannten sind nennbar")
    void unknown() {
        assertThat(resolver.resolve("Dachboden")).isEmpty();
        assertThat(resolver.knownNames()).containsExactly("Wohnzimmer", "Arbeitszimmer", "Büro", "flur");
    }
}
