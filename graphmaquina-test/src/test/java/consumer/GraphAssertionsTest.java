package consumer;

import static consumer.GraphAssertionErrors.assertFailure;
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
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Contract of {@link GraphAssert}. Every expectation gets a match and a mismatch read off the fields of the
 * {@link GraphAssertionError}, every one but {@code rejects} gets its own null-subject case, and the edge
 * selector is pinned to the pair of an origin and an identifier — never to a destination.
 */
class GraphAssertionsTest {

    /** State enum of a second machine, used as the state of a graph that declares a watch. */
    enum WatchState { WAITING, DONE }

    /** Transition identifiers of that second machine. */
    enum WatchId { FINISH }

    /** An enum that belongs to no graph under test, used to reach the selectors through raw types. */
    enum Foreign { OTHER }

    /**
     * States of two small graphs: one that cycles for ever and therefore declares no terminal, and one whose
     * two identifiers leave {@code A} for the terminal {@code B}.
     */
    enum Cycle { A, B }

    /**
     * Transition identifiers of both graphs over {@link Cycle}; in the parallel one {@code BACK} also leaves
     * {@code A} for {@code B}.
     */
    enum CycleId { FORWARD, BACK }

    private static final Duration TIMEOUT = Duration.ofSeconds(1);
    private static final String GRAPH = Entity.class.getName() + "/" + State.class.getName();

    // --- terminals ---------------------------------------------------------------------------------

