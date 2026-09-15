package io.github.yannicks99.jarvis_mcp.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Dass aus einer gesprochenen Aeusserung "an" und "aus" wird, ist der einzige Zweck dieser
 * Aufzaehlung - und der einzige Ort, an dem diese Wortliste steht. Was die Module daraus machen
 * (Dienstaufruf in Home Assistant, Container-Start im Monitoring Tool), pruefen deren eigene Tests.
 */
class PowerTest {

    @ParameterizedTest
    @ValueSource(strings = {"on", "ON", " an ", "ein", "einschalten", "true", "1", "start", "starten"})
    void on(String raw) {
        assertThat(Power.parse(raw)).contains(Power.ON);
    }

    @ParameterizedTest
    @ValueSource(strings = {"off", "OFF", "aus", "ausschalten", "false", "0", "stop", "stopp", "stoppen"})
    void off(String raw) {
        assertThat(Power.parse(raw)).contains(Power.OFF);
    }

    @Test
    @DisplayName("alles andere wird nicht geraten")
    void rejectsAnythingElse() {
        assertThat(Power.parse("heller")).isEmpty();
        assertThat(Power.parse("neu starten")).isEmpty();
        assertThat(Power.parse(null)).isEmpty();
    }
}
