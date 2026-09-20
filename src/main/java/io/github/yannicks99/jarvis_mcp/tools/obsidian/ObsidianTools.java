package io.github.yannicks99.jarvis_mcp.tools.obsidian;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * Die lesenden Obsidian-Werkzeuge (Anforderungskatalog JARVIS-Obsidian, Abschnitt 6).
 *
 * <p>Anders als bei den uebrigen Modulen sind die Rueckgaben hier keine ausformulierten Saetze,
 * sondern der Notizinhalt bzw. knappe Listen: Das Sprachmodell soll damit <em>arbeiten</em> - zitieren,
 * ueberarbeiten, Zusammenhaenge finden -, nicht nur eine Auskunft daraus bauen.
 *
 * <p>Alle drei sind rein lesend und wiederholbar. Der semantische Cache des JARVIS-AIService merkt
 * sich deshalb hoechstens die Entscheidung, sie aufzurufen - nie ihr Ergebnis; gelesen wird immer
 * frisch, sonst stuende eine gerade in Obsidian geaenderte Notiz veraltet im Gespraech.
 */
public class ObsidianTools {

    private static final Logger log = LoggerFactory.getLogger(ObsidianTools.class);

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final Vault vault;

    public ObsidianTools(Vault vault) {
        this.vault = vault;
    }

    @McpTool(name = "read_note",
            annotations = @McpAnnotations(readOnlyHint = true, idempotentHint = true,
                    destructiveHint = false, openWorldHint = false),
            description = """
                    Liest eine Notiz aus Yannicks Obsidian-Vault und gibt ihren Markdown-Text \
                    zurueck. Dafuer, wenn nach dem Inhalt einer Notiz gefragt ist oder eine \
                    ueberarbeitet werden soll. Der Pfad ist der aus list_notes oder \
                    search_notes, z. B. "Anforderungen/Entwurf.md". Die Antwort nennt oben \
                    einen "Stand" - genau den verlangt replace_section spaeter, um sicher zu \
                    gehen, dass die Notiz sich zwischenzeitlich nicht geaendert hat.""")
    public String readNote(
            @McpToolParam(required = true,
                    description = "Pfad der Notiz innerhalb des freigegebenen Ordners, "
                            + "z. B. \"Anforderungen/Entwurf.md\".")
            String path) {

        Vault.Note note = vault.read(path);
        log.debug("Notiz {} gelesen ({} Zeichen)", note.path(), note.text().length());

        String header = "Notiz \"%s\" (Stand %s)%s:%n%n".formatted(
                note.path(), note.stand(),
                note.truncated() ? " - gekuerzt, die Notiz ist laenger" : "");
        return header + note.text();
    }

    @McpTool(name = "list_notes",
            annotations = @McpAnnotations(readOnlyHint = true, idempotentHint = true,
                    destructiveHint = false, openWorldHint = false),
            description = """
                    Listet die Notizen im freigegebenen Bereich von Yannicks Obsidian-Vault, \
                    mit Pfad, Groesse und letzter Aenderung. Dafuer, um herauszufinden, welche \
                    Notizen es ueberhaupt gibt, bevor eine davon gelesen oder geschrieben wird. \
                    Ohne Angabe kommt alles, mit Ordnerangabe nur dieser Unterordner.""")
    public String listNotes(
            @McpToolParam(required = false,
                    description = "Optional: Unterordner, z. B. \"Anforderungen\". Weglassen fuer alles.")
            String folder) {

        List<Vault.NoteInfo> notes = vault.list(folder);
        if (notes.isEmpty()) {
            return folder == null || folder.isBlank()
                    ? "Im freigegebenen Bereich liegt noch keine Notiz."
                    : "In \"%s\" liegt keine Notiz.".formatted(folder);
        }

        StringBuilder answer = new StringBuilder(
                "%d Notiz(en)%s:%n".formatted(notes.size(),
                        folder == null || folder.isBlank() ? "" : " in \"" + folder + "\""));
        for (Vault.NoteInfo note : notes) {
            answer.append("- %s (%d Zeichen, geaendert %s)%n"
                    .formatted(note.path(), note.bytes(), DATE.format(note.modified())));
        }
        return answer.toString();
    }

    @McpTool(name = "search_notes",
            annotations = @McpAnnotations(readOnlyHint = true, idempotentHint = true,
                    destructiveHint = false, openWorldHint = false),
            description = """
                    Durchsucht die Notizen im freigegebenen Bereich nach einem Begriff und \
                    nennt Pfad, Zeile und die Fundstelle. Dafuer, wenn der Pfad einer Notiz \
                    nicht bekannt ist oder nachgesehen werden soll, wo ein Thema schon einmal \
                    vorkommt. Gesucht wird im Text und im Dateinamen, Gross- und Kleinschreibung \
                    spielt keine Rolle. Anschliessend die gefundene Notiz mit read_note lesen.""")
    public String searchNotes(
            @McpToolParam(required = true, description = "Suchbegriff, z. B. \"Satellite\".")
            String query,
            @McpToolParam(required = false,
                    description = "Optional: Unterordner, in dem gesucht wird.")
            String folder) {

        List<Vault.SearchHit> hits = vault.search(query, folder);
        if (hits.isEmpty()) {
            return "Keine Notiz enthaelt \"%s\".".formatted(query);
        }

        StringBuilder answer = new StringBuilder("%d Fundstelle(n) fuer \"%s\":%n".formatted(hits.size(), query));
        for (Vault.SearchHit hit : hits) {
            answer.append(hit.line() == 0
                    ? "- %s (Dateiname): %s%n".formatted(hit.path(), hit.snippet())
                    : "- %s, Zeile %d: %s%n".formatted(hit.path(), hit.line(), hit.snippet()));
        }
        return answer.toString();
    }
}
