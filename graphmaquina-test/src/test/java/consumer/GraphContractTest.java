package consumer;

import static io.github.graphmaquina.test.fixtures.TicketGraphFixture.Id.APPROVE;
import static io.github.graphmaquina.test.fixtures.TicketGraphFixture.Id.SUBMIT;
import static io.github.graphmaquina.test.fixtures.TicketGraphFixture.State.OPEN;
import static io.github.graphmaquina.test.fixtures.TicketGraphFixture.State.REVIEW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.test.GraphAssertions;
import io.github.graphmaquina.test.fixtures.TicketGraphFixture;
import io.github.graphmaquina.test.fixtures.TicketGraphFixture.Id;
import io.github.graphmaquina.test.fixtures.TicketGraphFixture.State;
import io.github.graphmaquina.test.fixtures.TicketGraphFixture.Ticket;
import io.github.graphmaquina.test.snapshot.GraphSnapshot;
import org.junit.jupiter.api.Test;

/**
 * The public contract of the testing surface, written from {@code consumer} so it exercises only what a
 * published artifact exposes: a graph is asserted by transition identity, a state's rejection policy is a
 * subject of its own, and the structural snapshot carries its format line and one path per edge field.
 */
class GraphContractTest {

    @Test
    void theGraphIsAssertedByTransitionIdentity() {
        GraphDefinition<Ticket, State, Id> graph = TicketGraphFixture.builder().build();
        GraphAssertions.assertThat(graph).hasNoTransition(OPEN, APPROVE)
                .hasTransitionsExactly(OPEN, SUBMIT).transition(OPEN, SUBMIT).hasTarget(REVIEW);
        GraphAssertions.assertThat(graph).policy(OPEN).hasDefaultReason("submit first");
    }

    @Test
    void theSnapshotCarriesItsFormatAndEdges() {
        GraphSnapshot snapshot = GraphSnapshot.capture(TicketGraphFixture.builder().build());
        assertTrue(snapshot.serialize().startsWith("graphmaquina-snapshot-v1\n"));
        assertEquals("REVIEW", snapshot.fields().get("/transitions/OPEN/SUBMIT/to"));
        assertEquals(snapshot.fields(), GraphSnapshot.parse(snapshot.serialize()).fields());
    }
}
