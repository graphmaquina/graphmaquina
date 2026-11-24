package io.github.graphmaquina.export;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.RETOUCH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocGraphs;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import org.junit.jupiter.api.Test;

/**
 * The DOT of a gate is a dotted self-loop whose label starts with {@code [gate]}, and that of an external edge a dashed
 * arrow whose label starts with {@code [external]}; a graph with neither draws no such style or part.
 */
class GraphvizEdgeKindsTest {

    private static GraphDefinition<Doc, DocStatus, DocTransition> graph() {
        return StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted")
                .statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "the archive retires a publication")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(RETOUCH, REVIEW, REVIEW).gate().endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).external().endTransition()
                .build();
    }

    private static String line(String dot, String identifier) {
        return dot.lines().filter((String line) -> line.contains("<b>" + identifier + "</b>")).findFirst()
                .orElseThrow(() -> new AssertionError("no edge " + identifier + " in\n" + dot));
    }

    @Test
    void aGateIsDottedAndMarked() {
        String gate = line(GraphvizExporter.render(GraphView.of(graph())), "RETOUCH");

        assertTrue(gate.contains("style=dotted"), gate);
        assertTrue(gate.contains("[gate]"), gate);
    }

    @Test
    void anExternalEdgeIsDashedAndMarked() {
        String external = line(GraphvizExporter.render(GraphView.of(graph())), "ARCHIVE");

        assertTrue(external.contains("style=dashed"), external);
        assertTrue(external.contains("[external]"), external);
    }

    @Test
    void aGraphWithNeitherDrawsNoSuchStyle() {
        String dot = GraphvizExporter.render(GraphView.of(DocGraphs.plain()));

        assertFalse(dot.contains("style=dotted"), dot);
        assertFalse(dot.contains("[gate]") || dot.contains("[external]"), dot);
    }
}
