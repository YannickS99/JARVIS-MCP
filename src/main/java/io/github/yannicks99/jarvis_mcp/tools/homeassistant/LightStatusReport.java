package io.github.yannicks99.jarvis_mcp.tools.homeassistant;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Formt Lichtzustaende zu deutschen Saetzen, aus denen das Sprachmodell seine Antwort baut.
 *
 * <p>Eigene Klasse, weil hier die einzige Stelle im Modul liegt, an der aus Daten Prosa wird - und
 * weil sie sich so ohne Home Assistant und ohne HTTP pruefen laesst. Die Saetze sind knapp und
 * gleichfoermig gehalten ("An: …", "Nicht erreichbar: …"): Der Leser ist ein kleines Modell, das
 * daraus einen gesprochenen Satz formuliert, nicht ein Mensch, der Prosa erwartet.
 *
 * <p>Ein Licht, das Home Assistant als {@code unavailable} meldet, wird eigens genannt statt
 * stillschweigend zu "aus" gezaehlt. Sonst sagt JARVIS "alles aus", obwohl in Wahrheit niemand
 * weiss, was die Lampe gerade tut.
 */
final class LightStatusReport {

    /** Sammelbegriff fuer Lichter, die in Home Assistant keinem Bereich zugeordnet sind. */
    private static final String WITHOUT_AREA = "ohne Bereich";

    private LightStatusReport() {
    }

    /** Der Blick aufs ganze Haus: was ist an, und welche Bereiche sind vollstaendig dunkel. */
    static String overview(List<HomeAssistantLightStatus> lights) {
        if (lights.isEmpty()) {
            return "In Home Assistant ist kein Licht eingerichtet.";
        }

        List<HomeAssistantLightStatus> on = filter(lights, HomeAssistantLightStatus::on);
        List<HomeAssistantLightStatus> unreachable = filter(lights, HomeAssistantLightStatus::unreachable);
        int reachable = lights.size() - unreachable.size();

        StringBuilder text = new StringBuilder();
        if (reachable == 0) {
            text.append("Zurzeit ist kein Licht erreichbar.");
        } else if (on.isEmpty()) {
            text.append("Im ganzen Haus ist kein Licht an.");
        } else {
            text.append("Es %s %d von %s an. An: %s."
                    .formatted(on.size() == 1 ? "ist" : "sind", on.size(), lightsDative(reachable),
                            groupedByArea(on)));

            List<String> dark = darkAreas(lights);
            if (!dark.isEmpty()) {
                text.append(dark.size() == 1
                        ? " Ganz aus ist der Bereich %s.".formatted(dark.getFirst())
                        : " Ganz aus sind die Bereiche %s.".formatted(String.join(", ", dark)));
            }
        }

        appendUnreachable(text, unreachable);
        return text.toString();
    }

    /** Der Blick auf einen einzelnen Bereich - hier werden auch die ausgeschalteten Lichter genannt. */
    static String forArea(String areaName, List<HomeAssistantLightStatus> lights) {
        if (lights.isEmpty()) {
            return "Im Bereich '%s' ist kein Licht eingerichtet.".formatted(areaName);
        }

        List<HomeAssistantLightStatus> on = filter(lights, HomeAssistantLightStatus::on);
        List<HomeAssistantLightStatus> off = filter(lights, HomeAssistantLightStatus::off);
        List<HomeAssistantLightStatus> unreachable = filter(lights, HomeAssistantLightStatus::unreachable);

        StringBuilder text = new StringBuilder();
        if (on.isEmpty()) {
            text.append(off.isEmpty()
                    ? "Im Bereich '%s' ist zurzeit kein Licht erreichbar.".formatted(areaName)
                    : "Im Bereich '%s' ist kein Licht an.".formatted(areaName));
        } else {
            text.append("Im Bereich '%s' %s an: %s."
                    .formatted(areaName,
                            on.size() == 1 ? "ist ein Licht" : "sind %d Lichter".formatted(on.size()),
                            names(on)));
            if (!off.isEmpty()) {
                text.append(" Aus: %s.".formatted(names(off)));
            }
        }

        appendUnreachable(text, unreachable);
        return text.toString();
    }

