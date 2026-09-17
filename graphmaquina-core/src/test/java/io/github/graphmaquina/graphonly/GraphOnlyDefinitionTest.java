package io.github.graphmaquina.graphonly;

import static io.github.graphmaquina.fixtures.GraphOnlyFixture.Id.APPROVE;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.Id.AUTO_APPROVE;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.Id.SUBMIT;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.State.DONE;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.State.OPEN;
import static io.github.graphmaquina.fixtures.GraphOnlyFixture.State.REVIEW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.fixtures.GraphOnlyFixture.Id;
import io.github.graphmaquina.fixtures.GraphOnlyFixture.State;
import io.github.graphmaquina.fixtures.GraphOnlyFixture.Ticket;
import io.github.graphmaquina.fixtures.GraphOnlyFixture;
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
}
