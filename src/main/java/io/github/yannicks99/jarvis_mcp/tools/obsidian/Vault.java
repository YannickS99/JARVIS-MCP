package io.github.yannicks99.jarvis_mcp.tools.obsidian;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Der Dateizugriff auf den freigegebenen Vault-Ordner.
 *
 * <p>Bewusst schlicht und ohne Obsidian-eigene Schnittstelle: Der Ordner liegt auf demselben Rechner
 * wie JARVIS-MCP, ein HTTP-Umweg ueber ein laufendes Obsidian waere nur Latenz und eine zusaetzliche
 * Abhaengigkeit (Anforderungskatalog JARVIS-Obsidian, Abschnitt 6).
 *
 * <p>Beim Schreiben gelten drei Regeln, weil derselbe Vault gleichzeitig in Obsidian offen ist:
 *
 * <ol>
 *   <li><strong>Atomar</strong> - erst in eine Nachbardatei schreiben, dann umbenennen. Ein
 *       Absturz mittendrin hinterlaesst nie eine halbe Notiz.</li>
 *   <li><strong>Vorgaengerfassung sichern</strong> - jede Aenderung legt die alte Fassung in
 *       {@code .jarvis-history} ab. Deshalb braucht es keine Rueckfrage vor jedem Schreibvorgang.</li>
 *   <li><strong>Stand pruefen</strong> - wer eine Notiz ersetzt, nennt den Stand, den er gelesen
 *       hat. Hat sich die Datei seither geaendert, wird abgebrochen statt ueberschrieben.</li>
 * </ol>
 */
class Vault {

    private static final Logger log = LoggerFactory.getLogger(Vault.class);

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ObsidianProperties properties;

    Vault(ObsidianProperties properties) {
        this.properties = properties;
    }

    /** Eine gelesene Notiz. {@code stand} ist die Kennung, mit der sie sich wieder ersetzen laesst. */
    record Note(String path, String text, String stand, boolean truncated) {
    }

    record NoteInfo(String path, long bytes, Instant modified) {
    }

    record SearchHit(String path, int line, String snippet) {
    }

    // ------------------------------------------------------------------ lesen

    Note read(String path) {
        Path file = file(path);
        String text = readText(file);
        boolean truncated = text.length() > properties.maxNoteChars();
        return new Note(
                VaultPath.relative(root(), file),
                truncated ? text.substring(0, properties.maxNoteChars()) : text,
                stand(text),
                truncated);
    }

    List<NoteInfo> list(String folder) {
        Path directory = folder == null || folder.isBlank()
                ? root()
                : VaultPath.resolve(root(), folder, false);

        if (!Files.isDirectory(directory)) {
            throw new ObsidianException("\"%s\" ist kein Ordner im Vault.".formatted(folder));
        }

        try (Stream<Path> entries = Files.walk(directory)) {
            return entries.filter(Files::isRegularFile)
                    .filter(Vault::isNote)
                    .filter(file -> !hidden(directory, file))
                    .filter(file -> !VaultPath.denied(root(), file, properties.deniedFolders()))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(properties.maxResults())
                    .map(this::info)
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            throw new ObsidianException("Der Ordner liess sich nicht lesen: " + e.getMessage(), e);
        }
    }