    /** Die Antwort auf "ist Licht X an?". */
    static String single(HomeAssistantLightStatus light) {
        String where = light.hasArea() ? " im Bereich '%s'".formatted(light.areaName()) : "";
        if (light.on()) {
            return "Das Licht '%s'%s ist an.".formatted(light.name(), where);
        }
        if (light.off()) {
            return "Das Licht '%s'%s ist aus.".formatted(light.name(), where);
        }
        // Der Rohzustand steht bewusst dabei: "unavailable" (Geraet weg) und "unknown" (noch nie
        // gemeldet) haben verschiedene Ursachen, und die KI soll nicht raten muessen, welche.
        return "Das Licht '%s'%s ist fuer Home Assistant gerade nicht erreichbar (Zustand: %s)."
                .formatted(light.name(), where, light.state());
    }

    /** {@code "Wohnzimmer: Stehlampe, Deckenlampe; Büro: Schreibtischlampe"} */
    private static String groupedByArea(List<HomeAssistantLightStatus> lights) {
        // TreeMap und sortierte Namen, damit dieselbe Lage immer denselben Satz ergibt - sonst
        // klingt zweimal dieselbe Frage nach zwei verschiedenen Antworten.
        Map<String, List<String>> byArea = new TreeMap<>();
        for (HomeAssistantLightStatus light : lights) {
            byArea.computeIfAbsent(areaLabel(light), key -> new ArrayList<>()).add(light.name());
        }

        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, List<String>> area : byArea.entrySet()) {
            if (!text.isEmpty()) {
                text.append("; ");
            }
            List<String> sorted = new ArrayList<>(area.getValue());
            sorted.sort(null);
            text.append(area.getKey()).append(": ").append(String.join(", ", sorted));
        }
        return text.toString();
    }

    /**
     * Bereiche, in denen nachweislich alles aus ist.
     *
     * <p>Ein Bereich, aus dem nur nicht erreichbare Lichter gemeldet werden, gehoert nicht dazu -
     * ueber den ist gerade nichts bekannt.
     */
    private static List<String> darkAreas(List<HomeAssistantLightStatus> lights) {
        Set<String> withLightOn = new HashSet<>();
        Set<String> withLightOff = new TreeSet<>();
        for (HomeAssistantLightStatus light : lights) {
            if (light.on()) {
                withLightOn.add(areaLabel(light));
            } else if (light.off()) {
                withLightOff.add(areaLabel(light));
            }
        }
        withLightOff.removeAll(withLightOn);
        return List.copyOf(withLightOff);
    }

    private static void appendUnreachable(StringBuilder text, List<HomeAssistantLightStatus> unreachable) {
        if (!unreachable.isEmpty()) {
            text.append(" Nicht erreichbar: %s.".formatted(names(unreachable)));
        }
    }

    private static String areaLabel(HomeAssistantLightStatus light) {
        return light.hasArea() ? light.areaName() : WITHOUT_AREA;
    }

    private static String names(List<HomeAssistantLightStatus> lights) {
        return lights.stream().map(HomeAssistantLightStatus::name).sorted()
                .reduce((left, right) -> left + ", " + right).orElse("");
    }

    private static List<HomeAssistantLightStatus> filter(List<HomeAssistantLightStatus> lights,
            Predicate<HomeAssistantLightStatus> matches) {
        return lights.stream().filter(matches).toList();
    }

    /** "3 von <b>12 Lichtern</b>" - der Dativ stimmt sonst im Einzahlfall nicht. */
    private static String lightsDative(int count) {
        return count == 1 ? "1 Licht" : count + " Lichtern";
    }
}
