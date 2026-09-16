package consumer;

import static io.github.graphmaquina.test.fixtures.GraphOnlyFixture.Id.APPROVE;
import static io.github.graphmaquina.test.fixtures.GraphOnlyFixture.Id.SUBMIT;
import static io.github.graphmaquina.test.fixtures.GraphOnlyFixture.State.OPEN;
import static io.github.graphmaquina.test.fixtures.GraphOnlyFixture.State.REVIEW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.test.GraphAssertions;
import io.github.graphmaquina.test.fixtures.GraphOnlyFixture;
import io.github.graphmaquina.test.fixtures.GraphOnlyFixture.Id;
import io.github.graphmaquina.test.fixtures.GraphOnlyFixture.State;
import io.github.graphmaquina.test.fixtures.GraphOnlyFixture.Ticket;
import io.github.graphmaquina.test.snapshot.GraphSnapshot;
import org.junit.jupiter.api.Test;

/**
 * The public contract of the graph-only testing surface, written from {@code consumer} so it exercises only
 * what a published artifact exposes: a graph is asserted by transition identity rather than by destination,
 * a state's rejection policy is a subject of its own, and the structural snapshot is version 2 with edge
 * paths and no matrix cells.
 */
class GraphOnlyContractTest {

    @Test
    void theGraphIsAssertedByTransitionIdentity() {
        GraphDefinition<Ticket, State, Id> graph = GraphOnlyFixture.builder().build();
        GraphAssertions.assertThat(graph).hasNoTransition(OPEN, APPROVE)
                .hasTransitionsExactly(OPEN, SUBMIT).transition(OPEN, SUBMIT).hasTarget(REVIEW);
        GraphAssertions.assertThat(graph).policy(OPEN).hasDefaultReason("submit first");
    }

    @Test
    void theSnapshotIsVersionTwoAndCarriesEdgesRatherThanCells() {
        GraphSnapshot snapshot = GraphSnapshot.capture(GraphOnlyFixture.builder().build());
        assertTrue(snapshot.serialize().startsWith("graphmaquina-snapshot-v2\n"));
        assertFalse(snapshot.fields().keySet().stream().anyMatch(path -> path.startsWith("/cells/")));
        assertEquals("REVIEW", snapshot.fields().get("/transitions/OPEN/SUBMIT/to"));
        assertThrows(IllegalArgumentException.class, () -> GraphSnapshot.parse("graphmaquina-snapshot-v1\n"));
        assertEquals(snapshot.fields(), GraphSnapshot.parse(snapshot.serialize()).fields());
    }
}
