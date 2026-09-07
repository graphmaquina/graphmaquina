package io.stategraph.dsl;

import io.stategraph.error.InvalidGraphException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Collects every problem found while assembling a graph and sorts them by code, row and column. */
final class Problems {

    /** One recorded problem, before sorting: a code, its cell (or -1, -1 when row-level), and a message. */
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