    @Test
    void terminalExpectationDoesNotAcceptAnExtraTerminal() {
        GraphDefinition<Entity, State, Id> graph = TestDomain.graph(Duration.ofSeconds(1));
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
        GraphAssert<Entity, State, Id> graph = GraphAssert.assertThat(TestDomain.graph(TIMEOUT));
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
        GraphAssert<Entity, State, Id> graph = GraphAssert.assertThat(TestDomain.graph(TIMEOUT));
        assertSame(graph, graph.hasInitial(State.NEW));
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> graph.hasInitial(State.READY));
        assertFailure(error, GRAPH, "initial", State.READY, State.NEW);
    }

    // --- declared identifiers ----------------------------------------------------------------------

    @Test
    void theIdentifiersOfAStateAreTheOnesThatDeclareAnEdgeFromIt() {
        GraphAssert<Entity, State, Id> graph = GraphAssert.assertThat(TestDomain.graph(TIMEOUT));
        assertSame(graph, graph.hasTransitionsExactly(State.NEW, Id.PREPARE, Id.RECOVER));
        graph.hasTransitionsExactly(State.READY, Id.FINISH);
        graph.hasTransitionsExactly(State.DONE);
    }

    @Test
    void twoIdentifiersThatReachTheSameStateAreTwoEntries() {
        GraphAssert.assertThat(parallelGraph())
                .hasTransitionsExactly(Cycle.A, CycleId.FORWARD, CycleId.BACK)
                .transition(Cycle.A, CycleId.FORWARD).hasTarget(Cycle.B);
        GraphAssert.assertThat(parallelGraph()).transition(Cycle.A, CycleId.BACK).hasTarget(Cycle.B);
    }

    @Test
    void aMissingIdentifierIsReportedAgainstItsOwnState() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .hasTransitionsExactly(State.NEW, Id.PREPARE));
        assertFailure(error, GRAPH + " NEW", "transitions", Set.of(Id.PREPARE),
                Set.of(Id.PREPARE, Id.RECOVER));
    }

    @Test
    void aTerminalStateDeclaresNoIdentifierAtAll() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .hasTransitionsExactly(State.DONE, Id.FINISH));
        assertFailure(error, GRAPH + " DONE", "transitions", Set.of(Id.FINISH), Set.of());
    }

    // --- absence -----------------------------------------------------------------------------------

    @Test
    void anIdentifierWithoutAnEdgeFromThatStateIsAbsent() {
        GraphAssert<Entity, State, Id> graph = GraphAssert.assertThat(TestDomain.graph(TIMEOUT));
        assertSame(graph, graph.hasNoTransition(State.NEW, Id.ABANDON));
        graph.hasNoTransition(State.NEW, Id.FINISH);
        graph.hasNoTransition(State.READY, Id.PREPARE);
        graph.hasNoTransition(State.DONE, Id.FINISH);
    }

    @Test
    void aDeclaredEdgeIsReportedByItsDestinationWhenAbsenceWasExpected() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .hasNoTransition(State.NEW, Id.PREPARE));
        assertFailure(error, GRAPH + " NEW -PREPARE->", "transition", Optional.empty(),
                Optional.of(State.READY));
    }

    // --- policies ----------------------------------------------------------------------------------

    @Test
    void everyStateCarriesARejectionPolicy() {
        GraphAssert<Entity, State, Id> graph = GraphAssert.assertThat(TestDomain.graph(TIMEOUT));
        Map<State, String> defaultReasons = Map.of(
                State.NEW, "prepare first",
                State.READY, "finish only",
                State.DONE, "terminal state DONE accepts no transitions",
                State.FAILED, "terminal state FAILED accepts no transitions");
        assertEquals(Set.of(State.values()), defaultReasons.keySet(), "this test must list every state");
        for (State state : State.values()) {
            graph.policy(state).hasDefaultReason(defaultReasons.get(state))
                    .rejects(Id.ABANDON, defaultReasons.get(state));
        }
    }

    @Test
    void aDeclaredRejectionReplacesTheDefaultForThatIdentifierAlone() {
        GraphDefinition<Entity, State, Id> graph = graphWithADeclaredRejection();
        GraphAssert.assertThat(graph).policy(State.NEW)
                .hasDefaultReason("prepare first")
                .rejects(Id.ABANDON, "a new entity is never abandoned")
                .rejects(Id.FINISH, "prepare first");
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(graph).policy(State.NEW).rejects(Id.ABANDON, "prepare first"));
        assertFailure(error, GRAPH + " policy NEW ABANDON", "reason", "prepare first",
                "a new entity is never abandoned");
    }

    @Test
    void aPolicyMismatchIsReportedAgainstItsOwnState() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT)).policy(State.READY)
                        .hasDefaultReason("prepare first"));
        assertFailure(error, GRAPH + " policy READY", "default reason", "prepare first", "finish only");
    }

    @Test
    void aPolicyCannotAssertRejectionOfAnExistingTransition() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT)).policy(State.NEW)
                        .rejects(Id.PREPARE, "prepare first"));
        assertFailure(error, GRAPH + " policy NEW PREPARE", "transition", Optional.empty(), Optional.of(State.READY));
    }

    // --- asynchrony --------------------------------------------------------------------------------

    @Test
    void asynchronyIsReadOffTheDefinition() {
        GraphAssert<Entity, State, Id> asynchronous = GraphAssert.assertThat(TestDomain.graph(TIMEOUT));
        assertSame(asynchronous, asynchronous.hasAsync());
        GraphAssert<Entity, State, Id> synchronous = GraphAssert.assertThat(synchronousGraph());
        assertSame(synchronous, synchronous.hasNoAsync());
        assertFailure(assertThrows(GraphAssertionError.class, synchronous::hasAsync),
                GRAPH, "async", Boolean.TRUE, Boolean.FALSE);
        assertFailure(assertThrows(GraphAssertionError.class, asynchronous::hasNoAsync),
                GRAPH, "async", Boolean.FALSE, Boolean.TRUE);
    }

    @Test
    void aGraphWhoseOnlyAsynchronyIsAGuardIsStillAsynchronous() {
        GraphDefinition<Entity, State, Id> graph = syncActionWithAsyncGuard();
        GraphAssert<Entity, State, Id> view = GraphAssert.assertThat(graph);
        assertSame(view, view.hasAsync());
        assertFailure(assertThrows(GraphAssertionError.class, view::hasNoAsync),
                GRAPH, "async", Boolean.FALSE, Boolean.TRUE);
    }

    // --- watches -----------------------------------------------------------------------------------

    @Test
    void aDeclaredWatchIsReportedByTheStateThatCarriesIt() {
        GraphDefinition<String, WatchState, WatchId> graph = graphWithAWatch();
        GraphAssert<String, WatchState, WatchId> view = GraphAssert.assertThat(graph);
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
        GraphAssert<Entity, State, Id> graph = GraphAssert.assertThat(TestDomain.graph(TIMEOUT));
        for (State state : State.values()) {
            graph.hasNoWatch(state);
        }
    }

    // --- edge selection ----------------------------------------------------------------------------

    @Test
    void theEdgeSelectorReachesEveryDeclaredEdge() {
        GraphAssert<Entity, State, Id> graph = GraphAssert.assertThat(TestDomain.graph(TIMEOUT));
        graph.transition(State.NEW, Id.PREPARE).isAction().hasTarget(State.READY);
        graph.transition(State.NEW, Id.RECOVER).isMove().hasTarget(State.FAILED);
        graph.transition(State.READY, Id.FINISH).isMove().hasTarget(State.DONE);
    }

    /**
     * A foreign identifier could satisfy an expectation of absence by accident, {@code hasNoTransition} and
     * {@code rejects} alike: the index is keyed by {@code (from, id)}, so a constant of another enum would
     * simply miss it and report a green absence. Every selector that names an identifier refuses one that is
     * not in the machine's catalogue.
     */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void aForeignEnumIsRejectedBeforeTheDefinitionIsQueried() {
        GraphAssert raw = GraphAssert.assertThat(TestDomain.graph(TIMEOUT));
        IllegalArgumentException from = assertThrows(IllegalArgumentException.class,
                () -> raw.transition(Foreign.OTHER, Id.PREPARE));
        assertTrue(from.getMessage().startsWith("from OTHER is not a state of "), from.getMessage());
        IllegalArgumentException identifier = assertThrows(IllegalArgumentException.class,
                () -> raw.transition(State.NEW, Foreign.OTHER));
        assertTrue(identifier.getMessage().startsWith("transition OTHER is not a transition of "),
                identifier.getMessage());
        IllegalArgumentException state = assertThrows(IllegalArgumentException.class,
                () -> raw.hasTransitionsExactly(Foreign.OTHER));
        assertTrue(state.getMessage().startsWith("from OTHER is not a state of "), state.getMessage());
        IllegalArgumentException absent = assertThrows(IllegalArgumentException.class,
                () -> raw.hasNoTransition(Foreign.OTHER, Id.PREPARE));
        assertTrue(absent.getMessage().startsWith("from OTHER is not a state of "), absent.getMessage());
        IllegalArgumentException absentIdentifier = assertThrows(IllegalArgumentException.class,
                () -> raw.hasNoTransition(State.NEW, Foreign.OTHER));
        assertTrue(absentIdentifier.getMessage().startsWith("transition OTHER is not a transition of "),
                absentIdentifier.getMessage());
        IllegalArgumentException policy = assertThrows(IllegalArgumentException.class,
                () -> raw.policy(Foreign.OTHER));
        assertTrue(policy.getMessage().startsWith("state OTHER is not a state of "), policy.getMessage());
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> raw.policy(State.NEW).rejects(Foreign.OTHER, "prepare first"));
        assertTrue(rejected.getMessage().startsWith("transition OTHER is not a transition of "),
                rejected.getMessage());
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
        GraphAssert<Entity, State, Id> graph = GraphAssert.assertThat(TestDomain.graph(TIMEOUT));
        assertEquals("expected", assertThrows(NullPointerException.class,
                () -> graph.hasInitial(null)).getMessage());
        assertEquals("from", assertThrows(NullPointerException.class,
                () -> graph.hasTransitionsExactly(null, Id.PREPARE)).getMessage());
        assertEquals("state", assertThrows(NullPointerException.class,
                () -> graph.hasNoWatch(null)).getMessage());
        assertEquals("state", assertThrows(NullPointerException.class,
                () -> graph.policy(null)).getMessage());
        assertEquals("from", assertThrows(NullPointerException.class,
                () -> graph.transition(null, Id.PREPARE)).getMessage());
        assertEquals("transition", assertThrows(NullPointerException.class,
                () -> graph.transition(State.NEW, null)).getMessage());
        assertEquals("from", assertThrows(NullPointerException.class,
                () -> graph.hasNoTransition(null, Id.PREPARE)).getMessage());
        assertEquals("transition", assertThrows(NullPointerException.class,
                () -> graph.hasNoTransition(State.NEW, null)).getMessage());
        assertThrows(NullPointerException.class,
                () -> graph.hasTerminalsExactly(State.DONE, null));
        assertThrows(NullPointerException.class,
                () -> graph.hasTransitionsExactly(State.NEW, Id.PREPARE, null));
    }

    // --- one null-subject case per expectation method, except rejects ------------------------------

    @Test
    void nullDefinitionFailsHasInitialWithContext() {
        assertNullGraph("initial", State.NEW, graph -> graph.hasInitial(State.NEW));
    }

    @Test
    void nullDefinitionFailsHasTerminalsExactlyWithContext() {
        assertNullGraph("terminals", Set.of(State.DONE), graph -> graph.hasTerminalsExactly(State.DONE));
    }

    @Test
    void nullDefinitionFailsHasTransitionsExactlyWithContext() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.<Entity, State, Id>assertThat(null)
                        .hasTransitionsExactly(State.NEW, Id.PREPARE));
        assertFailure(error, "graph NEW", "transitions", Set.of(Id.PREPARE), null);
    }

    @Test
    void nullDefinitionFailsHasNoTransitionWithContext() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.<Entity, State, Id>assertThat(null)
                        .hasNoTransition(State.NEW, Id.PREPARE));
        assertFailure(error, "graph NEW -PREPARE->", "transition", Optional.empty(), null);
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
                () -> GraphAssert.<Entity, State, Id>assertThat(null).hasNoWatch(State.NEW));
        assertFailure(error, "graph NEW", "watch", Optional.empty(), null);
    }

    @Test
    void nullDefinitionFailsThePolicyView() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.<Entity, State, Id>assertThat(null).policy(State.NEW)
                        .hasDefaultReason("prepare first"));
        assertFailure(error, "graph policy NEW", "default reason", "prepare first", null);
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static void assertNullGraph(String property, Object expected,
            Consumer<GraphAssert<Entity, State, Id>> expectation) {
        GraphAssert<Entity, State, Id> graph = GraphAssert.assertThat(null);
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> expectation.accept(graph));
        assertFailure(error, "graph", property, expected, null);
    }

    private static GraphDefinition<Entity, State, Id> synchronousGraph() {
        return StateGraph.define(TestDomain.KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .statePolicy(State.NEW, "prepare first")
                .statePolicy(State.READY, "finish only")
                .transition(Id.PREPARE, State.NEW, State.READY)
                    .action("prepare", ctx -> ActionOutcome.ok()).endTransition()
                .transition(Id.RECOVER, State.NEW, State.FAILED).endTransition()
                .transition(Id.FINISH, State.READY, State.DONE).endTransition()
                .build();
    }

    private static GraphDefinition<Entity, State, Id> syncActionWithAsyncGuard() {
        return StateGraph.define(TestDomain.KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .statePolicy(State.NEW, "prepare first")
                .statePolicy(State.READY, "finish only")
                .transition(Id.PREPARE, State.NEW, State.READY)
                    .action("prepare", ctx -> ActionOutcome.ok())
                    .asyncGuard("remote", ctx -> CompletableFuture.completedFuture(GuardResult.pass()))
                    .endTransition()
                .transition(Id.RECOVER, State.NEW, State.FAILED).endTransition()
                .transition(Id.FINISH, State.READY, State.DONE).endTransition()
                .build();
    }

    private static GraphDefinition<Entity, State, Id> graphWithADeclaredRejection() {
        return StateGraph.define(TestDomain.KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .statePolicy(State.NEW, "prepare first")
                .statePolicy(State.READY, "finish only")
                .reject(State.NEW, Id.ABANDON, "a new entity is never abandoned")
                .transition(Id.PREPARE, State.NEW, State.READY)
                    .action("prepare", ctx -> ActionOutcome.ok()).endTransition()
                .transition(Id.RECOVER, State.NEW, State.FAILED).endTransition()
                .transition(Id.FINISH, State.READY, State.DONE).endTransition()
                .build();
    }

    private static GraphDefinition<String, Cycle, CycleId> graphWithoutTerminals() {
        return StateGraph.define(MachineKey.of(String.class, Cycle.class, CycleId.class))
                .initial(Cycle.A).allowsCycle(Cycle.A)
                .statePolicy(Cycle.A, "go forward").statePolicy(Cycle.B, "go back")
                .transition(CycleId.FORWARD, Cycle.A, Cycle.B).endTransition()
                .transition(CycleId.BACK, Cycle.B, Cycle.A).endTransition()
                .build();
    }

    /** Two identifiers leaving {@code A} for the same destination, told apart by their identifiers. */
    private static GraphDefinition<String, Cycle, CycleId> parallelGraph() {
        return StateGraph.define(MachineKey.of(String.class, Cycle.class, CycleId.class))
                .initial(Cycle.A).terminal(Cycle.B)
                .statePolicy(Cycle.A, "go forward")
                .transition(CycleId.FORWARD, Cycle.A, Cycle.B).endTransition()
                .transition(CycleId.BACK, Cycle.A, Cycle.B).endTransition()
                .build();
    }

    private static GraphDefinition<String, WatchState, WatchId> graphWithAWatch() {
        return StateGraph.define(MachineKey.of(String.class, WatchState.class, WatchId.class))
                .initial(WatchState.WAITING).terminal(WatchState.DONE)
                .statePolicy(WatchState.WAITING, "waiting")
                .transition(WatchId.FINISH, WatchState.WAITING, WatchState.DONE).endTransition()
                .watch(WatchState.WAITING, TestDomain.KEY, owner -> List.<Entity>of(),
                        peer -> Optional.of("owner"))
                    .when("ready", (owner, peers, view) -> { throw new AssertionError("the condition ran"); })
                        .execute(WatchId.FINISH)
                    .endWatch()
                .build();
    }
}
