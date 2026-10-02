package io.github.yannicks99.jarvis_mcp.tools.obsidian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lesen weit, schreiben eng (Anforderungskatalog JARVIS-Obsidian, Abschnitt 6).
 *
 * <p>JARVIS soll den Zusammenhang kennen - die Uebersichten und die anderen Kataloge liegen
 * ausserhalb des Ordners, in dem er arbeiten darf. Aendern darf er trotzdem nur dort; im Container
 * ist der Rest zusaetzlich nur lesend eingehaengt.
 */
class ObsidianScopeTest {

    @TempDir
    Path root;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(root.resolve("Entwuerfe"));
        Files.createDirectories(root.resolve("Anforderungen"));
        Files.createDirectories(root.resolve("ALoveLetter"));
        Files.writeString(root.resolve("Entwuerfe/Offen.md"), "# Offen\n\n## Stand\n\nnichts\n");
        Files.writeString(root.resolve("Anforderungen/Satelliten.md"), "# Satelliten\n\nMikrofon fehlt\n");
        Files.writeString(root.resolve("Notizen.md"), "Kurze Notiz.\n");
        Files.writeString(root.resolve("ALoveLetter/Brief.md"), "Sehr privat.\n");
    }

    private Vault vault(String writeSubpath, String deny) {
        return new Vault(new ObsidianProperties(true, root, true, writeSubpath, deny, 60_000, 40, 240));
    }

    @Test
    @DisplayName("Gelesen wird im ganzen Vault, auch ausserhalb des Schreibordners")
    void readsEverywhere() {
        ObsidianTools tools = new ObsidianTools(vault("Entwuerfe", ""));

        assertThat(tools.readNote("Anforderungen/Satelliten.md")).contains("Mikrofon fehlt");
        assertThat(tools.listNotes(null)).contains("Notizen.md", "Entwuerfe/Offen.md");
    }

    @Test
    @DisplayName("Geschrieben wird nur im Schreibordner")
    void writesOnlyInsideTheWriteRoot() {
        ObsidianWriteTools writing = new ObsidianWriteTools(vault("Entwuerfe", ""));

        writing.appendNote("Entwuerfe/Offen.md", "- ein Punkt");

        assertThatThrownBy(() -> writing.appendNote("Notizen.md", "- daneben"))
                .isInstanceOf(ObsidianException.class)
                .hasMessageContaining("Entwuerfe");
        assertThatThrownBy(() -> writing.createNote("Anforderungen/Neu.md", "Text"))
                .isInstanceOf(ObsidianException.class)
                .hasMessageContaining("nur in");
    }

    @Test
    @DisplayName("Vorgaengerfassungen landen im Schreibordner - die Vault-Wurzel ist im Container nur lesend")
    void keepsHistoryInsideTheWriteRoot() throws IOException {
        // Wie auf JARVIS: Die Wurzel ist schreibgeschuetzt, nur der Schreibordner nicht. Vorher
        // landete die Sicherung unter <Wurzel>/.jarvis-history - "Read-only file system", und
        // append_note und replace_section scheiterten jedes Mal.
        Set<PosixFilePermission> before = Files.getPosixFilePermissions(root);
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            new ObsidianWriteTools(vault("Entwuerfe", "")).appendNote("Entwuerfe/Offen.md", "- ein Punkt");
        } finally {
            Files.setPosixFilePermissions(root, before);
        }

        assertThat(root.resolve(VaultPath.HISTORY_DIR)).doesNotExist();
        try (var history = Files.list(root.resolve("Entwuerfe").resolve(VaultPath.HISTORY_DIR))) {
            assertThat(history.map(path -> path.getFileName().toString()).toList())
                    .singleElement()
                    .asString()
                    .startsWith("Entwuerfe_Offen.md-");
        }
        // Versteckt bleibt sie trotzdem: keine Notiz in Listen und Suchergebnissen.
        assertThat(new ObsidianTools(vault("Entwuerfe", "")).listNotes("Entwuerfe")).doesNotContain(VaultPath.HISTORY_DIR);
    }

    @Test
    @DisplayName("Ohne Angabe bleibt es beim bisherigen Verhalten: schreiben im ganzen Bereich")
    void writesAnywhereWithoutWriteRoot() {
        new ObsidianWriteTools(vault("", "")).appendNote("Notizen.md", "- geht");

        assertThat(new ObsidianTools(vault("", "")).readNote("Notizen.md")).contains("- geht");
    }

    @Test
    @DisplayName("Gesperrte Ordner sind unsichtbar und unlesbar")
    void deniedFoldersStayInvisible() {
        ObsidianTools tools = new ObsidianTools(vault("Entwuerfe", "ALoveLetter"));

        assertThat(tools.listNotes(null)).doesNotContain("ALoveLetter");
        assertThat(tools.searchNotes("privat", null)).contains("Keine Notiz");
        assertThatThrownBy(() -> tools.readNote("ALoveLetter/Brief.md"))
                .isInstanceOf(ObsidianException.class)
                .hasMessageContaining("gesperrt");
    }
}
