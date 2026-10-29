package consumer;

import static consumer.GraphAssertionErrors.assertFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.test.GraphAssert;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.GuardExpectation;
import io.github.graphmaquina.test.TransitionAssert;
import io.github.graphmaquina.test.fixtures.AutomaticDomain;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Contract of {@link TransitionAssert}. Every expectation gets a match and a mismatch, the mismatch is
 * read off the fields of the {@link GraphAssertionError} rather than off its type alone, and every method
 * gets its own null-subject case so that removing a single guard fails exactly one test. The subject is
 * always the edge keyed by an origin and an identifier; what a graph does NOT declare leaves the view without
 * a subject, and is asserted through {@code hasNoTransition} and the state's policy instead.
 */
class TransitionAssertionsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);
    private static final String GRAPH = Entity.class.getName() + "/" + State.class.getName();
    private static final String PREPARE = GRAPH + " NEW -PREPARE->";

    // --- declared metadata is the contract, independent of diagram formats -----------------------

    @Test
    void timeoutIsCheckedAgainstTheDeclaredDuration() {
        GraphDefinition<Entity, State, Id> shortGraph = TestDomain.graph(Duration.ofSeconds(1));
        GraphDefinition<Entity, State, Id> longGraph = TestDomain.graph(Duration.ofSeconds(30));
        GraphAssert.assertThat(shortGraph).transition(State.NEW, Id.PREPARE)
                .hasTimeout(Duration.ofSeconds(1));
        GraphAssert.assertThat(longGraph).transition(State.NEW, Id.PREPARE)
                .hasTimeout(Duration.ofSeconds(30));
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(longGraph).transition(State.NEW, Id.PREPARE)
                        .hasTimeout(Duration.ofSeconds(1)));
        assertFailure(error, PREPARE, "timeout", Optional.of(Duration.ofSeconds(1)),
                Optional.of(Duration.ofSeconds(30)));
    }

    @Test
    void hookAndFailureClassesAreCheckedAgainstTheirDeclaredMetadata() {
        GraphDefinition<Entity, State, Id> graph = TestDomain.graph(TIMEOUT);
        GraphDefinition<Entity, State, Id> other = graphWithOtherHookAndFailureClass();
        GraphAssert.assertThat(graph).transition(State.NEW, Id.PREPARE)
                .hasHookNamed("audit").failsOnExactly(IOException.class);
        GraphAssert.assertThat(other).transition(State.NEW, Id.PREPARE)
                .hasHookNamed("notify").failsOnExactly(UncheckedIOException.class);
        GraphAssertionError hook = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(other).transition(State.NEW, Id.PREPARE).hasHookNamed("audit"));
        assertFailure(hook, PREPARE, "hook", Optional.of("audit"), Optional.of("notify"));
        GraphAssertionError failures = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(other).transition(State.NEW, Id.PREPARE)
                        .failsOnExactly(IOException.class));
        assertFailure(failures, PREPARE, "failsOn", List.of(IOException.class),
                List.of(UncheckedIOException.class));
    }

    @Test
    void inspectionNeverRunsTheEdgesCallbacks() {
        TransitionAssert<Entity, State, Id> edge =
                GraphAssert.assertThat(graphWhoseCallbacksFail()).transition(State.NEW, Id.PREPARE);
        edge.isAction().hasTarget(State.READY).hasActionNamed("prepare").hasAsyncAction()
                .hasGuardsExactly(new GuardExpectation("first", false))
                .hasTimeout(TIMEOUT).hasFailureTransition(Id.RECOVER)
                .failsOnExactly(IOException.class).hasHookNamed("audit");
    }

    // --- one match per method ---------------------------------------------------------------------

    @Test
    void theAsynchronousEdgeMatchesEveryFieldItDeclares() {
        TransitionAssert<Entity, State, Id> edge =
                GraphAssert.assertThat(TestDomain.graph(TIMEOUT)).transition(State.NEW, Id.PREPARE);
        assertSame(edge, edge.isAction()
                .hasTarget(State.READY)
                .hasActionNamed("prepare")
                .hasAsyncAction()
                .hasGuardsExactly(new GuardExpectation("first", false), new GuardExpectation("second", false))
                .hasFailureTransition(Id.RECOVER)
                .failsOnExactly(IOException.class)
                .hasTimeout(TIMEOUT)
                .hasHookNamed("audit"));
    }

    @Test
    void aSynchronousEdgeMatchesTheAbsenceOfEveryOptionalField() {
        TransitionAssert<Entity, State, Id> edge =
                GraphAssert.assertThat(syncActionWithAsyncGuard()).transition(State.NEW, Id.PREPARE);
        assertSame(edge, edge.isAction()
                .hasSyncAction()
                .hasGuardsExactly(new GuardExpectation("remote", true))
                .hasNoFailureTransition()
                .failsOnExactly()
                .hasNoTimeout()
                .hasNoHook());
    }

    @Test
    void anEdgeWithoutAnActionIsAMove() {
        GraphAssert<Entity, State, Id> graph = GraphAssert.assertThat(TestDomain.graph(TIMEOUT));
        graph.transition(State.NEW, Id.RECOVER).isMove().hasTarget(State.FAILED).hasNoAction().hasNoGuards();
        graph.transition(State.READY, Id.FINISH).isMove().hasTarget(State.DONE).hasNoAction().hasNoGuards();
    }

    /**
     * Two identifiers may leave the same state for the same destination, so the destination alone never
     * selects an edge: each one is read through its own identifier and carries its own action.
     */
    @Test
    void parallelEdgesAreTwoSubjectsThatShareADestination() {
        GraphAssert<Entity, State, Id> graph = GraphAssert.assertThat(parallelGraph());
        graph.transition(State.NEW, Id.PREPARE).hasTarget(State.READY).hasActionNamed("prepare");
        graph.transition(State.NEW, Id.ABANDON).hasTarget(State.READY).hasActionNamed("shortcut");
    }

    // --- one mismatch per method ------------------------------------------------------------------

    @Test
    void aMoveEdgeIsNotAnAction() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .transition(State.NEW, Id.RECOVER).isAction());
        assertFailure(error, GRAPH + " NEW -RECOVER->", "kind", TransitionKind.ACTION, TransitionKind.MOVE);
    }

    @Test
    void anActionEdgeIsNotAMove() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .transition(State.NEW, Id.PREPARE).isMove());
        assertFailure(error, PREPARE, "kind", TransitionKind.MOVE, TransitionKind.ACTION);
    }

    @Test
    void theDestinationIsComparedExactly() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .transition(State.NEW, Id.PREPARE).hasTarget(State.DONE));
        assertFailure(error, PREPARE, "target", State.DONE, State.READY);
    }

    @Test
    void theActionNameIsComparedExactly() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .transition(State.NEW, Id.PREPARE).hasActionNamed("Prepare"));
        assertFailure(error, PREPARE, "action", Optional.of("Prepare"), Optional.of("prepare"));
    }

    @Test
    void anEdgeWithAnActionDoesNotSatisfyHasNoAction() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .transition(State.NEW, Id.PREPARE).hasNoAction());
        assertFailure(error, PREPARE, "action", Optional.empty(), Optional.of("prepare"));
    }

    @Test
    void anAsyncGuardOnASyncActionDoesNotMakeTheActionAsync() {
        GraphDefinition<Entity, State, Id> graph = syncActionWithAsyncGuard();
        assertTrue(graph.findTransition(State.NEW, Id.PREPARE).orElseThrow().isAsync(),
                "the edge is async as a whole because its guard is");
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(graph).transition(State.NEW, Id.PREPARE).hasAsyncAction());
        assertFailure(error, PREPARE, "async action", Boolean.TRUE, Boolean.FALSE);
    }

    @Test
    void anAsynchronousActionDoesNotSatisfyHasSyncAction() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .transition(State.NEW, Id.PREPARE).hasSyncAction());
        assertFailure(error, PREPARE, "sync action", Boolean.TRUE, Boolean.FALSE);
    }

    @Test
    void anEdgeWithoutAnActionSatisfiesNeitherAsyncNorSyncAction() {
        GraphAssert<Entity, State, Id> graph = GraphAssert.assertThat(TestDomain.graph(TIMEOUT));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> graph.transition(State.NEW, Id.RECOVER).hasAsyncAction()),
                GRAPH + " NEW -RECOVER->", "async action", Boolean.TRUE, Boolean.FALSE);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> graph.transition(State.NEW, Id.RECOVER).hasSyncAction()),
                GRAPH + " NEW -RECOVER->", "sync action", Boolean.TRUE, Boolean.FALSE);
    }

    @Test
    void guardsKeepTheirDeclarationOrder() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT)).transition(State.NEW, Id.PREPARE)
                        .hasGuardsExactly(new GuardExpectation("second", false),
                                new GuardExpectation("first", false)));
        assertFailure(error, PREPARE, "guards",
                List.of(new GuardExpectation("second", false), new GuardExpectation("first", false)),
                List.of(new GuardExpectation("first", false), new GuardExpectation("second", false)));
    }

    /** The guards of one edge have distinct names, so an expectation that repeats one is not collapsed into a match. */
    @Test
    void guardsKeepTheirMultiplicity() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT)).transition(State.NEW, Id.PREPARE)
                        .hasGuardsExactly(new GuardExpectation("first", false),
                                new GuardExpectation("second", false), new GuardExpectation("first", false)));
        assertFailure(error, PREPARE, "guards",
                List.of(new GuardExpectation("first", false), new GuardExpectation("second", false),
                        new GuardExpectation("first", false)),
                List.of(new GuardExpectation("first", false), new GuardExpectation("second", false)));
    }

    @Test
    void aGuardsAsynchronyIsPartOfTheExpectation() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(syncActionWithAsyncGuard()).transition(State.NEW, Id.PREPARE)
                        .hasGuardsExactly(new GuardExpectation("remote", false)));
        assertFailure(error, PREPARE, "guards",
                List.of(new GuardExpectation("remote", false)),
                List.of(new GuardExpectation("remote", true)));
    }

    @Test
    void aGuardedEdgeDoesNotSatisfyHasNoGuards() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .transition(State.NEW, Id.PREPARE).hasNoGuards());
        assertFailure(error, PREPARE, "guards", List.of(),
                List.of(new GuardExpectation("first", false), new GuardExpectation("second", false)));
    }

    @Test
    void theFailureTransitionIsComparedExactly() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .transition(State.NEW, Id.PREPARE).hasFailureTransition(Id.FINISH));
        assertFailure(error, PREPARE, "failure transition", Optional.of(Id.FINISH),
                Optional.of(Id.RECOVER));
    }

    @Test
    void aRoutedEdgeDoesNotSatisfyHasNoFailureTransition() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .transition(State.NEW, Id.PREPARE).hasNoFailureTransition());
        assertFailure(error, PREPARE, "failure transition", Optional.empty(), Optional.of(Id.RECOVER));
    }

    @Test
    void failureClassesAreNotWidenedToSubclasses() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .transition(State.NEW, Id.PREPARE).failsOnExactly(Exception.class));
        assertFailure(error, PREPARE, "failsOn", List.of(Exception.class), List.of(IOException.class));
    }

    @Test
    void failureClassesKeepTheirDeclarationOrder() {
        GraphDefinition<Entity, State, Id> graph = graphWithRepeatedFailureClasses();
        GraphAssert.assertThat(graph).transition(State.NEW, Id.PREPARE)
                .failsOnExactly(IOException.class, UncheckedIOException.class, IOException.class);
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(graph).transition(State.NEW, Id.PREPARE)
                        .failsOnExactly(UncheckedIOException.class, IOException.class, IOException.class));
        assertFailure(error, PREPARE, "failsOn",
                List.of(UncheckedIOException.class, IOException.class, IOException.class),
                List.of(IOException.class, UncheckedIOException.class, IOException.class));
    }

    @Test
    void failureClassesKeepTheirMultiplicity() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(graphWithRepeatedFailureClasses())
                        .transition(State.NEW, Id.PREPARE)
                        .failsOnExactly(IOException.class, UncheckedIOException.class));
        assertEquals("failsOn", error.property());
        assertEquals(List.of(IOException.class, UncheckedIOException.class, IOException.class),
                error.actual());
    }

    @Test
    void theTimeoutIsComparedExactly() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT)).transition(State.NEW, Id.PREPARE)
                        .hasTimeout(Duration.ofMillis(1000).plusNanos(1)));
        assertFailure(error, PREPARE, "timeout", Optional.of(Duration.ofSeconds(1).plusNanos(1)),
                Optional.of(TIMEOUT));
    }

    @Test
    void anEdgeWithATimeoutDoesNotSatisfyHasNoTimeout() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .transition(State.NEW, Id.PREPARE).hasNoTimeout());
        assertFailure(error, PREPARE, "timeout", Optional.empty(), Optional.of(TIMEOUT));
    }

    @Test
    void theHookNameIsComparedExactly() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .transition(State.NEW, Id.PREPARE).hasHookNamed("notify"));
        assertFailure(error, PREPARE, "hook", Optional.of("notify"), Optional.of("audit"));
    }

    @Test
    void aHookedEdgeDoesNotSatisfyHasNoHook() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .transition(State.NEW, Id.PREPARE).hasNoHook());
        assertFailure(error, PREPARE, "hook", Optional.empty(), Optional.of("audit"));
    }

    @Test
    void theFailureMessageNamesTheEdgeTheContextAndTheProperty() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .transition(State.NEW, Id.PREPARE).hasNoTimeout());
        assertEquals(PREPARE + ": timeout expected <Optional.empty> but was <Optional[PT1S]>",
                error.getMessage());
    }

    // --- an identifier with no edge from that state has no subject at all --------------------------

    @Test
    void anUndeclaredKeyLeavesTheViewWithoutASubject() {
        TransitionAssert<Entity, State, Id> absent =
                GraphAssert.assertThat(TestDomain.graph(TIMEOUT)).transition(State.NEW, Id.ABANDON);
        GraphAssertionError error =
                assertThrows(GraphAssertionError.class, () -> absent.hasTarget(State.READY));
        assertFailure(error, GRAPH + " NEW -ABANDON->", "target", State.READY, null);
        assertFailure(assertThrows(GraphAssertionError.class, absent::isMove),
                GRAPH + " NEW -ABANDON->", "kind", TransitionKind.MOVE, null);
    }

    // --- required expected values stay argument errors ---------------------------------------------

    @Test
    void aNullExpectedValueIsAnArgumentErrorNotAnAssertionFailure() {
        TransitionAssert<Entity, State, Id> edge =
                GraphAssert.assertThat(TestDomain.graph(TIMEOUT)).transition(State.NEW, Id.PREPARE);
        assertEquals("expected is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> edge.hasTarget(null)).getMessage());
        assertEquals("expected is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> edge.hasActionNamed(null)).getMessage());
        assertEquals("expected is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> edge.hasFailureTransition(null)).getMessage());
        assertEquals("expected is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> edge.hasTimeout(null)).getMessage());
        assertEquals("expected is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> edge.hasHookNamed(null)).getMessage());
    }

    @Test
    void aNullInsideAVarargsExpectationIsAnArgumentError() {
        TransitionAssert<Entity, State, Id> edge =
                GraphAssert.assertThat(TestDomain.graph(TIMEOUT)).transition(State.NEW, Id.PREPARE);
        assertThrows(NullPointerException.class,
                () -> edge.hasGuardsExactly(new GuardExpectation("first", false), null));
        assertThrows(NullPointerException.class, () -> edge.failsOnExactly(IOException.class, null));
    }

    @Test
    void aGuardExpectationRejectsANullName() {
        assertEquals("name is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> new GuardExpectation(null, false)).getMessage());
    }

    // --- one null-subject case per expectation method ----------------------------------------------

    @Test
    void nullDefinitionFailsHasTargetWithContext() {
        assertNullEdge("target", State.READY, edge -> edge.hasTarget(State.READY));
    }

    @Test
    void nullDefinitionFailsIsActionWithContext() {
        assertNullEdge("kind", TransitionKind.ACTION, TransitionAssert::isAction);
    }

    @Test
    void nullDefinitionFailsIsMoveWithContext() {
        assertNullEdge("kind", TransitionKind.MOVE, TransitionAssert::isMove);
    }

    @Test
    void nullDefinitionFailsHasActionNamedWithContext() {
        assertNullEdge("action", Optional.of("prepare"), edge -> edge.hasActionNamed("prepare"));
    }

    @Test
    void nullDefinitionFailsHasNoActionWithContext() {
        assertNullEdge("action", Optional.empty(), TransitionAssert::hasNoAction);
    }

    @Test
    void nullDefinitionFailsHasAsyncActionWithContext() {
        assertNullEdge("async action", Boolean.TRUE, TransitionAssert::hasAsyncAction);
    }

    @Test
    void nullDefinitionFailsHasSyncActionWithContext() {
        assertNullEdge("sync action", Boolean.TRUE, TransitionAssert::hasSyncAction);
    }

    @Test
    void nullDefinitionFailsHasGuardsExactlyWithContext() {
        assertNullEdge("guards", List.of(new GuardExpectation("first", false)),
                edge -> edge.hasGuardsExactly(new GuardExpectation("first", false)));
    }

    @Test
    void nullDefinitionFailsHasNoGuardsWithContext() {
        assertNullEdge("guards", List.of(), TransitionAssert::hasNoGuards);
    }

    @Test
    void nullDefinitionFailsHasFailureTransitionWithContext() {
        assertNullEdge("failure transition", Optional.of(Id.RECOVER),
                edge -> edge.hasFailureTransition(Id.RECOVER));
    }

    @Test
    void nullDefinitionFailsHasNoFailureTransitionWithContext() {
        assertNullEdge("failure transition", Optional.empty(), TransitionAssert::hasNoFailureTransition);
    }

    @Test
    void nullDefinitionFailsFailsOnExactlyWithContext() {
        assertNullEdge("failsOn", List.of(IOException.class),
                edge -> edge.failsOnExactly(IOException.class));
    }

    @Test
    void nullDefinitionFailsHasTimeoutWithContext() {
        assertNullEdge("timeout", Optional.of(TIMEOUT), edge -> edge.hasTimeout(TIMEOUT));
    }

    @Test
    void nullDefinitionFailsHasNoTimeoutWithContext() {
        assertNullEdge("timeout", Optional.empty(), TransitionAssert::hasNoTimeout);
    }

    @Test
    void nullDefinitionFailsHasHookNamedWithContext() {
        assertNullEdge("hook", Optional.of("audit"), edge -> edge.hasHookNamed("audit"));
    }

    @Test
    void nullDefinitionFailsHasNoHookWithContext() {
        assertNullEdge("hook", Optional.empty(), TransitionAssert::hasNoHook);
    }

    // --- the automatic vocabulary ------------------------------------------------------------------

    private static final String READY_FINISH = GRAPH + " READY -FINISH->";

    @Test
    void anAutomaticEdgeMatchesIsAutomaticAndRefusesIsManual() {
        TransitionAssert<Entity, State, Id> edge =
                GraphAssert.assertThat(AutomaticDomain.graph()).transition(State.NEW, Id.PREPARE);
        assertSame(edge, edge.isAutomatic());
        assertFailure(assertThrows(GraphAssertionError.class, edge::isManual), PREPARE, "automatic",
                Boolean.FALSE, Boolean.TRUE);
    }

    @Test
    void aManualEdgeMatchesIsManualAndRefusesIsAutomatic() {
        TransitionAssert<Entity, State, Id> edge =
                GraphAssert.assertThat(AutomaticDomain.graph()).transition(State.NEW, Id.RECOVER);
        assertSame(edge, edge.isManual());
        assertFailure(assertThrows(GraphAssertionError.class, edge::isAutomatic), GRAPH + " NEW -RECOVER->",
                "automatic", Boolean.TRUE, Boolean.FALSE);
    }

    @Test
    void aContinuingFallbackMatchesOnFailureContinuesAndAPlainOneDoesNot() {
        TransitionAssert<Entity, State, Id> continuing =
                GraphAssert.assertThat(AutomaticDomain.graph()).transition(State.READY, Id.FINISH);
        assertSame(continuing, continuing.hasFailureTransition(Id.RECOVER).onFailureContinues());
        TransitionAssert<Entity, State, Id> plain =
                GraphAssert.assertThat(TestDomain.graph(TIMEOUT)).transition(State.NEW, Id.PREPARE);
        assertFailure(assertThrows(GraphAssertionError.class, plain::onFailureContinues), PREPARE,
                "failure route continues", Boolean.TRUE, Boolean.FALSE);
    }

    @Test
    void anEdgeWithoutAFallbackDoesNotContinueOnFailure() {
        TransitionAssert<Entity, State, Id> edge =
                GraphAssert.assertThat(AutomaticDomain.graph()).transition(State.NEW, Id.PREPARE);
        assertFailure(assertThrows(GraphAssertionError.class, edge::onFailureContinues), PREPARE,
                "failure route continues", Boolean.TRUE, Boolean.FALSE);
    }

    @Test
    void aContinuingRecoveryRuleMatchesByItsKind() {
        TransitionAssert<Entity, State, Id> edge =
                GraphAssert.assertThat(AutomaticDomain.graph()).transition(State.NEW, Id.PREPARE);
        assertSame(edge, edge.recoveryContinues(AutomaticDomain.Declined.class));
    }

    @Test
    void aStoppingRuleOrAnUndeclaredKindDoesNotContinue() {
        TransitionAssert<Entity, State, Id> edge =
                GraphAssert.assertThat(AutomaticDomain.graph()).transition(State.NEW, Id.PREPARE);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> edge.recoveryContinues(AutomaticDomain.Refused.class)), PREPARE,
                "recovery continues for " + AutomaticDomain.Refused.class.getName(), Boolean.TRUE, Boolean.FALSE);
        TransitionAssert<Entity, State, Id> other =
                GraphAssert.assertThat(AutomaticDomain.graph()).transition(State.READY, Id.FINISH);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> other.recoveryContinues(AutomaticDomain.Declined.class)), READY_FINISH,
                "recovery rule", AutomaticDomain.Declined.class, List.of());
    }

    @Test
    void theMarksAreReadOffTheSpecAndNeverRunAnyCallback() {
        TransitionAssert<Entity, State, Id> edge =
                GraphAssert.assertThat(graphWhoseCallbacksFail()).transition(State.NEW, Id.PREPARE);
        assertSame(edge, edge.isManual());
    }

    @Test
    void aNullRequiredKindIsAnArgumentErrorNotAnAssertionFailure() {
        TransitionAssert<Entity, State, Id> edge =
                GraphAssert.assertThat(AutomaticDomain.graph()).transition(State.NEW, Id.PREPARE);
        assertEquals("kind is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> edge.recoveryContinues(null)).getMessage());
    }

    @Test
    void nullDefinitionFailsIsAutomaticWithContext() {
        assertNullEdge("automatic", Boolean.TRUE, TransitionAssert::isAutomatic);
    }

    @Test
    void nullDefinitionFailsIsManualWithContext() {
        assertNullEdge("automatic", Boolean.FALSE, TransitionAssert::isManual);
    }

    @Test
    void nullDefinitionFailsOnFailureContinuesWithContext() {
        assertNullEdge("failure route continues", Boolean.TRUE, TransitionAssert::onFailureContinues);
    }

    @Test
    void nullDefinitionFailsRecoveryContinuesWithContext() {
        assertNullEdge("recovery continues", AutomaticDomain.Declined.class,
                (TransitionAssert<Entity, State, Id> edge) -> edge.recoveryContinues(AutomaticDomain.Declined.class));
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static void assertNullEdge(String property, Object expected,
            Consumer<TransitionAssert<Entity, State, Id>> expectation) {
        TransitionAssert<Entity, State, Id> edge =
                GraphAssert.<Entity, State, Id>assertThat(null).transition(State.NEW, Id.PREPARE);
        GraphAssertionError error = assertThrows(GraphAssertionError.class, () -> expectation.accept(edge));
        assertFailure(error, "graph NEW -PREPARE->", property, expected, null);
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

    private static GraphDefinition<Entity, State, Id> parallelGraph() {
        return StateGraph.define(TestDomain.KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .statePolicy(State.NEW, "prepare first")
                .statePolicy(State.READY, "finish only")
                .transition(Id.PREPARE, State.NEW, State.READY)
                    .action("prepare", ctx -> ActionOutcome.ok()).endTransition()
                .transition(Id.ABANDON, State.NEW, State.READY)
                    .action("shortcut", ctx -> ActionOutcome.ok()).endTransition()
                .transition(Id.RECOVER, State.NEW, State.FAILED).endTransition()
                .transition(Id.FINISH, State.READY, State.DONE).endTransition()
                .build();
    }

    private static GraphDefinition<Entity, State, Id> graphWithRepeatedFailureClasses() {
        return StateGraph.define(TestDomain.KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .statePolicy(State.NEW, "prepare first")
                .statePolicy(State.READY, "finish only")
                .transition(Id.PREPARE, State.NEW, State.READY)
                    .action("prepare", ctx -> ActionOutcome.ok())
                    .onFailure(Id.RECOVER)
                    .failsOn(IOException.class)
                    .failsOn(UncheckedIOException.class)
                    .failsOn(IOException.class)
                    .endTransition()
                .transition(Id.RECOVER, State.NEW, State.FAILED).endTransition()
                .transition(Id.FINISH, State.READY, State.DONE).endTransition()
                .build();
    }

    private static GraphDefinition<Entity, State, Id> graphWithOtherHookAndFailureClass() {
        return StateGraph.define(TestDomain.KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .statePolicy(State.NEW, "prepare first")
                .statePolicy(State.READY, "finish only")
                .transition(Id.PREPARE, State.NEW, State.READY)
                    .asyncAction("prepare", ctx -> CompletableFuture.completedFuture(ActionOutcome.ok()))
                    .guard("first", ctx -> GuardResult.pass())
                    .guard("second", ctx -> GuardResult.pass())
                    .timeout(TIMEOUT).onFailure(Id.RECOVER).failsOn(UncheckedIOException.class)
                    .afterCommit("notify", entity -> { })
                    .endTransition()
                .transition(Id.RECOVER, State.NEW, State.FAILED).endTransition()
                .transition(Id.FINISH, State.READY, State.DONE).endTransition()
                .build();
    }

    private static GraphDefinition<Entity, State, Id> graphWhoseCallbacksFail() {
        return StateGraph.define(TestDomain.KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .statePolicy(State.NEW, "prepare first")
                .statePolicy(State.READY, "finish only")
                .transition(Id.PREPARE, State.NEW, State.READY)
                    .asyncAction("prepare", ctx -> { throw new AssertionError("the action ran"); })
                    .guard("first", ctx -> { throw new AssertionError("the guard ran"); })
                    .timeout(TIMEOUT).onFailure(Id.RECOVER).failsOn(IOException.class)
                    .afterCommit("audit", entity -> { throw new AssertionError("the hook ran"); })
                    .endTransition()
                .transition(Id.RECOVER, State.NEW, State.FAILED).endTransition()
                .transition(Id.FINISH, State.READY, State.DONE).endTransition()
                .build();
    }
}
