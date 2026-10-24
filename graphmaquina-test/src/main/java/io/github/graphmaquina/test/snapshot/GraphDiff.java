package io.github.graphmaquina.test.snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.NonNull;

/**
 * The difference between two snapshots, path by path, in canonical path order. A path missing from one
 * side is a change against the value on the other, and it stays distinguishable from a path present with
 * an empty value: both halves of a {@link Change} are optional.
 */
public final class GraphDiff {

    private final List<Change> changes;

    private GraphDiff(List<Change> changes) {
        this.changes = changes;
    }

    public static GraphDiff compare(GraphSnapshot expected, GraphSnapshot actual) {
        Map<String, String> left = Objects.requireNonNull(expected, "expected").fields();
        Map<String, String> right = Objects.requireNonNull(actual, "actual").fields();
        Set<String> paths = new TreeSet<>(left.keySet());
        paths.addAll(right.keySet());
        List<Change> changes = new ArrayList<>();
        for (String path : paths) {
            Optional<String> before = Optional.ofNullable(left.get(path));
            Optional<String> after = Optional.ofNullable(right.get(path));
            if (!before.equals(after)) {
                changes.add(new Change(path, before, after));
            }
        }
        return new GraphDiff(List.copyOf(changes));
    }

    public List<Change> changes() {
        return changes;
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    /**
     * One line per change, each terminated by a line feed, with the escapes of the snapshot format. A
     * present value is quoted — so an empty one reads as {@code ""} — and an absent one reads as
     * {@code <absent>}. A quote inside a value is escaped as {@code \"} as well, since the quotes are what
     * delimit it; the snapshot format itself does not escape it. An empty difference renders as the empty
     * string.
     */
    public String render() {
        StringBuilder text = new StringBuilder();
        for (Change change : changes) {
            text.append(SnapshotCodec.escape(change.path()))
                    .append(": expected ").append(render(change.expected()))
                    .append(" but was ").append(render(change.actual()))
                    .append('\n');
        }
        return text.toString();
    }

    private static String render(Optional<String> value) {
        return value.map((String present) ->
                "\"" + SnapshotCodec.escape(present).replace("\"", "\\\"") + "\"").orElse("<absent>");
    }

    /**
     * One differing path with the value each snapshot carries for it, empty where the path is absent.
     *
     * @param path the field path that differs
     * @param expected the value of the first snapshot, empty when it does not carry the path
     * @param actual the value of the second snapshot, empty when it does not carry the path
     */
    public record Change(@NonNull String path, @NonNull Optional<String> expected, @NonNull Optional<String> actual) {

        public Change {
        }
    }
}
