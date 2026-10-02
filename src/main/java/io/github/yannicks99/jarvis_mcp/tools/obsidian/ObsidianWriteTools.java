package io.github.yannicks99.jarvis_mcp.tools.obsidian;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * Die schreibenden Obsidian-Werkzeuge - eigene Klasse, damit sich der Vault auch nur lesend
 * anbinden laesst ({@code jarvis-mcp.obsidian.writable=false}): Was nicht als Bean entsteht, taucht
 * gar nicht erst in {@code tools/list} auf.
 *
 * <p><strong>Keines dieser Werkzeuge ist idempotent</strong> - zweimal ausgefuehrt entsteht zweimal
 * etwas anderes. Damit sind sie fuer den semantischen Cache des JARVIS-AIService von vornherein
 * kein Kandidat: Er wiederholt nur, was idempotent und nicht rein lesend ist (siehe
 * Anforderungskatalog JARVIS-SemanticCache, Abschnitt 4).
 *
 * <p>Eine Rueckfrage vor dem Schreiben gibt es bewusst nicht - sie waere im Gespraech nur laestig.
 * Stattdessen sichert {@link Vault} vor jeder Aenderung die Vorgaengerfassung, und
 * {@code replace_section} schreibt nur, wenn die Notiz noch dem gelesenen Stand entspricht.
 */
public class ObsidianWriteTools {

    private static final Logger log = LoggerFactory.getLogger(ObsidianWriteTools.class);

    private final Vault vault;

    public ObsidianWriteTools(Vault vault) {
        this.vault = vault;
    }

    @McpTool(name = "create_note",
            metaProvider = WorkModeOnly.class,
            annotations = @McpAnnotations(readOnlyHint = false, idempotentHint = false,
                    destructiveHint = false, openWorldHint = false),
            description = """
                    Legt eine neue Notiz in Yannicks Obsidian-Vault an. Dafuer, wenn ein Entwurf, \
                    ein Protokoll oder eine Zusammenfassung dauerhaft festgehalten werden soll. \
                    Der Inhalt ist Markdown im Stil des Vaults: echte Ueberschriften mit ## \
                    (keine nur fett gesetzten Zeilen), Tabellen fuer Uebersichten, [[Wikilinks]] \
                    auf verwandte Notizen, Datumsangaben als TT.MM.JJJJ. Geschrieben werden darf \
                    nur im freigegebenen Ordner. Eine bereits bestehende Notiz wird nicht \
                    ueberschrieben - dafuer append_note, replace_section oder replace_text.""")
    public String createNote(
            @McpToolParam(required = true,
                    description = "Pfad der neuen Notiz, z. B. \"Entwuerfe/Satelliten.md\". "
                            + "Fehlende Ordner werden angelegt.")
            String path,
            @McpToolParam(required = true, description = "Der vollstaendige Inhalt als Markdown.")
            String content) {

        String created = vault.create(path, content);
        log.info("Notiz {} angelegt ({} Zeichen)", created, content.length());
        return "Notiz \"%s\" angelegt.".formatted(created);
    }

    @McpTool(name = "append_note",
            metaProvider = WorkModeOnly.class,
            annotations = @McpAnnotations(readOnlyHint = false, idempotentHint = false,
                    destructiveHint = false, openWorldHint = false),
            description = """
                    Haengt Text an das Ende einer bestehenden Notiz an, ohne den bisherigen \
                    Inhalt anzutasten. Dafuer, wenn etwas ergaenzt wird - ein weiterer Punkt, \
                    ein Nachtrag, ein neuer Abschnitt. Soll dagegen vorhandener Text \
                    ueberarbeitet werden, ist replace_section richtig.""")
    public String appendNote(
            @McpToolParam(required = true, description = "Pfad der bestehenden Notiz.")
            String path,
            @McpToolParam(required = true, description = "Der anzuhaengende Markdown-Text.")
            String content) {

        String updated = vault.append(path, content);
        log.info("Notiz {} ergaenzt ({} Zeichen)", updated, content.length());
        return "Notiz \"%s\" ergaenzt.".formatted(updated);
    }

