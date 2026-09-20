package io.github.yannicks99.jarvis_mcp.tools.obsidian;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Uebersetzt einen Pfad aus einem Werkzeugaufruf in eine Datei innerhalb des freigegebenen Ordners -
 * und laesst nichts durch, was darueber hinausfuehrt.
 *
 * <p>Die eigentliche Absicherung ist der Mount: In den Container kommt nur der freigegebene Ordner,
 * der Rest des Vaults existiert dort gar nicht. Diese Klasse ist die zweite Ebene - sie faengt den
 * Fall ab, dass jemand den Mount weiter setzt, und die Wege, auf denen ein Pfad trotz Mount nach
 * draussen zeigen kann:
 *
 * <ul>
 *   <li><strong>{@code ..} und absolute Pfade</strong> - abgewiesen, bevor irgendetwas geoeffnet
 *       wird.</li>
 *   <li><strong>Symbolische Verknuepfungen</strong> - der aufgeloeste Pfad ({@code toRealPath})
 *       muss weiterhin im Ordner liegen. Eine Verknuepfung im Vault, die nach {@code /etc} zeigt,
 *       faellt damit auf, obwohl der geschriebene Pfad harmlos aussieht.</li>
 *   <li><strong>Versteckte Ordner</strong> - {@code .obsidian}, {@code .git} und die eigene
 *       Sicherungsablage gehoeren nicht in eine Antwort der KI.</li>
 * </ul>
 */
final class VaultPath {

    /** Dorthin legt {@code Vault} die Vorgaengerfassungen; die KI hat dort nichts zu suchen. */
    static final String HISTORY_DIR = ".jarvis-history";

    private VaultPath() {
    }

    /**
     * Loest einen relativen Pfad gegen den Ordner auf.
     *
     * @param note {@code true}, wenn es eine Notiz sein muss (Endung {@code .md}) - bei einem
     *             Ordner ist die Endung gerade nicht erwuenscht
     */
    static Path resolve(Path root, String relative, boolean note) {
        if (relative == null || relative.isBlank()) {
            throw new ObsidianException("Es fehlt der Pfad innerhalb des Ordners, z. B. "
                    + "\"Anforderungen/Entwurf.md\".");
        }

        String cleaned = relative.strip().replace('\\', '/');
        // Ein fuehrender Schraegstrich ist der haeufigste Vertipper und harmlos zu heilen: Gemeint
        // ist immer etwas innerhalb des Ordners.
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isBlank()) {
            throw new ObsidianException("Der Pfad zeigt auf den Ordner selbst, nicht auf eine Notiz.");
        }

        Path candidate;
        try {
            candidate = Path.of(cleaned).normalize();
        } catch (InvalidPathException e) {
            throw new ObsidianException("\"%s\" ist kein gueltiger Pfad.".formatted(relative), e);
        }

        if (candidate.isAbsolute() || candidate.startsWith("..")) {
            throw new ObsidianException("Pfade gelten immer innerhalb des freigegebenen Ordners - "
                    + "ohne fuehrenden Schraegstrich und ohne \"..\".");
        }
        for (Path element : candidate) {
            String name = element.toString();
            if (name.startsWith(".")) {
                throw new ObsidianException("Versteckte Ordner und Dateien (%s) sind nicht zugaenglich."
                        .formatted(name));
            }
        }
        if (note && !candidate.toString().endsWith(".md")) {
            throw new ObsidianException("Notizen enden auf \".md\" - gemeint war vermutlich \"%s.md\"."
                    .formatted(candidate));
        }

        Path resolved = root.resolve(candidate).normalize();
        ensureInside(root, resolved);
        return resolved;
    }

    /**
     * Prueft nach dem Aufloesen symbolischer Verknuepfungen, dass der Pfad wirklich im Ordner liegt.
     *
     * <p>Geprueft wird der naechste <em>vorhandene</em> Vorfahr: Eine neue Notiz existiert noch
     * nicht, und ihr Ordner womoeglich auch nicht - {@code create_note} legt ihn an. Der Rest des
     * Pfades ist zu diesem Zeitpunkt bereits normalisiert und enthaelt kein {@code ..} mehr, kann
     * also nicht mehr nach draussen fuehren.
     */
    private static void ensureInside(Path root, Path resolved) {
        try {
            Path realRoot = root.toRealPath();
            Path existing = resolved;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (existing == null || !existing.toRealPath().startsWith(realRoot)) {
                throw new ObsidianException("Der Pfad fuehrt aus dem freigegebenen Ordner heraus.");
            }
        } catch (IOException e) {
            throw new ObsidianException(
                    "Der Pfad \"%s\" liess sich nicht pruefen: %s".formatted(resolved, e.getMessage()), e);
        }
    }

    /** Der Pfad, wie ihn die KI wieder nennen kann: relativ zum Ordner, mit Schraegstrichen. */
    static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
