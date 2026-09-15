package io.github.yannicks99.jarvis_mcp.tools.monitoring;

import java.util.List;

/**
 * Ergebnis einer Namenssuche unter den hinterlegten Anwendungen. Ein Fehlgriff ist hier kein
 * Ausnahmefall, sondern ein erwartetes Ergebnis: Das Sprachmodell soll die Kandidatenliste sehen und es
 * mit einem passenderen Namen erneut versuchen koennen.
 */
public sealed interface ApplicationLookup {

    record Found(MonitoredApplication application) implements ApplicationLookup {
    }

    /** Kein Treffer - {@code available} nennt, was es stattdessen gibt. */
    record NotFound(List<String> available) implements ApplicationLookup {
    }

    /** Mehrere Teiltreffer - ohne Rueckfrage waere die Auswahl geraten. */
    record Ambiguous(List<String> candidates) implements ApplicationLookup {
    }
}
