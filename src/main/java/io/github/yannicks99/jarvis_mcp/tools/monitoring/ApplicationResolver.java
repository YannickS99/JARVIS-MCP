package io.github.yannicks99.jarvis_mcp.tools.monitoring;

import io.github.yannicks99.jarvis_mcp.common.NameNormalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loest einen gesprochenen Anwendungsnamen gegen die im Monitoring Tool hinterlegten Namen auf.
 *
 * <p>Genau dafuer nimmt der Nutzer den Anwendungsnamen und nicht den Containernamen: "Monetheus" statt
 * "monetheus-backend-1". Die Toleranz ist dieselbe wie bei den Home-Assistant-Werkzeugen - Gross-/
 * Kleinschreibung, Bindestriche und Umlautschreibweise sind egal, und ein verkuerzter Name trifft,
 * solange er eindeutig bleibt.
 *
 * <p>Bewusst hier und nicht im Monitoring Tool: Dort steht ein Dienst mit Kennungen, hier steht der
 * Nutzer mit einer Aeusserung. Die Rueckfrage bei Mehrdeutigkeit gehoert an das Ende, an dem sie
 * gestellt werden kann.
 *
 * <p>Bewusst ohne Zwischenspeicher: Die Namen kommen aus derselben Antwort wie die Zustaende, und die
 * darf nie zwischengespeichert werden. Anders als bei Home Assistant kostet das nichts - der Aufruf
 * geht an einen Dienst im selben Docker-Netz und liefert ein paar Kilobyte.
 */
public final class ApplicationResolver {

    /** Wie viele Namen einer Fehlermeldung beigelegt werden, damit die Antwort lesbar bleibt. */
    private static final int MAX_SUGGESTIONS = 25;

    private ApplicationResolver() {
    }

    /**
     * Sucht die Anwendung zu einem Namen.
     *
     * @param applications die hinterlegten Anwendungen, so wie das Monitoring Tool sie gemeldet hat
     * @param name         der gesprochene Name
     */
    public static ApplicationLookup resolve(List<MonitoredApplication> applications, String name) {
        // Reihenfolge festzurren, damit bei zwei gleich benannten Anwendungen immer dieselbe gewinnt.
        // (Das Monitoring Tool haelt Namen eindeutig; die Sortierung macht das Verhalten trotzdem
        // unabhaengig davon, in welcher Reihenfolge es sie ausliefert.)
        List<MonitoredApplication> sorted = new ArrayList<>(applications);
        sorted.sort((left, right) -> Long.compare(idOf(left), idOf(right)));

        Map<String, MonitoredApplication> byKey = HashMap.newHashMap(sorted.size() * 2);
        for (MonitoredApplication application : sorted) {
            for (String variant : NameNormalizer.variants(application.name())) {
                byKey.putIfAbsent(variant, application);
            }
        }

        for (String variant : NameNormalizer.variants(name)) {
            MonitoredApplication hit = byKey.get(variant);
            if (hit != null) {
                return new ApplicationLookup.Found(hit);
            }
        }
        return partialMatch(sorted, NameNormalizer.canonical(name));
    }

    /**
     * Zweiter Versuch ueber Teilstrings: deckt ab, dass gesprochene Namen kuerzer ausfallen als der
     * eingetragene ("Monetheus" statt "Monetheus Backend"). Bleibt nur ein Treffer uebrig, ist die
     * Sache eindeutig; bei mehreren wird bewusst nicht geraten.
     */
    private static ApplicationLookup partialMatch(List<MonitoredApplication> applications, String needle) {
        if (needle.isEmpty()) {
            return new ApplicationLookup.NotFound(names(applications));
        }
        List<MonitoredApplication> matches = new ArrayList<>(2);
        for (MonitoredApplication application : applications) {
            if (application.normalized().contains(needle)) {
                matches.add(application);
            }
        }
        return switch (matches.size()) {
            case 0 -> new ApplicationLookup.NotFound(names(applications));
            case 1 -> new ApplicationLookup.Found(matches.getFirst());
            default -> new ApplicationLookup.Ambiguous(
                    matches.stream().map(MonitoredApplication::name).toList());
        };
    }

    /** Namen der hinterlegten Anwendungen, gedeckelt fuer Fehlermeldungen. */
    private static List<String> names(List<MonitoredApplication> applications) {
        return applications.stream()
                .map(MonitoredApplication::name)
                .limit(MAX_SUGGESTIONS)
                .toList();
    }

    /** Anwendungen ohne Kennung sortieren nach hinten, statt die Sortierung platzen zu lassen. */
    private static long idOf(MonitoredApplication application) {
        return application.id() == null ? Long.MAX_VALUE : application.id();
    }
}
