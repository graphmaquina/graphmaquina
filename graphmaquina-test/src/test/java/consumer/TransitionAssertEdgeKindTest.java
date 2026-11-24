package consumer;

import static consumer.GraphAssertionErrors.assertFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.test.GraphAssert;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.TransitionAssert;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import io.github.graphmaquina.test.snapshot.GraphSnapshot;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code isGate()} and {@code isExternal()} read the edge's marks off its spec: each passes on an edge of its kind and
 * fails, naming the edge, the mark and both values, on any other. A snapshot records each mark only where the graph
 * declares it, so a graph without gates or external edges captures what it always did.
 */
class TransitionAssertEdgeKindTest {

    private static final String GRAPH = Entity.class.getName() + "/" + State.class.getName();

    private static GraphDefinition<Entity, State, Id> graph() {
        return StateGraph.define(TestDomain.KEY).initial(State.NEW).terminal(State.DONE).terminal(State.FAILED)
                .statePolicy(State.NEW, "a new entity is prepared")
                .statePolicy(State.READY, "a ready entity finishes")
                .transition(Id.PREPARE, State.NEW, State.READY).endTransition()
                .transition(Id.RECOVER, State.READY, State.READY).gate().endTransition()
                .transition(Id.FINISH, State.READY, State.DONE).endTransition()
                .transition(Id.ABANDON, State.READY, State.FAILED).external().endTransition()
                .build();
    }

    @Test
    void aGateMatchesIsGateAndRefusesIsExternal() {
        TransitionAssert<Entity, State, Id> gate = GraphAssert.assertThat(graph()).transition(State.READY, Id.RECOVER);

        assertSame(gate, gate.isGate());
        assertFailure(assertThrows(GraphAssertionError.class, gate::isExternal), GRAPH + " READY -RECOVER->",
                "external", Boolean.TRUE, Boolean.FALSE);
    }

    @Test
    void anExternalEdgeMatchesIsExternalAndRefusesIsGate() {
        TransitionAssert<Entity, State, Id> external =
                GraphAssert.assertThat(graph()).transition(State.READY, Id.ABANDON);

        assertSame(external, external.isExternal());
        assertFailure(assertThrows(GraphAssertionError.class, external::isGate), GRAPH + " READY -ABANDON->",
                "gate", Boolean.TRUE, Boolean.FALSE);
    }

    @Test
    void anOrdinaryEdgeIsNeither() {
        TransitionAssert<Entity, State, Id> edge = GraphAssert.assertThat(graph()).transition(State.NEW, Id.PREPARE);

        assertFailure(assertThrows(GraphAssertionError.class, edge::isGate), GRAPH + " NEW -PREPARE->",
                "gate", Boolean.TRUE, Boolean.FALSE);
        assertFailure(assertThrows(GraphAssertionError.class, edge::isExternal), GRAPH + " NEW -PREPARE->",
                "external", Boolean.TRUE, Boolean.FALSE);
    }

    @Test
    void aSnapshotRecordsTheMarksOnlyWhereTheGraphDeclaresThem() {
        Map<String, String> fields = GraphSnapshot.capture(graph()).fields();

        assertEquals("true", fields.get("/transitions/READY/RECOVER/gate"));
        assertEquals("true", fields.get("/transitions/READY/ABANDON/external"));
        assertFalse(fields.containsKey("/transitions/READY/RECOVER/external"));
        assertFalse(fields.containsKey("/transitions/READY/ABANDON/gate"));
        assertFalse(fields.containsKey("/transitions/NEW/PREPARE/gate"));
        assertFalse(fields.containsKey("/transitions/NEW/PREPARE/external"));
    }
}
