package io.github.yannicks99.jarvis_mcp.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NameNormalizerTest {

    @ParameterizedTest
    @CsvSource({
            "Wohnzimmer,             wohnzimmer",
            "Büro,                   buero",
            "'Stehlampe  links',     stehlampe links",
            "'Küchen-Licht 2',       kuechen licht 2",
            "'  STRASSE  ',          strasse",
            "Straße,                 strasse",
    })
    @DisplayName("kanonische Form: klein, Umlaute ausgeschrieben, Trennzeichen vereinheitlicht")
    void canonical(String raw, String expected) {
        assertThat(NameNormalizer.canonical(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("weggelassene Umlautpunkte finden denselben Eintrag")
    void strippedVariant() {
        assertThat(NameNormalizer.stripped("Büro")).isEqualTo("buro");
        assertThat(NameNormalizer.variants("Büro")).containsExactly("buero", "buro");
    }

    @Test
    @DisplayName("ohne Umlaute gibt es nur eine Variante")
    void singleVariantWithoutDiacritics() {
        assertThat(NameNormalizer.variants("Stehlampe")).containsExactly("stehlampe");
    }

    @Test
    @DisplayName("leere Eingaben liefern keine Varianten")
    void emptyInput() {
        assertThat(NameNormalizer.variants("   ")).isEmpty();
        assertThat(NameNormalizer.canonical(null)).isEmpty();
    }
}
