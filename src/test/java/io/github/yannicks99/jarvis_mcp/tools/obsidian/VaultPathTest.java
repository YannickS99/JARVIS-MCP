package io.github.yannicks99.jarvis_mcp.tools.obsidian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Die Pfadpruefung - zweite Absicherung neben dem Mount, der ohnehin nur den freigegebenen Ordner
 * in den Container bringt.
 */
class VaultPathTest {

    @TempDir
    Path root;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(root.resolve("Anforderungen"));
        Files.writeString(root.resolve("Anforderungen/Entwurf.md"), "# Entwurf\n");
    }

    @Test
    @DisplayName("Ein Pfad innerhalb des Ordners wird aufgeloest")
    void resolvesInsideTheFolder() {
        Path resolved = VaultPath.resolve(root, "Anforderungen/Entwurf.md", true);

        assertThat(resolved).isEqualTo(root.resolve("Anforderungen/Entwurf.md"));
        assertThat(VaultPath.relative(root, resolved)).isEqualTo("Anforderungen/Entwurf.md");
    }

    @Test
    @DisplayName("Ein fuehrender Schraegstrich ist der haeufigste Vertipper und wird geheilt")
    void toleratesLeadingSlash() {
        assertThat(VaultPath.resolve(root, "/Anforderungen/Entwurf.md", true))
                .isEqualTo(root.resolve("Anforderungen/Entwurf.md"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../geheim.md",
            "Anforderungen/../../geheim.md",
            "/etc/passwd",
            "..\\geheim.md",
    })
    @DisplayName("Kein Weg fuehrt aus dem freigegebenen Ordner heraus")
    void rejectsEscapes(String path) {
        assertThatThrownBy(() -> VaultPath.resolve(root, path, true))
                .isInstanceOf(ObsidianException.class);
    }

    @Test
    @DisplayName("Eine Verknuepfung nach draussen faellt trotz harmlosem Pfad auf")
    void rejectsSymlinksOutOfTheFolder() throws IOException {
        Path outside = Files.createTempDirectory("ausserhalb");
        Files.writeString(outside.resolve("geheim.md"), "nichts fuer JARVIS");
        try {
            Files.createSymbolicLink(root.resolve("harmlos.md"), outside.resolve("geheim.md"));
        } catch (UnsupportedOperationException | IOException e) {
            return;  // Ohne Symlink-Unterstuetzung ist hier nichts zu pruefen.
        }

        // Der geschriebene Pfad liegt im Ordner - erst das Aufloesen zeigt, wohin er wirklich zeigt.
        assertThatThrownBy(() -> VaultPath.resolve(root, "harmlos.md", true))
                .isInstanceOf(ObsidianException.class)
                .hasMessageContaining("heraus");
    }

    @ParameterizedTest
    @ValueSource(strings = {".obsidian/app.json", ".git/config", ".jarvis-history/alt.md"})
    @DisplayName("Versteckte Ordner bleiben unsichtbar")
    void rejectsHiddenFolders(String path) {
        assertThatThrownBy(() -> VaultPath.resolve(root, path, true))
                .isInstanceOf(ObsidianException.class)
                .hasMessageContaining("Versteckte");
    }

    @Test
    @DisplayName("Eine Notiz muss auf .md enden, ein Ordner gerade nicht")
    void requiresMarkdownForNotes() {
        assertThatThrownBy(() -> VaultPath.resolve(root, "Anforderungen/Entwurf", true))
                .isInstanceOf(ObsidianException.class)
                .hasMessageContaining(".md");

        assertThat(VaultPath.resolve(root, "Anforderungen", false)).isEqualTo(root.resolve("Anforderungen"));
    }

    @Test
    @DisplayName("Ein leerer Pfad sagt, was stattdessen gemeint war")
    void explainsMissingPath() {
        assertThatThrownBy(() -> VaultPath.resolve(root, "  ", true))
                .isInstanceOf(ObsidianException.class)
                .hasMessageContaining(".md");
    }
}
