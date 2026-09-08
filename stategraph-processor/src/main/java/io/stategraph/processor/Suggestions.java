package io.stategraph.processor;

import java.util.List;
import java.util.Optional;

/** "Did you mean" for state names, by edit distance. */
final class Suggestions {

    private static final int MAX_DISTANCE = 3;

    private Suggestions() { }

    static String describeUnknownState(String wrong, String enumName, List<String> valid) {
        Optional<String> closest = closest(wrong, valid);
        String hint = closest.map((String name) -> "; did you mean " + name + "?").orElse(".");
        return "unknown state \"" + wrong + "\" in " + enumName + hint + " Valid states: " + String.join(", ", valid);
    }

    static Optional<String> closest(String wrong, List<String> candidates) {
        Optional<String> best = Optional.empty();
        int bestDistance = MAX_DISTANCE + 1;
        for (String candidate : candidates) {
            int distance = distance(wrong.toUpperCase(), candidate.toUpperCase());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = Optional.of(candidate);
            }
        }
        return best;
    }

    /** Levenshtein distance. */
    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
