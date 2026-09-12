package io.github.yannicks99.jarvis_mcp.common;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.SequencedSet;

/**
 * Vereinheitlicht Namen, die aus einer gesprochenen Aeusserung stammen, damit sie gegen die in
 * Home Assistant hinterlegten Anzeigenamen gematcht werden koennen.
 *
 * <p>Die Namen kommen ueber Spracherkennung und ein kleines lokales Modell herein - Gross-/
 * Kleinschreibung, Bindestriche und die Schreibweise von Umlauten ("Buero" vs. "Büro") sind dabei
 * nicht verlaesslich. Deshalb wird jeder Name auf eine kanonische Form reduziert und zusaetzlich
 * eine Umlaut-freie Variante erzeugt; beide werden im Index als Schluessel hinterlegt, sodass die
 * spaetere Suche ein reiner Hash-Zugriff bleibt statt eines Vergleichs ueber alle Entitaeten.
 */
public final class NameNormalizer {

    private NameNormalizer() {
    }

    /**
     * Kanonische Form: Kleinbuchstaben, Umlaute ausgeschrieben ("ü" wird zu "ue"), alles andere
     * ausser Buchstaben und Ziffern zu einem einzelnen Leerzeichen zusammengezogen.
     */
    public static String canonical(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        StringBuilder expanded = new StringBuilder(lower.length() + 4);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            switch (c) {
                case 'ä' -> expanded.append("ae");
                case 'ö' -> expanded.append("oe");
                case 'ü' -> expanded.append("ue");
                case 'ß' -> expanded.append("ss");
                default -> expanded.append(c);
            }
        }
        return squash(expanded);
    }

    /**
     * Wie {@link #canonical(String)}, nur werden diakritische Zeichen entfernt statt ausgeschrieben
     * ("ü" wird zu "u"). Deckt die zweite gaengige Schreibweise ab, wenn jemand die Umlautpunkte
     * einfach weglaesst.
     */
    public static String stripped(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String decomposed = Normalizer.normalize(raw.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        StringBuilder withoutMarks = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK) {
                withoutMarks.append(c == 'ß' ? "ss" : String.valueOf(c));
            }
        }
        return squash(withoutMarks);
    }

    /**
     * Alle Schreibvarianten eines Namens - in dieser Reihenfolge zu probieren bzw. zu indizieren.
     * Die Menge ist bewusst klein (hoechstens zwei Eintraege) und behaelt die Reihenfolge bei.
     */
    public static SequencedSet<String> variants(String raw) {
        LinkedHashSet<String> variants = new LinkedHashSet<>(2);
        String canonical = canonical(raw);
        if (!canonical.isEmpty()) {
            variants.add(canonical);
        }
        String stripped = stripped(raw);
        if (!stripped.isEmpty()) {
            variants.add(stripped);
        }
        return variants;
    }

    /** Buchstaben und Ziffern behalten, alles andere zu einzelnen Leerzeichen. */
    private static String squash(CharSequence input) {
        StringBuilder out = new StringBuilder(input.length());
        boolean pendingSpace = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (pendingSpace && !out.isEmpty()) {
                    out.append(' ');
                }
                pendingSpace = false;
                out.append(c);
            } else {
                pendingSpace = true;
            }
        }
        return out.toString();
    }
}
