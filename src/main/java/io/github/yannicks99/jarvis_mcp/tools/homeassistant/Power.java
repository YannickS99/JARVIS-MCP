package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

import java.util.Locale;
import java.util.Optional;

/** Gewuenschter Schaltzustand eines Lichts. */
public enum Power {

    ON("turn_on", "eingeschaltet"),
    OFF("turn_off", "ausgeschaltet");

    private final String service;
    private final String participle;

    Power(String service, String participle) {
        this.service = service;
        this.participle = participle;
    }

    /** Name des Home-Assistant-Dienstes, z. B. {@code turn_on}. */
    public String service() {
        return service;
    }

    /** Fuer die Rueckmeldung an die KI, z. B. "eingeschaltet". */
    public String participle() {
        return participle;
    }

    /**
     * Nimmt die Schreibweisen entgegen, die ein kleines lokales Modell tatsaechlich produziert.
     *
     * <p>Vorgesehen sind laut Anforderungskatalog "on" und "off"; die Werte kommen aber aus einer
     * gesprochenen deutschen Aeusserung, die ein Modell der Groesse von Qwen/{@code flm} nicht
     * immer sauber uebersetzt. Eine Handvoll naheliegender Varianten zu akzeptieren kostet nichts
     * und erspart einen vermeidbaren Fehlversuch - die Zuverlaessigkeit des Tool-Callings unter
     * {@code flm} ist ohnehin das groesste bekannte Risiko der Gesamtarchitektur.
     */
    public static Optional<Power> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "on", "an", "ein", "einschalten", "true", "1" -> Optional.of(ON);
            case "off", "aus", "ausschalten", "false", "0" -> Optional.of(OFF);
            default -> Optional.empty();
        };
    }
}
