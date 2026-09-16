package io.github.graphmaquina.test;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.export.GraphView;
import io.github.graphmaquina.export.GraphvizExporter;
import io.github.graphmaquina.test.internal.Checks;
import io.github.graphmaquina.test.internal.GoldenStore;
import io.github.graphmaquina.test.internal.LineDiff;
import io.github.graphmaquina.test.snapshot.GoldenOptions;
import java.util.Objects;

/**
 * Compares the Graphviz DOT rendering of a graph against a checked-in golden resource, and rewrites that
 * resource when the run is in update mode. A mismatch fails with the lines that changed, not with both
 * diagrams in full, so a review reads what moved.
 *
 * <p>The rendering draws the declared edges and nothing else: parallel identifiers between the same pair of
 * states are separate arrows, and a rejection policy is an attribute of its node rather than an arrow. There
 * is no tabular or exhaustive mode, because absence has no diagram.
 *
 * <p>This checks visual output only. Use {@link GraphAssert} and {@link GraphSnapshots} for declared
 * contracts, and outcomes and observed state for machine behavior. Diagram text is not a semantic oracle.
 *
 * <p>The default options come from {@link GoldenOptions#defaults()} and therefore from the system
 * properties; passing options explicitly reads no property at all.
 */
public final class GraphGoldens {

    private GraphGoldens() { }

    /** Compares the rendering against {@code resource}, with the default options. */
    public static void assertMatches(GraphDefinition<?, ?, ?> definition, String resource) {
        assertMatches(definition, resource, GoldenOptions.defaults());
    }

    /**
     * @throws GraphAssertionError if the definition is null, the resource is missing, or its text differs
     *     from the rendering
     */
    public static void assertMatches(GraphDefinition<?, ?, ?> definition, String resource,
            GoldenOptions options) {
        GoldenStore.validateResource(resource);
        Objects.requireNonNull(options, "options");
        GraphDefinition<?, ?, ?> graph = Checks.subject(resource, "content", "matching golden", definition);
        GoldenStore.assertMatches(resource, render(graph), options, LineDiff::render);
    }

    private static <E, S extends Enum<S>, T extends Enum<T>> String render(GraphDefinition<E, S, T> definition) {
        return GraphvizExporter.render(GraphView.of(definition));
    }
}
