package io.github.graphmaquina.graphonly;

import static io.github.graphmaquina.fixtures.GraphOnlyFixture.Id.APPROVE;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.Id.AUTO_APPROVE;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.Id.BACK;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.Id.RETOUCH;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.State.DONE;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.State.OPEN;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.State.REVIEW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.export.GraphView;
import io.github.graphmaquina.export.GraphvizExporter;
import io.github.graphmaquina.export.PolicyView;
import io.github.graphmaquina.export.TransitionView;
import io.github.graphmaquina.fixtures.GraphOnlyFixture.Id;
import io.github.graphmaquina.fixtures.GraphOnlyFixture.State;
import io.github.graphmaquina.fixtures.GraphOnlyFixture.Ticket;
import io.github.graphmaquina.fixtures.GraphOnlyFixture;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GraphOnlyExportTest {

    @Test void exportContainsOnlyRealEdges() {
        GraphDefinition<Ticket,State,Id> graph = GraphOnlyFixture.builder()
            .transition(AUTO_APPROVE, REVIEW, DONE).endTransition().build();
        GraphView view = GraphView.of(graph);
        assertEquals(3, view.states().size());
        assertEquals(3, view.transitions().size());
        String dot = GraphvizExporter.render(view);
        assertTrue(dot.startsWith("digraph "));
        assertTrue(dot.contains("APPROVE"));
        assertTrue(dot.contains("AUTO_APPROVE"));
        assertFalse(dot.contains("forbid:"));
    }

    /** Parallel edges survive: each identifier keeps its own arrow and its own label. */
    @Test void parallelEdgesAreDrawnSeparately() {
        GraphDefinition<Ticket,State,Id> graph = GraphOnlyFixture.builder()
                .transition(AUTO_APPROVE, REVIEW, DONE).endTransition().build();
        String dot = GraphvizExporter.render(GraphView.of(graph));
        assertTrue(dot.contains("n1 -> n2 [label=\"APPROVE manual\"];"), dot);
        assertTrue(dot.contains("n1 -> n2 [label=\"AUTO_APPROVE\"];"), dot);
    }

    /** The order of the view is origin index, then identifier index, then destination index. */
    @Test void statesAndTransitionsFollowTheDeclaredOrder() {
        GraphDefinition<Ticket,State,Id> graph = GraphOnlyFixture.builder()
                .transition(AUTO_APPROVE, REVIEW, DONE).endTransition().build();
        GraphView view = GraphView.of(graph);
        assertEquals(List.of("OPEN", "REVIEW", "DONE"), view.states());
        assertEquals(List.of("SUBMIT", "APPROVE", "AUTO_APPROVE"),
                view.transitions().stream().map(TransitionView::transition).toList());
    }

    /** A policy is a node attribute, never an arrow to a state the request could not reach. */
    @Test void policiesAreNodeAttributesAndNeverEdges() {
        GraphDefinition<Ticket,State,Id> graph = GraphOnlyFixture.builder()
                .reject(OPEN, APPROVE, "approval requires review").build();
        GraphView view = GraphView.of(graph);
        PolicyView open = view.policies().get(0);
        assertEquals("OPEN", open.state());
        assertEquals("submit first", open.otherwise());
        assertEquals(List.of("APPROVE: approval requires review"), open.rejections());
        String dot = GraphvizExporter.render(view);
        assertTrue(dot.contains("tooltip=\"otherwise: submit first; APPROVE: approval requires review\""), dot);
        assertEquals(graph.transitions().size(),
                dot.lines().filter((String line) -> line.contains("->") && !line.contains("initial ->")).count(), dot);
    }

    /**
     * The reasons a state gives are rendered in the order they were declared. The three identifiers below are
     * declared in neither ordinal order (BACK is last in the catalogue, APPROVE second) nor any hash order, so
     * this fails if the chain reverts to an {@code EnumMap}, which sorts by ordinal, or to {@code Map.copyOf},
     * whose iteration order is unspecified and salted per JVM.
     */
    @Test void rejectionReasonsKeepTheirDeclaredOrderInTheRenderedTooltip() {
        GraphDefinition<Ticket,State,Id> graph = GraphOnlyFixture.builder()
                .reject(OPEN, BACK, "nothing to go back to")
                .reject(OPEN, APPROVE, "approval requires review")
                .reject(OPEN, RETOUCH, "nothing to retouch yet").build();
        GraphView view = GraphView.of(graph);
        assertEquals(List.of("BACK", "APPROVE", "RETOUCH"),
                graph.policy(OPEN).rejectionReasons().keySet().stream().map(Id::name).toList());
        assertEquals(List.of("BACK: nothing to go back to", "APPROVE: approval requires review",
                "RETOUCH: nothing to retouch yet"), view.policies().get(0).rejections());
        String dot = GraphvizExporter.render(view);
        assertTrue(dot.contains("tooltip=\"otherwise: submit first; BACK: nothing to go back to;"
                + " APPROVE: approval requires review; RETOUCH: nothing to retouch yet\""), dot);
    }

    /** A terminal gets the stable generated policy text when nobody declares one. */
    @Test void aTerminalCarriesTheGeneratedPolicyText() {
        GraphView view = GraphView.of(GraphOnlyFixture.builder().build());
        PolicyView done = view.policies().get(2);
        assertEquals("DONE", done.state());
        assertEquals("terminal state DONE accepts no transitions", done.otherwise());
        assertEquals(List.of(), done.rejections());
    }

    /** The identifier leads every label, and routing is rendered by identity rather than by destination. */
    @Test void labelsCarryIdentityActionGuardsAndRouting() {
        GraphDefinition<Ticket,State,Id> graph = GraphOnlyFixture.builder()
                .transition(AUTO_APPROVE, REVIEW, DONE)
                    .action("auto", ctx -> ActionOutcome.ok())
                    .guard("ready", ctx -> GuardResult.pass())
                    .onFailure(APPROVE).endTransition().build();
        TransitionView auto = GraphView.of(graph).transitions().get(2);
        assertEquals(Optional.of("auto"), auto.action());
        assertEquals(List.of("ready"), auto.guards());
        assertEquals(Optional.of("APPROVE"), auto.failureTransition());
        assertTrue(GraphvizExporter.render(GraphView.of(graph))
                .contains("[label=\"AUTO_APPROVE auto [if ready] [onFailure APPROVE]\"]"));
    }
}
