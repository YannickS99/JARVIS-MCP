package io.github.yannicks99.jarvis_mcp.tools.monitoring;

import java.util.Locale;
import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Der normalisierte Gesamtstatus einer Anwendung, wie das Monitoring Tool ihn meldet.
 *
 * <p>Die Wortwahl fuer die Antwort steht hier und nicht in den Werkzeugmethoden: Was die KI zu hoeren
 * bekommt, ist eine Eigenschaft des Zustands, nicht der Abfrage.
 */
public enum ApplicationState {

    /** Die Anwendung arbeitet ordnungsgemaess. */
    UP("laeuft"),

    /** Zwischenzustand - einzelne Teilkomponenten gestoert, oder der Container faehrt gerade hoch. */
    DEGRADED("gestoert"),

    /** Nicht erreichbar, oder die Anwendung meldet selbst einen Fehler. */
    DOWN("ausgefallen"),

    /** Noch kein Ergebnis vorhanden oder die Antwort war nicht deutbar. */
    UNKNOWN("unklar"),

    /** Ueber das Monitoring Tool gestoppt - bewusst kein Ausfall. */
    INACTIVE("absichtlich gestoppt");

    private final String description;

    ApplicationState(String description) {
        this.description = description;
    }

    /** Kurzform fuer die Antwort an die KI, z. B. "ausgefallen". */
    public String description() {
        return description;
    }

    /**
     * Ein Zustand, den diese Fassung nicht kennt, wird zu {@link #UNKNOWN} statt zu einem Fehler:
     * Kaeme im Monitoring Tool eine weitere Stufe dazu, soll daran nicht die gesamte Statusabfrage
     * scheitern - "unklar" ist dann sogar die zutreffende Auskunft.
     */
    @JsonCreator
    static ApplicationState from(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
