package io.github.graphmaquina.test;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.test.internal.Checks;
import io.github.graphmaquina.test.internal.GoldenStore;
import io.github.graphmaquina.test.internal.LineDiff;
import io.github.graphmaquina.test.snapshot.GoldenOptions;
import io.github.graphmaquina.test.snapshot.GraphDiff;
import io.github.graphmaquina.test.snapshot.GraphSnapshot;
import java.util.Objects;

/**
 * Compares the structural snapshot of a graph against a checked-in golden resource, and rewrites that
 * resource when the run is in update mode. Where {@link GraphGoldens} pins what a diagram shows, this pins
 * everything the definition declares — transition identifiers, timeouts, recovery routes, guard modes, hooks,
 * policies — by name and value, never by the code behind a name, so a change no diagram would reveal still
 * breaks the build, and it fails with the paths that differ rather than with two files.
 *
 * <p>In read mode a stored snapshot of an unknown version or a broken format is a fixture problem, not a
 * mismatch: it is reported as such rather than compared. Update mode never reads the old text as a snapshot
 * at all — it replaces the file with a snapshot of the current version, whatever was there before — so an
 * unreadable snapshot an update run meets is rewritten rather than reported.
 *
 * <p>The comparison is over the stored text, while the report is over the fields, so a golden that carries
 * the very same fields in another line order differs without any field differing. That case falls back to
 * the line diff, which always names something, instead of failing with an empty message.
 */
public final class GraphSnapshots {

    private GraphSnapshots() { }

    /**
     * Compares against {@code resource} with the default options. A malformed resource name is reported before
     * the system properties are read, so it is never hidden by a mistake in them.
     */
    public static void assertMatches(GraphDefinition<?, ?, ?> definition, String resource) {
        GoldenStore.validateResource(resource);
        assertMatches(definition, resource, GoldenOptions.defaults());
    }

    /**
     * Compares the capture against {@code resource} through {@code options}. In update mode a missing or
     * different snapshot is written instead of failing the check.
     *
     * @throws GraphAssertionError if the definition is null or, in read mode, the resource is missing, names
     *     a directory or holds a snapshot that differs from the capture
     * @throws IllegalArgumentException if the resource name is malformed, if in read mode the stored snapshot
     *     is not readable, or if in update mode the resource resolves outside the source root
     * @throws IllegalStateException if the golden cannot be read or decoded, or cannot be written
     */
    public static void assertMatches(GraphDefinition<?, ?, ?> definition, String resource,
            GoldenOptions options) {
        GoldenStore.validateResource(resource);
        Objects.requireNonNull(options, "options");
        GraphDefinition<?, ?, ?> graph = Checks.subject(resource, "content", "matching golden", definition);
        GoldenStore.assertMatches(resource, GraphSnapshot.capture(graph).serialize(), options,
                (expected, produced) -> diff(resource, expected, produced));
    }

    private static String diff(String resource, String expected, String actual) {
        GraphSnapshot stored;
        try {
            stored = GraphSnapshot.parse(expected);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("the golden <" + resource + "> is not a readable snapshot: "
                    + malformed.getMessage(), malformed);
        }
        GraphDiff difference = GraphDiff.compare(stored, GraphSnapshot.parse(actual));
        return difference.isEmpty() ? LineDiff.render(expected, actual) : difference.render();
    }
}
