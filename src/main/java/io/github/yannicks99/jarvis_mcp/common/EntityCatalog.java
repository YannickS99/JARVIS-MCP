package io.github.yannicks99.jarvis_mcp.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Die ansprechbaren Namen eines Werkzeugmoduls - Inhalt der Entity-Resources (Anforderungskatalog
 * JARVIS-SemanticCache, Abschnitt 4).
 *
 * <p>Der JARVIS-AIService maskiert vor jedem Cache-Lookup die Namen in einer Aeusserung ("Mach das
 * Licht im Wohnzimmer aus" wird zu "Mach das Licht im {area} aus"), damit ein gelernter Befehl fuer
 * jeden Bereich gilt statt nur fuer den, in dem er zuerst gesprochen wurde. Die Namen dafuer kommen
 * von hier - aus demselben warmgehaltenen Stand, gegen den auch die Werkzeuge aufloesen. Eine zweite
 * Namenspflege im AIService wuerde unweigerlich auseinanderlaufen.
 *
 * <p>Liegt in {@code common}, weil jedes Werkzeugmodul seine Namen in genau dieser Form
 * veroeffentlicht. Der AIService erkennt die Resources am {@link #MIME_TYPE} und nicht an ihrer
 * URI: Ein neues Modul macht seine Namen bekannt, indem es eine weitere solche Resource anbietet -
 * im AIService ist dafuer nichts zu aendern.
 *
 * @param entities alle Eintraege des Moduls, in stabiler Reihenfolge
 */
public record EntityCatalog(List<Entry> entities) {

    /** Kennzeichnet eine Resource als Entity-Katalog; der AIService liest genau diese. */
    public static final String MIME_TYPE = "application/vnd.jarvis.entities+json";

    public EntityCatalog {
        entities = List.copyOf(entities);
    }

    /**
     * Ein ansprechbarer Name.
     *
     * @param type    Art des Eintrags. Wo ein Werkzeug den Namen entgegennimmt, ist das der Name
     *                seines Parameters ({@code area}, {@code light}, {@code application}) - daran
     *                erkennt der AIService, welcher Name in welches Argument gehoert.
     * @param name    Anzeigename, so wie ihn die Werkzeuge aufloesen
     * @param ref     interne Kennung, z. B. {@code area_id} oder {@code entity_id}; {@code null},
     *                wenn es keine gibt
     * @param aliases weitere Namen, unter denen der Eintrag gemeint sein kann
     * @param idempotent Zusage, dass genau dieser Eintrag bei jeder Ausloesung dasselbe bewirkt -
     *                   auch wenn das Werkzeug, das ihn entgegennimmt, das allgemein nicht zusagt
     *                   (Anforderungskatalog JARVIS-CacheDifferenzierung, 4). Der AIService darf
     *                   dann gelernte Antworten wiederverwenden. Steht nur im JSON, wenn gesetzt:
     *                   Fehlt es, gilt die Angabe des Werkzeugs.
     */
    public record Entry(String type, String name, String ref, List<String> aliases,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean idempotent) {

        public Entry {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }

        public Entry(String type, String name, String ref, List<String> aliases) {
            this(type, name, ref, aliases, false);
        }
    }
}
