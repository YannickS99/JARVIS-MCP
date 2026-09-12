package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PowerTest {

    @ParameterizedTest
    @ValueSource(strings = {"on", "ON", " an ", "ein", "true", "1"})
    void on(String raw) {
        assertThat(Power.parse(raw)).contains(Power.ON);
        assertThat(Power.ON.service()).isEqualTo("turn_on");
    }

    @ParameterizedTest
    @ValueSource(strings = {"off", "OFF", "aus", "false", "0"})
    void off(String raw) {
        assertThat(Power.parse(raw)).contains(Power.OFF);
        assertThat(Power.OFF.service()).isEqualTo("turn_off");
    }

    @Test
    @DisplayName("alles andere wird nicht geraten")
    void rejectsAnythingElse() {
        assertThat(Power.parse("heller")).isEmpty();
        assertThat(Power.parse(null)).isEmpty();
    }
}
