package io.github.yannicks99.jarvis_mcp.common;

import java.util.Locale;
import java.util.Optional;

/**
 * Ein gewuenschter An-/Aus-Zustand, wie er aus einer gesprochenen Aeusserung hereinkommt.
 *
 * <p>Liegt in {@code common}, weil mehr als ein Werkzeugmodul denselben Schalter anbietet - Lichter in
 * Home Assistant und Container im Monitoring Tool. Was die beiden daraus machen, unterscheidet sich
 * (ein Dienstaufruf hier, ein Container-Start dort); <em>welche Worte</em> als "an" und "aus" gelten
 * darf sich dagegen nicht unterscheiden.
 */
public enum Power {

    ON,
    OFF;

    /**
     * Nimmt die Schreibweisen entgegen, die ein kleines lokales Modell tatsaechlich produziert.
     *
     * <p>Vorgesehen sind laut Anforderungskatalog "on" und "off"; die Werte kommen aber aus einer
     * gesprochenen deutschen Aeusserung, die ein Modell der Groesse von Qwen/{@code flm} nicht immer
     * sauber uebersetzt. Eine Handvoll naheliegender Varianten zu akzeptieren kostet nichts und erspart
     * einen vermeidbaren Fehlversuch - die Zuverlaessigkeit des Tool-Callings unter {@code flm} ist
     * ohnehin das groesste bekannte Risiko der Gesamtarchitektur.
     */
    public static Optional<Power> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "on", "an", "ein", "einschalten", "true", "1", "start", "starten" -> Optional.of(ON);
            case "off", "aus", "ausschalten", "false", "0", "stop", "stopp", "stoppen" -> Optional.of(OFF);
            default -> Optional.empty();
        };
    }
}
