package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

import java.util.Set;

/**
 * Die Entitaeten, die in Home Assistant das Label {@value #LABEL} tragen (Anforderungskatalog
 * JARVIS-CacheDifferenzierung, Abschnitt 4).
 *
 * <p>Mit dem Label sagt man in Home Assistant zu, dass eine Entitaet bei jeder Ausloesung dasselbe
 * bewirkt. Gebraucht wird das fuer Routinen: {@code run_ha_routine} ist als Werkzeug nicht
 * idempotent, weil ein Skript auch etwas umschalten darf - eine einzelne Routine wie "Gute Nacht"
 * kann es trotzdem sein. Der JARVIS-AIService verwendet fuer solche Eintraege gelernte Antworten
 * wieder; fuer alle anderen spricht er nur den Ergebnistext des Werkzeugs. Home Assistant bleibt
 * damit die einzige Stelle, an der das gepflegt wird.
 *
 * <p>Eigener Typ statt {@code Set<String>}: Er steht als Bean bereit, und ein allgemeiner Typ wuerde
 * mit dem naechsten Modul, das ebenfalls eine Menge von Kennungen vorhaelt, mehrdeutig.
 */
record IdempotentEntities(Set<String> entityIds) {

    static final String LABEL = "jarvis-idempotent";

    static final IdempotentEntities NONE = new IdempotentEntities(Set.of());

    IdempotentEntities {
        entityIds = Set.copyOf(entityIds);
    }

    boolean contains(String entityId) {
        return entityIds.contains(entityId);
    }
}