    // Destruktiv, weil vorhandener Text ersetzt wird - die Vorgaengerfassung liegt danach in
    // .jarvis-history, rueckgaengig macht sie aber nur ein Mensch.
    @McpTool(name = "replace_section",
            metaProvider = WorkModeOnly.class,
            annotations = @McpAnnotations(readOnlyHint = false, idempotentHint = false,
                    destructiveHint = true, openWorldHint = false),
            description = """
                    Ersetzt den Inhalt eines Abschnitts einer Notiz; die Ueberschrift selbst \
                    bleibt stehen. Das ist der Weg, eine Notiz gemeinsam zu ueberarbeiten. \
                    Vorher die Notiz mit read_note lesen und den dort genannten "Stand" hier \
                    angeben - stimmt er nicht mehr, wurde die Notiz zwischenzeitlich geaendert \
                    und es wird nichts ueberschrieben; dann erneut lesen und die Aenderung darauf \
                    aufsetzen. Fuer eine Stelle ohne eigene #-Ueberschrift replace_text.""")
    public String replaceSection(
            @McpToolParam(required = true, description = "Pfad der Notiz.")
            String path,
            @McpToolParam(required = true,
                    description = "Die Ueberschrift des Abschnitts, ohne Rautenzeichen, "
                            + "z. B. \"Offene Punkte\".")
            String heading,
            @McpToolParam(required = true, description = "Der neue Inhalt des Abschnitts als Markdown.")
            String content,
            @McpToolParam(required = true, description = "Der \"Stand\" aus read_note.")
            String stand) {

        String updated = vault.replaceSection(path, heading, content, stand);
        log.info("Abschnitt \"{}\" in {} ersetzt", heading, updated);
        return "Abschnitt \"%s\" in \"%s\" ersetzt; die vorherige Fassung liegt in %s."
                .formatted(heading, updated, VaultPath.HISTORY_DIR);
    }

    // Destruktiv aus demselben Grund wie replace_section.
    @McpTool(name = "replace_text",
            metaProvider = WorkModeOnly.class,
            annotations = @McpAnnotations(readOnlyHint = false, idempotentHint = false,
                    destructiveHint = true, openWorldHint = false),
            description = """
                    Ersetzt eine genau benannte Textstelle in einer Notiz - fuer alles ohne eigene \
                    Ueberschrift: einen Punkt einer Liste, eine fett gesetzte Zwischenzeile, einen \
                    Satz. Mit leerem neuen Text wird die Stelle geloescht. Den bisherigen Text genau \
                    so angeben, wie read_note ihn zeigt (inklusive ** und Aufzaehlungszeichen); er \
                    muss genau einmal vorkommen. Ganze Abschnitte unter einer #-Ueberschrift \
                    besser mit replace_section. Wie dort den "Stand" aus read_note angeben.""")
    public String replaceText(
            @McpToolParam(required = true, description = "Pfad der Notiz.")
            String path,
            @McpToolParam(required = true,
                    description = "Der bisherige Text, genau wie in der Notiz, z. B. "
                            + "\"- Soll es einen Multiplayer-Modus geben?\".")
            String oldText,
            @McpToolParam(required = true,
                    description = "Der neue Text; leer, um die Stelle zu loeschen.")
            String newText,
            @McpToolParam(required = true, description = "Der \"Stand\" aus read_note.")
            String stand) {

        String updated = vault.replaceText(path, oldText, newText, stand);
        log.info("Textstelle in {} ersetzt ({} -> {} Zeichen)", updated, oldText.length(),
                newText == null ? 0 : newText.length());
        return "Textstelle in \"%s\" %s; die vorherige Fassung liegt in %s."
                .formatted(updated, newText == null || newText.isEmpty() ? "geloescht" : "ersetzt",
                        VaultPath.HISTORY_DIR);
    }
}