    /**
     * Volltextsuche ueber die Notizen - Zeile fuer Zeile, ohne Index.
     *
     * <p>Fuer einen Ordner dieser Groesse ist das schnell genug, und es hat gegenueber einem Index
     * den Vorteil, dass nichts veralten kann: Was gerade in Obsidian geschrieben wurde, ist sofort
     * auffindbar.
     */
    List<SearchHit> search(String query, String folder) {
        if (query == null || query.isBlank()) {
            throw new ObsidianException("Es fehlt der Suchbegriff.");
        }
        String needle = query.strip().toLowerCase(Locale.GERMAN);
        List<SearchHit> hits = new ArrayList<>();

        for (NoteInfo note : listAll(folder)) {
            Path file = file(note.path());
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("Notiz {} liess sich bei der Suche nicht lesen: {}", note.path(), e.getMessage());
                continue;
            }
            // Auch der Dateiname zaehlt: Wer nach "Satellite" sucht, meint oft die Notiz selbst.
            if (note.path().toLowerCase(Locale.GERMAN).contains(needle)) {
                hits.add(new SearchHit(note.path(), 0, firstLine(lines)));
            }
            for (int index = 0; index < lines.size() && hits.size() < properties.maxResults(); index++) {
                if (lines.get(index).toLowerCase(Locale.GERMAN).contains(needle)) {
                    hits.add(new SearchHit(note.path(), index + 1, snippet(lines.get(index))));
                    break;  // Eine Fundstelle je Notiz reicht, um sie zum Lesen vorzuschlagen.
                }
            }
            if (hits.size() >= properties.maxResults()) {
                break;
            }
        }
        return hits;
    }

    // --------------------------------------------------------------- schreiben

    /** Legt eine neue Notiz an; eine bestehende wird nicht ueberschrieben. */
    String create(String path, String content) {
        Path file = writable(VaultPath.resolve(root(), path, true));
        if (Files.exists(file)) {
            throw new ObsidianException(("\"%s\" gibt es bereits. Zum Ergaenzen append_note "
                    + "verwenden, zum Ueberarbeiten replace_section.").formatted(path));
        }
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new ObsidianException("Der Ordner liess sich nicht anlegen: " + e.getMessage(), e);
        }
        write(file, content.endsWith("\n") ? content : content + "\n");
        return VaultPath.relative(root(), file);
    }

    /** Haengt an eine bestehende Notiz an - mit Leerzeile dazwischen, damit Bloecke getrennt bleiben. */
    String append(String path, String content) {
        Path file = writable(file(path));
        String existing = readText(file);
        String separator = existing.isBlank() || existing.endsWith("\n\n") ? "" : existing.endsWith("\n") ? "\n" : "\n\n";
        backup(file, existing);
        write(file, existing + separator + (content.endsWith("\n") ? content : content + "\n"));
        return VaultPath.relative(root(), file);
    }

    /**
     * Ersetzt den Inhalt eines Abschnitts, die Ueberschrift bleibt stehen.
     *
     * <p>Genau das ist der Handgriff zum <em>Verfeinern</em>: Anhaengen allein reicht nicht, wenn
     * eine Notiz gemeinsam ueberarbeitet wird.
     *
     * @param stand die beim Lesen genannte Kennung; weicht sie ab, wurde die Notiz zwischenzeitlich
     *              geaendert und es wird nichts ueberschrieben
     */
    String replaceSection(String path, String heading, String content, String stand) {
        Path file = writable(file(path));
        String existing = readText(file);
        requireUnchanged(path, existing, stand);

        List<String> lines = existing.lines().toList();
        int start = headingIndex(lines, heading);
        if (start < 0) {
            throw new ObsidianException(("In \"%s\" gibt es keine Ueberschrift \"%s\". Vorhanden sind: %s")
                    .formatted(path, heading, String.join(", ", headings(lines))));
        }
        int level = level(lines.get(start));
        int end = start + 1;
        while (end < lines.size() && !(level(lines.get(end)) > 0 && level(lines.get(end)) <= level)) {
            end++;
        }

        StringBuilder updated = new StringBuilder();
        lines.subList(0, start + 1).forEach(line -> updated.append(line).append('\n'));
        updated.append('\n').append(content.strip()).append('\n');
        if (end < lines.size()) {
            updated.append('\n');
            lines.subList(end, lines.size()).forEach(line -> updated.append(line).append('\n'));
        }

        backup(file, existing);
        write(file, updated.toString());
        return VaultPath.relative(root(), file);
    }

    // ------------------------------------------------------------------ innen

    Path root() {
        return properties.root();
    }

    /** Der Pfad, sofern er im beschreibbaren Teil liegt - sonst mit Hinweis abgelehnt. */
    private Path writable(Path file) {
        VaultPath.ensureWritable(root(), properties.writeRoot(), file);
        return file;
    }

    private Path file(String path) {
        Path file = VaultPath.resolve(root(), path, true);
        if (VaultPath.denied(root(), file, properties.deniedFolders())) {
            throw new ObsidianException("\"%s\" liegt in einem gesperrten Ordner.".formatted(path));
        }
        if (!Files.isRegularFile(file)) {
            throw new ObsidianException(("Die Notiz \"%s\" gibt es nicht - mit list_notes oder "
                    + "search_notes nachsehen, wie sie wirklich heisst.").formatted(path));
        }
        return file;
    }

    private String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ObsidianException("Die Notiz liess sich nicht lesen: " + e.getMessage(), e);
        }
    }

    /**
     * Schreibt atomar: erst in eine Nachbardatei, dann umbenennen.
     *
     * <p>Die temporaere Datei liegt bewusst im selben Ordner - ein Umbenennen ueber Dateisysteme
     * hinweg ist nicht atomar.
     */
    private void write(Path file, String content) {
        Path temporary = file.resolveSibling(file.getFileName() + ".jarvis-tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ObsidianException("Die Notiz liess sich nicht schreiben: " + e.getMessage(), e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException e) {
                log.warn("Temporaere Datei {} blieb liegen: {}", temporary, e.getMessage());
            }
        }
    }

    /**
     * Legt die bisherige Fassung beiseite, bevor sie ueberschrieben wird.
     *
     * <p>Das ist der Grund, weshalb JARVIS ohne Rueckfrage schreiben darf: Nichts geht verloren, und
     * eine missratene Aenderung laesst sich von Hand zurueckholen. Scheitert die Sicherung, wird
     * <em>nicht</em> geschrieben - dann lieber gar keine Aenderung als eine unumkehrbare.
     */
    private void backup(Path file, String content) {
        Path directory = root().resolve(VaultPath.HISTORY_DIR);
        String name = "%s-%s".formatted(
                VaultPath.relative(root(), file).replace('/', '_'),
                LocalDateTime.now().format(STAMP));
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(name), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ObsidianException(
                    "Die Vorgaengerfassung liess sich nicht sichern, deshalb wurde nichts geaendert: "
                            + e.getMessage(), e);
        }
    }

    private void requireUnchanged(String path, String existing, String stand) {
        if (stand == null || stand.isBlank()) {
            throw new ObsidianException("Es fehlt der Stand aus read_note - ohne ihn wird nichts ersetzt.");
        }
        String current = stand(existing);
        if (!current.equalsIgnoreCase(stand.strip())) {
            throw new ObsidianException(("\"%s\" wurde inzwischen geaendert (Stand %s statt %s). "
                    + "Notiz erneut lesen und die Aenderung darauf aufsetzen.")
                    .formatted(path, current, stand.strip()));
        }
    }

    private List<NoteInfo> listAll(String folder) {
        Path directory = folder == null || folder.isBlank()
                ? root()
                : VaultPath.resolve(root(), folder, false);
        try (Stream<Path> entries = Files.walk(directory)) {
            return entries.filter(Files::isRegularFile)
                    .filter(Vault::isNote)
                    .filter(file -> !hidden(directory, file))
                    .filter(file -> !VaultPath.denied(root(), file, properties.deniedFolders()))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(this::info)
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            throw new ObsidianException("Der Ordner liess sich nicht durchsuchen: " + e.getMessage(), e);
        }
    }

    private NoteInfo info(Path file) {
        try {
            return new NoteInfo(VaultPath.relative(root(), file), Files.size(file),
                    Files.getLastModifiedTime(file).toInstant());
        } catch (IOException e) {
            return new NoteInfo(VaultPath.relative(root(), file), 0, Instant.EPOCH);
        }
    }

    private static boolean isNote(Path file) {
        return file.getFileName().toString().endsWith(".md");
    }

    /** Versteckte Ordner - {@code .obsidian}, {@code .git}, die eigene Sicherungsablage. */
    private static boolean hidden(Path base, Path file) {
        for (Path element : base.relativize(file)) {
            if (element.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private String snippet(String line) {
        String cleaned = line.strip();
        return cleaned.length() <= properties.snippetChars()
                ? cleaned
                : cleaned.substring(0, properties.snippetChars() - 1) + "…";
    }

    private String firstLine(List<String> lines) {
        return lines.stream().filter(line -> !line.isBlank()).findFirst().map(this::snippet).orElse("");
    }

    private static int level(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        return level > 0 && level < line.length() && line.charAt(level) == ' ' ? level : 0;
    }

    private static int headingIndex(List<String> lines, String heading) {
        String wanted = heading.strip().replaceAll("^#+\\s*", "").toLowerCase(Locale.GERMAN);
        for (int index = 0; index < lines.size(); index++) {
            int level = level(lines.get(index));
            if (level > 0 && lines.get(index).substring(level).strip().toLowerCase(Locale.GERMAN).equals(wanted)) {
                return index;
            }
        }
        return -1;
    }

    private static List<String> headings(List<String> lines) {
        return lines.stream()
                .filter(line -> level(line) > 0)
                .map(line -> line.substring(level(line)).strip())
                .toList();
    }

    /** Kurze Kennung des Inhalts - genug, um eine zwischenzeitliche Aenderung zu bemerken. */
    private static String stand(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt in dieser JVM", e);
        }
    }
}
