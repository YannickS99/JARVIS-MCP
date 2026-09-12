package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

import java.util.List;

/**
 * Ergebnis einer Namenssuche im Entitaeten-Index. Ein Fehlgriff ist hier kein Ausnahmefall,
 * sondern ein erwartetes Ergebnis: Das Sprachmodell soll die Kandidatenliste sehen und es mit
 * einem passenderen Namen erneut versuchen koennen.
 */
public sealed interface EntityLookup {

    record Found(HomeAssistantEntity entity) implements EntityLookup {
    }

    /** Kein Treffer - {@code available} nennt, was es stattdessen gibt. */
    record NotFound(List<String> available) implements EntityLookup {
    }

    /** Mehrere Teiltreffer - ohne Rueckfrage waere die Auswahl geraten. */
    record Ambiguous(List<String> candidates) implements EntityLookup {
    }
}
