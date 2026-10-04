package io.github.graphmaquina.graphonly;

import static io.github.graphmaquina.fixtures.GraphOnlyFixture.Id.APPROVE;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.Id.AUTO_APPROVE;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.Id.RETOUCH;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.Id.SUBMIT;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.State.DONE;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.State.OPEN;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.State.REVIEW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.fixtures.GraphOnlyFixture.Id;
import io.github.graphmaquina.fixtures.GraphOnlyFixture.State;
import io.github.graphmaquina.fixtures.GraphOnlyFixture.Ticket;
import io.github.graphmaquina.fixtures.GraphOnlyFixture;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GraphOnlyDefinitionTest {

    @Test void definitionContainsOnlyDeclaredTransitions() {
        GraphDefinition<Ticket,State,Id> graph = GraphOnlyFixture.builder().build();
        assertEquals(2, graph.transitions().size());
        assertEquals(2, graph.graph().edges().size());
        assertTrue(graph.findTransition(OPEN, APPROVE).isEmpty());
        assertEquals(REVIEW, graph.findTransition(OPEN, SUBMIT).orElseThrow().to());
    }

    @Test void parallelEdgesAreNotDuplicates() {
        GraphDefinition<Ticket,State,Id> graph = GraphOnlyFixture.builder()
            .transition(AUTO_APPROVE, REVIEW, DONE).endTransition().build();
        assertEquals(2, graph.outgoingFrom(REVIEW).size());
        assertThrows(InvalidGraphException.class, () -> GraphOnlyFixture.builder()
            .transition(APPROVE, REVIEW, OPEN).endTransition().build());
    }

    @Test void everyDeclaredTransitionResolvesToTheExactSameSpecInstance() {
        GraphDefinition<Ticket,State,Id> graph = GraphOnlyFixture.builder().allowsCycle(REVIEW)
            .transition(AUTO_APPROVE, REVIEW, DONE).endTransition()
            .transition(RETOUCH, REVIEW, REVIEW).endTransition().build();
        for (TransitionSpec<Ticket,State,Id> spec : graph.transitions()) {
            assertSame(spec, graph.findTransition(spec.from(), spec.transition()).orElseThrow());
        }
    }

    @Test void absentLookupIsEmptyForAStateWithNoDeclaredTransitionsAtAll() {
        GraphDefinition<Ticket,State,Id> graph = GraphOnlyFixture.builder().build();
        assertEquals(Optional.empty(), graph.findTransition(DONE, SUBMIT));
    }

    @Test void selfLoopIsAnOrdinaryEdgeByIdentityAndEndpoints() {
        GraphDefinition<Ticket,State,Id> graph = GraphOnlyFixture.builder().allowsCycle(REVIEW)
            .transition(RETOUCH, REVIEW, REVIEW).endTransition().build();
        TransitionSpec<Ticket,State,Id> loop = graph.findTransition(REVIEW, RETOUCH).orElseThrow();
        assertEquals(REVIEW, loop.from());
        assertEquals(REVIEW, loop.to());
    }

    @Test void parallelEdgesAreDistinguishedByTransitionId() {
        GraphDefinition<Ticket,State,Id> graph = GraphOnlyFixture.builder()
            .transition(AUTO_APPROVE, REVIEW, DONE).endTransition().build();
        TransitionSpec<Ticket,State,Id> approve = graph.findTransition(REVIEW, APPROVE).orElseThrow();
        TransitionSpec<Ticket,State,Id> autoApprove = graph.findTransition(REVIEW, AUTO_APPROVE).orElseThrow();
        assertEquals(DONE, approve.to());
        assertEquals(DONE, autoApprove.to());
        assertNotSame(approve, autoApprove);
    }

    @Test void outgoingFromAndTransitionsAgreeOnDeclarationOrder() {
        GraphDefinition<Ticket,State,Id> graph = GraphOnlyFixture.builder().allowsCycle(REVIEW)
            .transition(AUTO_APPROVE, REVIEW, DONE).endTransition()
            .transition(RETOUCH, REVIEW, REVIEW).endTransition().build();
        assertEquals(List.of(SUBMIT, APPROVE, AUTO_APPROVE, RETOUCH),
            graph.transitions().stream().map(TransitionSpec::transition).toList());
        assertEquals(List.of(APPROVE, AUTO_APPROVE, RETOUCH),
            graph.outgoingFrom(REVIEW).stream().map(TransitionSpec::transition).toList());
    }
}
