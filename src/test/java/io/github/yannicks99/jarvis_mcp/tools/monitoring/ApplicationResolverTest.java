package io.github.yannicks99.jarvis_mcp.tools.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Die Namensaufloesung ist der Grund, warum der Nutzer den Anwendungsnamen sagen darf und nicht den
 * Containernamen - entsprechend wird hier geprueft, wie viel Schlamperei sie vertraegt und wo sie
 * bewusst nicht mehr raet.
 */
class ApplicationResolverTest {

    private static final List<MonitoredApplication> APPLICATIONS = List.of(
            application(1L, "Monetheus"),
            application(2L, "FilmPickr"),
            application(3L, "Bürolicht-Dashboard"),
            application(4L, "Monetheus Web"));

    @ParameterizedTest
    @DisplayName("Gross-/Kleinschreibung, Bindestriche und Umlautschreibweise sind egal")
    @ValueSource(strings = {"Bürolicht-Dashboard", "buerolicht dashboard", "BUEROLICHT-DASHBOARD",
            "burolicht dashboard"})
    void resolvesRegardlessOfSpelling(String spoken) {
        assertThat(ApplicationResolver.resolve(APPLICATIONS, spoken))
                .isInstanceOf(ApplicationLookup.Found.class)
                .extracting(found -> ((ApplicationLookup.Found) found).application().id())
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("ein exakter Name schlaegt den Teiltreffer eines laengeren Namens")
    void exactNameWinsOverPartialMatch() {
        // "Monetheus" steckt auch in "Monetheus Web" - der genaue Name muss trotzdem eindeutig sein.
        assertThat(ApplicationResolver.resolve(APPLICATIONS, "monetheus"))
                .isInstanceOf(ApplicationLookup.Found.class)
                .extracting(found -> ((ApplicationLookup.Found) found).application().id())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("ein verkuerzter Name trifft, solange er eindeutig bleibt")
    void resolvesShortenedName() {
        assertThat(ApplicationResolver.resolve(APPLICATIONS, "Pickr"))
                .isInstanceOf(ApplicationLookup.Found.class)
                .extracting(found -> ((ApplicationLookup.Found) found).application().name())
                .isEqualTo("FilmPickr");
    }

    @Test
    @DisplayName("bei Mehrdeutigkeit wird nicht geraten, sondern nachgefragt")
    void reportsAmbiguity() {
        assertThat(ApplicationResolver.resolve(APPLICATIONS, "Monet"))
                .isInstanceOf(ApplicationLookup.Ambiguous.class)
                .extracting(result -> ((ApplicationLookup.Ambiguous) result).candidates())
                .isEqualTo(List.of("Monetheus", "Monetheus Web"));
    }

    @Test
    @DisplayName("ein unbekannter Name bringt die vorhandenen Namen mit zurueck")
    void unknownNameListsAlternatives() {
        assertThat(ApplicationResolver.resolve(APPLICATIONS, "Odysseus"))
                .isInstanceOf(ApplicationLookup.NotFound.class)
                .extracting(result -> ((ApplicationLookup.NotFound) result).available())
                .isEqualTo(List.of("Monetheus", "FilmPickr", "Bürolicht-Dashboard", "Monetheus Web"));
    }

    @Test
    @DisplayName("ohne hinterlegte Anwendungen ist die Kandidatenliste leer, nicht null")
    void handlesEmptyInventory() {
        assertThat(ApplicationResolver.resolve(List.of(), "Monetheus"))
                .isEqualTo(new ApplicationLookup.NotFound(List.of()));
    }

    @Test
    @DisplayName("ein leerer Name loest nichts auf - sonst wuerde der Teilstring auf alles passen")
    void doesNotMatchOnEmptyName() {
        assertThat(ApplicationResolver.resolve(APPLICATIONS, "   "))
                .isInstanceOf(ApplicationLookup.NotFound.class);
    }

    private static MonitoredApplication application(long id, String name) {
        return new MonitoredApplication(id, name, ApplicationState.UP, "HTTP 200", true,
                "container-" + id, "running");
    }
}
