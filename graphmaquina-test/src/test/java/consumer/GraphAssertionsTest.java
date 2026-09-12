package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.test.GraphAssert;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Contract of {@link GraphAssert}. Every expectation gets a match and a mismatch read off the fields of
 * the {@link GraphAssertionError}, every method gets its own null-subject case, and the cell selector is
 * pinned against the ordinal indexing of the matrix.
 */
class GraphAssertionsTest {

    /** State enum of a second machine, used as the state of a graph that declares a watch. */
    enum WatchState { WAITING, DONE }

    /** An enum that belongs to no graph under test, used to reach the matrix through raw types. */
    enum Foreign { OTHER }

    /** States of a graph that cycles for ever and therefore declares no terminal. */
    enum Cycle { A, B }

    private static final Duration TIMEOUT = Duration.ofSeconds(1);
    private static final String GRAPH = Entity.class.getName() + "/" + State.class.getName();

    // --- terminals ---------------------------------------------------------------------------------

    @Test
    void terminalExpectationDoesNotAcceptAnExtraTerminal() {
        GraphDefinition<Entity, State> graph = TestDomain.graph(Duration.ofSeconds(1));
        assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(graph).hasTerminalsExactly(State.DONE));
        GraphAssert.assertThat(graph).hasTerminalsExactly(State.DONE, State.FAILED);
    }

    @Test
    void aMissingTerminalIsReportedAsASetMismatch() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT)).hasTerminalsExactly(State.DONE));
        assertFailure(error, GRAPH, "terminals", Set.of(State.DONE), Set.of(State.DONE, State.FAILED));
    }

    @Test
    void anUnexpectedTerminalIsReportedAsASetMismatch() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .hasTerminalsExactly(State.DONE, State.FAILED, State.READY));
        assertFailure(error, GRAPH, "terminals", Set.of(State.DONE, State.FAILED, State.READY),
                Set.of(State.DONE, State.FAILED));
    }

    @Test
    void terminalsAreComparedAsASetSoOrderAndRepetitionDoNotMatter() {
        GraphAssert<Entity, State> graph = GraphAssert.assertThat(TestDomain.graph(TIMEOUT));
        assertSame(graph, graph.hasTerminalsExactly(State.FAILED, State.DONE));
        graph.hasTerminalsExactly(State.DONE, State.FAILED, State.DONE);
    }

    @Test
    void aGraphWithoutTerminalsMatchesAnEmptyExpectation() {
        GraphAssert.assertThat(graphWithoutTerminals()).hasTerminalsExactly();
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT)).hasTerminalsExactly());
        assertFailure(error, GRAPH, "terminals", Set.of(), Set.of(State.DONE, State.FAILED));
    }

    @Test
    void aGraphWithoutTerminalsDoesNotMatchATerminalExpectation() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(graphWithoutTerminals()).hasTerminalsExactly(Cycle.B));
        assertEquals("terminals", error.property());
        assertEquals(Set.of(Cycle.B), error.expected());
        assertEquals(Set.of(), error.actual());
    }

    // --- initial -----------------------------------------------------------------------------------

    @Test
    void theInitialStateIsComparedExactly() {
        GraphAssert<Entity, State> graph = GraphAssert.assertThat(TestDomain.graph(TIMEOUT));
        assertSame(graph, graph.hasInitial(State.NEW));
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> graph.hasInitial(State.READY));
        assertFailure(error, GRAPH, "initial", State.READY, State.NEW);
    }

    // --- allowed targets ---------------------------------------------------------------------------

    @Test
    void allowedTargetsCoverActionAndMoveCellsOnly() {
        GraphAssert<Entity, State> graph = GraphAssert.assertThat(TestDomain.graph(TIMEOUT));
        assertSame(graph, graph.hasAllowedTargetsExactly(State.NEW, State.READY, State.FAILED));
        graph.hasAllowedTargetsExactly(State.READY, State.DONE);
        graph.hasAllowedTargetsExactly(State.DONE);
    }

    @Test
    void aMissingAllowedTargetIsReportedAgainstItsOwnRow() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .hasAllowedTargetsExactly(State.NEW, State.READY));
        assertFailure(error, GRAPH + " NEW", "allowed targets", Set.of(State.READY),
                Set.of(State.READY, State.FAILED));
    }

    @Test
    void aTerminalRowAllowsNothing() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .hasAllowedTargetsExactly(State.DONE, State.NEW));
        assertFailure(error, GRAPH + " DONE", "allowed targets", Set.of(State.NEW), Set.of());
    }

    // --- asynchrony --------------------------------------------------------------------------------

    @Test
    void asynchronyIsReadOffTheDefinition() {
        GraphAssert<Entity, State> asynchronous = GraphAssert.assertThat(TestDomain.graph(TIMEOUT));
        assertSame(asynchronous, asynchronous.hasAsync());
        GraphAssert<Entity, State> synchronous = GraphAssert.assertThat(synchronousGraph());
        assertSame(synchronous, synchronous.hasNoAsync());
        assertFailure(assertThrows(GraphAssertionError.class, synchronous::hasAsync),
                GRAPH, "async", Boolean.TRUE, Boolean.FALSE);
        assertFailure(assertThrows(GraphAssertionError.class, asynchronous::hasNoAsync),
                GRAPH, "async", Boolean.FALSE, Boolean.TRUE);
    }

    @Test
    void aGraphWhoseOnlyAsynchronyIsAGuardIsStillAsynchronous() {
        GraphDefinition<Entity, State> graph = syncActionWithAsyncGuard();
        GraphAssert<Entity, State> view = GraphAssert.assertThat(graph);
        assertSame(view, view.hasAsync());
        assertFailure(assertThrows(GraphAssertionError.class, view::hasNoAsync),
                GRAPH, "async", Boolean.FALSE, Boolean.TRUE);
    }

    // --- watches -----------------------------------------------------------------------------------

    @Test
    void aDeclaredWatchIsReportedByTheStateThatCarriesIt() {
        GraphDefinition<String, WatchState> graph = graphWithAWatch();
        GraphAssert<String, WatchState> view = GraphAssert.assertThat(graph);
        assertSame(view, view.hasNoWatch(WatchState.DONE));
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> view.hasNoWatch(WatchState.WAITING));
        assertEquals(String.class.getName() + "/" + WatchState.class.getName() + " WAITING",
                error.context());
        assertEquals("watch", error.property());
        assertEquals(Optional.empty(), error.expected());
        assertEquals(graph.watch(WatchState.WAITING), error.actual());
    }

    @Test
    void aGraphWithoutWatchesSatisfiesHasNoWatchOnEveryState() {
        GraphAssert<Entity, State> graph = GraphAssert.assertThat(TestDomain.graph(TIMEOUT));
        for (State state : State.values()) {
            graph.hasNoWatch(state);
        }
    }

    // --- cell selection ----------------------------------------------------------------------------

    @Test
    void theCellSelectorReachesEveryCellOfTheMatrix() {
        GraphAssert<Entity, State> graph = GraphAssert.assertThat(TestDomain.graph(TIMEOUT));
        graph.edge(State.NEW, State.READY).isAction();
        graph.edge(State.FAILED, State.FAILED).isStay();
        graph.edge(State.FAILED, State.NEW).isForbidden();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void aStateOfAForeignEnumIsRejectedBeforeTheMatrixIsIndexed() {
        GraphAssert raw = GraphAssert.assertThat(TestDomain.graph(TIMEOUT));
        IllegalArgumentException from = assertThrows(IllegalArgumentException.class,
                () -> raw.edge(Foreign.OTHER, State.READY));
        assertTrue(from.getMessage().startsWith("from OTHER is not a state of "), from.getMessage());
        IllegalArgumentException to = assertThrows(IllegalArgumentException.class,
                () -> raw.edge(State.NEW, Foreign.OTHER));
        assertTrue(to.getMessage().startsWith("to OTHER is not a state of "), to.getMessage());
        IllegalArgumentException row = assertThrows(IllegalArgumentException.class,
                () -> raw.hasAllowedTargetsExactly(Foreign.OTHER));
        assertTrue(row.getMessage().startsWith("from OTHER is not a state of "), row.getMessage());
        IllegalArgumentException watched = assertThrows(IllegalArgumentException.class,
                () -> raw.hasNoWatch(Foreign.OTHER));
        assertTrue(watched.getMessage().startsWith("state OTHER is not a state of "), watched.getMessage());
    }

    // --- messages and required arguments -----------------------------------------------------------

    @Test
    void theFailureMessageNamesTheQualifiedMachineTheContextAndTheProperty() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT)).hasInitial(State.READY));
        assertEquals(GRAPH + ": initial expected <READY> but was <NEW>", error.getMessage());
    }

    @Test
    void aNullRequiredArgumentIsAnArgumentErrorNotAnAssertionFailure() {
        GraphAssert<Entity, State> graph = GraphAssert.assertThat(TestDomain.graph(TIMEOUT));
        assertEquals("expected", assertThrows(NullPointerException.class,
                () -> graph.hasInitial(null)).getMessage());
        assertEquals("from", assertThrows(NullPointerException.class,
                () -> graph.hasAllowedTargetsExactly(null, State.READY)).getMessage());
        assertEquals("state", assertThrows(NullPointerException.class,
                () -> graph.hasNoWatch(null)).getMessage());
        assertEquals("from", assertThrows(NullPointerException.class,
                () -> graph.edge(null, State.READY)).getMessage());
        assertEquals("to", assertThrows(NullPointerException.class,
                () -> graph.edge(State.NEW, null)).getMessage());
        assertThrows(NullPointerException.class,
                () -> graph.hasTerminalsExactly(State.DONE, null));
        assertThrows(NullPointerException.class,
                () -> graph.hasAllowedTargetsExactly(State.NEW, State.READY, null));
    }

    // --- one null-subject case per expectation method ----------------------------------------------

    @Test
    void nullDefinitionFailsHasInitialWithContext() {
        assertNullGraph("initial", State.NEW, graph -> graph.hasInitial(State.NEW));
    }

    @Test
    void nullDefinitionFailsHasTerminalsExactlyWithContext() {
        assertNullGraph("terminals", Set.of(State.DONE), graph -> graph.hasTerminalsExactly(State.DONE));
    }

    @Test
    void nullDefinitionFailsHasAllowedTargetsExactlyWithContext() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.<Entity, State>assertThat(null)
                        .hasAllowedTargetsExactly(State.NEW, State.READY));
        assertFailure(error, "graph NEW", "allowed targets", Set.of(State.READY), null);
    }

    @Test
    void nullDefinitionFailsHasAsyncWithContext() {
        assertNullGraph("async", Boolean.TRUE, GraphAssert::hasAsync);
    }

    @Test
    void nullDefinitionFailsHasNoAsyncWithContext() {
        assertNullGraph("async", Boolean.FALSE, GraphAssert::hasNoAsync);
    }

    @Test
    void nullDefinitionFailsHasNoWatchWithContext() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.<Entity, State>assertThat(null).hasNoWatch(State.NEW));
        assertFailure(error, "graph NEW", "watch", Optional.empty(), null);
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static void assertNullGraph(String property, Object expected,
            Consumer<GraphAssert<Entity, State>> expectation) {
        GraphAssert<Entity, State> graph = GraphAssert.assertThat(null);
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> expectation.accept(graph));
        assertFailure(error, "graph", property, expected, null);
    }

    private static void assertFailure(GraphAssertionError error, String context, String property,
            Object expected, Object actual) {
        assertEquals(context, error.context());
        assertEquals(property, error.property());
        assertEquals(expected, error.expected());
        assertEquals(actual, error.actual());
    }

    private static GraphDefinition<Entity, State> synchronousGraph() {
        return StateGraph.define(TestDomain.KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .from(State.NEW)
                    .to(State.READY).action("prepare", ctx -> ActionOutcome.ok())
                    .to(State.FAILED).move().stay().forbidOthers("prepare first")
                .from(State.READY).to(State.DONE).move().stay().forbidOthers("finish only")
                .from(State.DONE).stay().forbidOthers("done is final")
                .from(State.FAILED).stay().forbidOthers("failed is final")
                .build();
    }

    private static GraphDefinition<Entity, State> syncActionWithAsyncGuard() {
        return StateGraph.define(TestDomain.KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .from(State.NEW)
                    .to(State.READY).action("prepare", ctx -> ActionOutcome.ok())
                        .asyncGuard("remote", ctx -> CompletableFuture.completedFuture(GuardResult.pass()))
                    .to(State.FAILED).move().stay().forbidOthers("prepare first")
                .from(State.READY).to(State.DONE).move().stay().forbidOthers("finish only")
                .from(State.DONE).stay().forbidOthers("done is final")
                .from(State.FAILED).stay().forbidOthers("failed is final")
                .build();
    }

    private static GraphDefinition<String, Cycle> graphWithoutTerminals() {
        return StateGraph.define(MachineKey.of(String.class, Cycle.class))
                .initial(Cycle.A).allowsCycle(Cycle.A)
                .from(Cycle.A).to(Cycle.B).move().stay().forbidOthers("go forward")
                .from(Cycle.B).to(Cycle.A).move().stay().forbidOthers("go back")
                .build();
    }

    private static GraphDefinition<String, WatchState> graphWithAWatch() {
        return StateGraph.define(MachineKey.of(String.class, WatchState.class))
                .initial(WatchState.WAITING).terminal(WatchState.DONE)
                .from(WatchState.WAITING).to(WatchState.DONE).move().stay()
                    .watches(TestDomain.KEY, owner -> List.<Entity>of(), peer -> Optional.of("owner"))
                    .when("ready", (owner, peers, view) -> { throw new AssertionError("the condition ran"); })
                        .goTo(WatchState.DONE)
                    .forbidOthers("waiting")
                .from(WatchState.DONE).stay().forbidOthers("final")
                .build();
    }
}
