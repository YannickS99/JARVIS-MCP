package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Prueft die Saetze, die am Ende beim Sprachmodell ankommen. Ohne HTTP und ohne Home Assistant -
 * hier geht es allein darum, dass die Antwort stimmt und nichts behauptet, was niemand geprueft
 * hat.
 */
class LightStatusReportTest {

    private static HomeAssistantLightStatus light(String name, String state, String area) {
        return new HomeAssistantLightStatus("light." + name.toLowerCase().replace(' ', '_'), name,
                state, area.isEmpty() ? "" : area.toLowerCase(), area);
    }

    @Test
    @DisplayName("die Uebersicht gruppiert die brennenden Lichter nach Bereich")
    void groupsByArea() {
        String text = LightStatusReport.overview(List.of(
                light("Stehlampe", "on", "Wohnzimmer"),
                light("Deckenlampe", "on", "Wohnzimmer"),
                light("Schreibtischlampe", "on", "Büro"),
                light("Herdlicht", "off", "Küche")));

        assertThat(text).isEqualTo("Es sind 3 von 4 Lichtern an. "
                + "An: Büro: Schreibtischlampe; Wohnzimmer: Deckenlampe, Stehlampe. "
                + "Ganz aus ist der Bereich Küche.");
    }

    @Test
    @DisplayName("mehrere dunkle Bereiche werden zusammen genannt")
    void listsDarkAreas() {
        String text = LightStatusReport.overview(List.of(
                light("Stehlampe", "on", "Wohnzimmer"),
                light("Herdlicht", "off", "Küche"),
                light("Flurlicht", "off", "Flur")));

        assertThat(text).contains("Ganz aus sind die Bereiche Flur, Küche.");
    }

    @Test
    @DisplayName("ist nichts an, faellt die Aufzaehlung weg")
    void allOff() {
        String text = LightStatusReport.overview(List.of(
                light("Stehlampe", "off", "Wohnzimmer"),
                light("Herdlicht", "off", "Küche")));

        assertThat(text).isEqualTo("Im ganzen Haus ist kein Licht an.");
    }

    @Test
    @DisplayName("ein einzelnes brennendes Licht bekommt die Einzahl")
    void singularReadsCorrectly() {
        String text = LightStatusReport.overview(List.of(
                light("Stehlampe", "on", "Wohnzimmer"),
                light("Herdlicht", "off", "Küche")));

        assertThat(text).startsWith("Es ist 1 von 2 Lichtern an.");
    }

    @Test
    @DisplayName("ein nicht erreichbares Licht zaehlt weder als an noch als aus")
    void unreachableIsNamedSeparately() {
        String text = LightStatusReport.overview(List.of(
                light("Stehlampe", "on", "Wohnzimmer"),
                light("Gartenlicht", "unavailable", "Garten")));

        assertThat(text).isEqualTo("Es ist 1 von 1 Licht an. An: Wohnzimmer: Stehlampe. "
                + "Nicht erreichbar: Gartenlicht.");
    }

    @Test
    @DisplayName("ein Bereich, aus dem nur Unerreichbares kommt, gilt nicht als ausgeschaltet")
    void unreachableAreaIsNotDark() {
        String text = LightStatusReport.overview(List.of(
                light("Stehlampe", "on", "Wohnzimmer"),
                light("Gartenlicht", "unavailable", "Garten")));

        assertThat(text).doesNotContain("Ganz aus");
    }

    @Test
    @DisplayName("ist gar nichts erreichbar, wird auch nichts behauptet")
    void nothingReachable() {
        String text = LightStatusReport.overview(List.of(light("Gartenlicht", "unavailable", "Garten")));

        assertThat(text).isEqualTo("Zurzeit ist kein Licht erreichbar. Nicht erreichbar: Gartenlicht.");
    }

    @Test
    @DisplayName("Lichter ohne Bereich bekommen einen eigenen Sammelbegriff")
    void lightsWithoutArea() {
        String text = LightStatusReport.overview(List.of(light("Lichterkette", "on", "")));

        assertThat(text).contains("ohne Bereich: Lichterkette");
    }

    @Test
    @DisplayName("die Bereichsansicht nennt auch die ausgeschalteten Lichter")
    void areaNamesOffLightsToo() {
        String text = LightStatusReport.forArea("Wohnzimmer", List.of(
                light("Stehlampe", "on", "Wohnzimmer"),
                light("Deckenlampe", "off", "Wohnzimmer"),
                light("Leselampe", "off", "Wohnzimmer")));

        assertThat(text).isEqualTo("Im Bereich 'Wohnzimmer' ist ein Licht an: Stehlampe. "
                + "Aus: Deckenlampe, Leselampe.");
    }

    @Test
    @DisplayName("sind im Bereich mehrere an, stimmt die Mehrzahl")
    void areaPlural() {
        String text = LightStatusReport.forArea("Wohnzimmer", List.of(
                light("Stehlampe", "on", "Wohnzimmer"),
                light("Deckenlampe", "on", "Wohnzimmer")));

        assertThat(text).isEqualTo("Im Bereich 'Wohnzimmer' sind 2 Lichter an: Deckenlampe, Stehlampe.");
    }

    @Test
    @DisplayName("ein Bereich ohne brennendes Licht bekommt die kurze Antwort")
    void areaAllOff() {
        String text = LightStatusReport.forArea("Küche", List.of(light("Herdlicht", "off", "Küche")));

        assertThat(text).isEqualTo("Im Bereich 'Küche' ist kein Licht an.");
    }

    @Test
    @DisplayName("ein Bereich ohne Lichter wird als solcher gemeldet")
    void areaWithoutLights() {
        assertThat(LightStatusReport.forArea("Keller", List.of()))
                .isEqualTo("Im Bereich 'Keller' ist kein Licht eingerichtet.");
    }

    @Test
    @DisplayName("die Einzelantwort nennt den Bereich mit")
    void singleLight() {
        assertThat(LightStatusReport.single(light("Stehlampe", "on", "Wohnzimmer")))
                .isEqualTo("Das Licht 'Stehlampe' im Bereich 'Wohnzimmer' ist an.");
        assertThat(LightStatusReport.single(light("Stehlampe", "off", "Wohnzimmer")))
                .isEqualTo("Das Licht 'Stehlampe' im Bereich 'Wohnzimmer' ist aus.");
    }

    @Test
    @DisplayName("ohne Bereich bleibt die Einzelantwort trotzdem ein ganzer Satz")
    void singleLightWithoutArea() {
        assertThat(LightStatusReport.single(light("Lichterkette", "on", "")))
                .isEqualTo("Das Licht 'Lichterkette' ist an.");
    }

    @Test
    @DisplayName("ein nicht erreichbares Licht wird nicht zu \"aus\" verkuerzt")
    void singleLightUnreachable() {
        assertThat(LightStatusReport.single(light("Gartenlicht", "unavailable", "Garten")))
                .contains("nicht erreichbar")
                .contains("unavailable")
                .doesNotContain("ist aus");
    }

    @Test
    @DisplayName("gar kein Licht in Home Assistant ist kein Fehler")
    void noLightsAtAll() {
        assertThat(LightStatusReport.overview(List.of()))
                .isEqualTo("In Home Assistant ist kein Licht eingerichtet.");
    }
}
