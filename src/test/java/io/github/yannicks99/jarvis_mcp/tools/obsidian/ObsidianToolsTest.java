package io.github.yannicks99.jarvis_mcp.tools.obsidian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Die Obsidian-Werkzeuge gegen einen echten Ordner im Dateisystem - ohne Vault, ohne Obsidian.
 */
class ObsidianToolsTest {

    @TempDir
    Path root;

    private Vault vault;
    private ObsidianTools tools;
    private ObsidianWriteTools writeTools;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(root.resolve("Anforderungen"));
        Files.writeString(root.resolve("Anforderungen/Satelliten.md"), """
                # Satelliten

                ## Hardware

                Raspberry Pi 4, offen.

                ## Offene Punkte

                - Mikrofon fehlt
                """);
        Files.writeString(root.resolve("Notizen.md"), "Kurze Notiz ueber das Wohnzimmer.\n");

        vault = new Vault(properties(true));
        tools = new ObsidianTools(vault);
        writeTools = new ObsidianWriteTools(vault);
    }

    private ObsidianProperties properties(boolean writable) {
        return new ObsidianProperties(true, root, writable, "", "", 60_000, 40, 240);
    }

    // ------------------------------------------------------------------ lesen

    @Test
    @DisplayName("read_note liefert den Text und den Stand, den replace_section spaeter verlangt")
    void readsNoteWithStand() {
        String answer = tools.readNote("Anforderungen/Satelliten.md");

        assertThat(answer).contains("Notiz \"Anforderungen/Satelliten.md\" (Stand ");
        assertThat(answer).contains("## Offene Punkte", "Mikrofon fehlt");
    }

    @Test
    @DisplayName("Eine ueberlange Notiz wird gekuerzt, nicht abgelehnt")
    void shortensOverlongNotes() throws IOException {
        Files.writeString(root.resolve("Lang.md"), "x".repeat(500));
        ObsidianTools small = new ObsidianTools(
                new Vault(new ObsidianProperties(true, root, true, "", "", 100, 40, 240)));

        String answer = small.readNote("Lang.md");

        assertThat(answer).contains("gekuerzt");
        assertThat(answer).hasSizeLessThan(300);
    }

    @Test
    @DisplayName("Eine fehlende Notiz sagt der KI, wie sie den richtigen Pfad findet")
    void explainsMissingNote() {
        assertThatThrownBy(() -> tools.readNote("Gibtsnicht.md"))
                .isInstanceOf(ObsidianException.class)
                .hasMessageContaining("list_notes");
    }

    @Test
    @DisplayName("list_notes nennt Pfade, Groesse und Aenderungsdatum")
    void listsNotes() {
        String all = tools.listNotes(null);

        assertThat(all).contains("Anforderungen/Satelliten.md", "Notizen.md", "geaendert");
        assertThat(tools.listNotes("Anforderungen")).contains("Satelliten.md").doesNotContain("Notizen.md");
    }

    @Test
    @DisplayName("Versteckte Ordner tauchen in keiner Liste auf")
    void hidesHiddenFolders() throws IOException {
        Files.createDirectories(root.resolve(VaultPath.HISTORY_DIR));
        Files.writeString(root.resolve(VaultPath.HISTORY_DIR + "/alt.md"), "alte Fassung");
        Files.createDirectories(root.resolve(".obsidian"));
        Files.writeString(root.resolve(".obsidian/notiz.md"), "intern");

        assertThat(tools.listNotes(null)).doesNotContain("alt.md", ".obsidian");
    }

    @Test
    @DisplayName("search_notes findet im Text und im Dateinamen")
    void searchesTextAndFilename() {
        assertThat(tools.searchNotes("mikrofon", null))
                .contains("Anforderungen/Satelliten.md", "Zeile");
        assertThat(tools.searchNotes("Satelliten", null)).contains("Dateiname");
        assertThat(tools.searchNotes("Rollladen", null)).contains("Keine Notiz");
    }

    // --------------------------------------------------------------- schreiben

    @Test
    @DisplayName("create_note legt an, ueberschreibt aber nie")
    void createsButNeverOverwrites() {
        writeTools.createNote("Entwuerfe/Neu.md", "# Neu\n\nErster Absatz.");

        assertThat(root.resolve("Entwuerfe/Neu.md")).exists();
        assertThat(tools.readNote("Entwuerfe/Neu.md")).contains("Erster Absatz.");

        assertThatThrownBy(() -> writeTools.createNote("Entwuerfe/Neu.md", "anderer Text"))
                .isInstanceOf(ObsidianException.class)
                .hasMessageContaining("append_note");
    }

    @Test
    @DisplayName("append_note haengt an und laesst das Bisherige stehen")
    void appendsToNote() throws IOException {
        writeTools.appendNote("Notizen.md", "- Nachtrag");

        assertThat(Files.readString(root.resolve("Notizen.md")))
                .startsWith("Kurze Notiz ueber das Wohnzimmer.")
                .endsWith("- Nachtrag\n");
    }

    @Test
    @DisplayName("replace_section tauscht genau einen Abschnitt aus")
    void replacesOneSection() throws IOException {
        String stand = stand("Anforderungen/Satelliten.md");

        writeTools.replaceSection("Anforderungen/Satelliten.md", "Offene Punkte",
                "- Mikrofon ausgewaehlt\n- Gehaeuse offen", stand);

        String updated = Files.readString(root.resolve("Anforderungen/Satelliten.md"));
        assertThat(updated).contains("## Hardware", "Raspberry Pi 4, offen.");
        assertThat(updated).contains("- Mikrofon ausgewaehlt", "- Gehaeuse offen");
        assertThat(updated).doesNotContain("Mikrofon fehlt");
        // Die Ueberschrift bleibt stehen, sonst waere der Abschnitt danach namenlos.
        assertThat(updated).contains("## Offene Punkte");
    }

    @Test
    @DisplayName("Ein Abschnitt in der Mitte laesst die folgenden unberuehrt")
    void keepsFollowingSections() throws IOException {
        writeTools.replaceSection("Anforderungen/Satelliten.md", "Hardware",
                "Raspberry Pi 5.", stand("Anforderungen/Satelliten.md"));

        String updated = Files.readString(root.resolve("Anforderungen/Satelliten.md"));
        assertThat(updated).contains("Raspberry Pi 5.", "## Offene Punkte", "- Mikrofon fehlt");
        assertThat(updated.indexOf("## Hardware")).isLessThan(updated.indexOf("## Offene Punkte"));
    }

    @Test
    @DisplayName("Wurde die Notiz zwischenzeitlich geaendert, wird nichts ueberschrieben")
    void refusesToOverwriteChangedNotes() throws IOException {
        String stand = stand("Notizen.md");
        // Jemand tippt in Obsidian - derselbe Vault ist dort offen.
        Files.writeString(root.resolve("Notizen.md"), "# Notizen\n\n## Ideen\n\nVon Hand ergaenzt.\n");

        assertThatThrownBy(() -> writeTools.replaceSection("Notizen.md", "Ideen", "ersetzt", stand))
                .isInstanceOf(ObsidianException.class)
                .hasMessageContaining("geaendert");

        assertThat(Files.readString(root.resolve("Notizen.md"))).contains("Von Hand ergaenzt.");
    }

    @Test
    @DisplayName("Eine unbekannte Ueberschrift nennt die vorhandenen")
    void listsAvailableHeadings() {
        assertThatThrownBy(() -> writeTools.replaceSection("Anforderungen/Satelliten.md",
                "Zeitplan", "…", stand("Anforderungen/Satelliten.md")))
                .isInstanceOf(ObsidianException.class)
                .hasMessageContaining("Hardware")
                .hasMessageContaining("Offene Punkte");
    }

    @Test
    @DisplayName("Jede Aenderung sichert die Vorgaengerfassung")
    void keepsThePreviousVersion() throws IOException {
        writeTools.appendNote("Notizen.md", "- Nachtrag");

        try (var history = Files.list(root.resolve(VaultPath.HISTORY_DIR))) {
            List<Path> backups = history.toList();
            assertThat(backups).hasSize(1);
            // Genau deshalb braucht es keine Rueckfrage vor dem Schreiben.
            assertThat(Files.readString(backups.getFirst())).isEqualTo("Kurze Notiz ueber das Wohnzimmer.\n");
        }
    }

    @Test
    @DisplayName("Nach dem Schreiben bleibt keine temporaere Datei liegen")
    void leavesNoTemporaryFiles() throws IOException {
        writeTools.createNote("Sauber.md", "Inhalt");

        try (var files = Files.walk(root)) {
            assertThat(files.map(Path::toString).filter(name -> name.endsWith(".jarvis-tmp"))).isEmpty();
        }
    }

    private String stand(String path) {
        return vault.read(path).stand();
    }
}
