package io.github.graphmaquina.dsl;

import io.github.graphmaquina.error.InvalidGraphException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Collects every problem found while assembling a machine and sorts them by code, origin and identifier. */
final class Problems {

    /**
     * One recorded problem, before sorting: a code, the declaration index of the origin state and of the
     * transition identifier it concerns (-1 for either when the problem is not about that part), and a message.
     */
    private record Entry(String code, int from, int to, String message) { }

    private final List<Entry> entries = new ArrayList<>();

    void add(String code, int from, int to, String message) {
        entries.add(new Entry(code, from, to, message));
    }

    void add(String code, String message) {
        add(code, -1, -1, message);
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    List<InvalidGraphException.Problem> sorted() {
        List<Entry> copy = new ArrayList<>(entries);
        copy.sort(Comparator.comparing(Entry::code).thenComparingInt(Entry::from).thenComparingInt(Entry::to));
        List<InvalidGraphException.Problem> result = new ArrayList<>();
        for (Entry entry : copy) {
            result.add(new InvalidGraphException.Problem(entry.code(), entry.message()));
        }
        return List.copyOf(result);
    }
}
