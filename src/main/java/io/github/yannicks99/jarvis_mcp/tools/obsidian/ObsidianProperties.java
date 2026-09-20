package io.github.yannicks99.jarvis_mcp.tools.obsidian;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Konfiguration des Obsidian-Moduls (Anforderungskatalog JARVIS-Obsidian, Abschnitt 6).
 *
 * <p>Wie die uebrigen Module bringt dieses seine eigene Properties-Klasse mit; ein neues Modul
 * aendert nichts an den bestehenden.
 *
 * @param enabled       Schaltet das Modul zu. Vorgabe ist <strong>aus</strong> - anders als bei den
 *                      anderen Modulen: Ohne eingehaengten Vault-Ordner wuerde jedes Werkzeug
 *                      scheitern, und die KI soll Werkzeuge gar nicht erst sehen, die es hier nicht
 *                      gibt.
 * @param root          Der Ordner, den JARVIS lesen und beschreiben darf, aus Sicht des Containers.
 *                      In der Testphase ist das <em>nicht</em> die Vault-Wurzel, sondern nur ein
 *                      Unterordner - eingehaengt wird ohnehin nur dieser (siehe
 *                      {@code docker-compose.yml}), sodass der Rest des Vaults hier physisch gar
 *                      nicht existiert. Die Pfadpruefung in {@link VaultPath} ist die zweite
 *                      Absicherung.
 * @param writable      Ob schreibende Werkzeuge angeboten werden. Getrennt vom Zuschalten des
 *                      Moduls, damit sich der Vault auch nur lesend anbinden laesst.
 * @param maxNoteChars  Obergrenze fuer den Text, den {@code read_note} zurueckgibt. Eine Notiz
 *                      landet im Kontextfenster des Sprachmodells; eine ueberlange wird gekuerzt
 *                      statt abgelehnt, mit sichtbarem Hinweis.
 * @param maxResults    Wie viele Treffer bzw. Dateien eine Liste hoechstens nennt.
 * @param snippetChars  Laenge der Textstelle, die ein Suchtreffer mitliefert.
 */
@ConfigurationProperties(prefix = "jarvis-mcp.obsidian")
public record ObsidianProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("/srv/vault") Path root,
        @DefaultValue("true") boolean writable,
        @DefaultValue("60000") int maxNoteChars,
        @DefaultValue("40") int maxResults,
        @DefaultValue("240") int snippetChars) {

    /** Ohne vorhandenen Ordner kann kein einziger Aufruf gelingen. */
    public boolean usable() {
        return Files.isDirectory(root);
    }
}
