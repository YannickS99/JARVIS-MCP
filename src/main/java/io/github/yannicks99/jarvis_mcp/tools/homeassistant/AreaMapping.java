package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

import java.util.List;

/**
 * Ein Home-Assistant-Bereich mit den Namen, unter denen er angesprochen werden darf.
 *
 * <p>Bewusst eine Liste von Eintraegen statt einer Zuordnung Name → {@code area_id}, obwohl
 * letztere naeher an der Anforderung liegt: Spring bindet Schluessel einer Map ueber seinen
 * Property-Namensraum und wirft dabei alles weg, was kein Buchstabe und keine Ziffer ist - aus
 * {@code Büro:} wuerde still {@code Bro}, ohne Fehlermeldung. Als Wert in einer Liste kommt
 * derselbe Name unveraendert an. Nebeneffekt: Mehrere Aliasse pro Bereich stehen hier
 * beieinander, statt sich ueber mehrere Zeilen zu verteilen.
 *
 * @param id    die {@code area_id} aus Home Assistant
 * @param names Namen und Aliasse, unter denen der Bereich gemeint sein kann. Die {@code id} gilt
 *              immer zusaetzlich und muss nicht wiederholt werden.
 */
public record AreaMapping(String id, List<String> names) {

    public AreaMapping {
        names = names == null ? List.of() : List.copyOf(names);
    }
}
