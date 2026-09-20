package io.github.yannicks99.jarvis_mcp.tools.obsidian;

/**
 * Ein Zugriff auf den Vault ist nicht moeglich - falscher Pfad, fehlende Notiz, Ordner nicht
 * eingehaengt.
 *
 * <p>Die Meldung ist fuer das Sprachmodell gedacht und sagt deshalb, was stattdessen zu tun ist
 * („Pfade beginnen ohne Schraegstrich", „vorher mit list_notes nachsehen"). Geworfen wird nur, wenn
 * der Aufruf gar nicht auszufuehren war; alles, womit die KI weiterarbeiten kann, kommt als Text
 * zurueck.
 */
public class ObsidianException extends RuntimeException {

    public ObsidianException(String message) {
        super(message);
    }

    public ObsidianException(String message, Throwable cause) {
        super(message, cause);
    }
}
