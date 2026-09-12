package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.EdgeKind;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.export.Detail;
import io.github.graphmaquina.export.GraphView;
import io.github.graphmaquina.export.MermaidExporter;
import io.github.graphmaquina.test.EdgeAssert;
import io.github.graphmaquina.test.GraphAssert;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.GuardExpectation;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
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
 * Contract of {@link EdgeAssert}. Every expectation gets a match and a mismatch, the mismatch is read
 * off the fields of the {@link GraphAssertionError} rather than off its type alone, and every method
 * gets its own null-subject case so that removing a single guard fails exactly one test.
 */
class EdgeAssertionsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);
    private static final String GRAPH = Entity.class.getName() + "/" + State.class.getName();
    private static final String NEW_READY = GRAPH + " NEW -> READY";

    // --- the premise of this task: fields the diagram does not carry ------------------------------

    @Test
    void timeoutIsCheckedEvenWhenTheDiagramIsUnchanged() {
        GraphDefinition<Entity, State> shortGraph = TestDomain.graph(Duration.ofSeconds(1));
        GraphDefinition<Entity, State> longGraph = TestDomain.graph(Duration.ofSeconds(30));
        assertEquals(MermaidExporter.render(GraphView.of(shortGraph), Detail.FULL),
                MermaidExporter.render(GraphView.of(longGraph), Detail.FULL));
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(longGraph).edge(State.NEW, State.READY)
                        .hasTimeout(Duration.ofSeconds(1)));
        assertEquals("timeout", error.property());
    }

    @Test
    void theHookAndTheFailureClassesAreCheckedThoughTheDiagramDropsThem() {
        GraphDefinition<Entity, State> graph = TestDomain.graph(TIMEOUT);
        GraphDefinition<Entity, State> other = graphWithOtherHookAndFailureClass();
        assertEquals(MermaidExporter.render(GraphView.of(graph), Detail.FULL),
                MermaidExporter.render(GraphView.of(other), Detail.FULL));
        GraphAssertionError hook = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(other).edge(State.NEW, State.READY).hasHookNamed("audit"));
        assertFailure(hook, NEW_READY, "hook", Optional.of("audit"), Optional.of("notify"));
        GraphAssertionError failures = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(other).edge(State.NEW, State.READY)
                        .failsOnExactly(IOException.class));
        assertFailure(failures, NEW_READY, "failsOn", List.of(IOException.class),
                List.of(UncheckedIOException.class));
    }

    @Test
    void inspectionNeverRunsTheCellsCallbacks() {
        EdgeAssert<Entity, State> edge =
                GraphAssert.assertThat(graphWhoseCallbacksFail()).edge(State.NEW, State.READY);
        edge.isAction().hasActionNamed("prepare").hasAsyncAction()
                .hasGuardsExactly(new GuardExpectation("first", false))
                .hasTimeout(TIMEOUT).hasFailureTarget(State.FAILED)
                .failsOnExactly(IOException.class).hasHookNamed("audit");
    }

    // --- one match per method ---------------------------------------------------------------------

    @Test
    void theAsynchronousCellMatchesEveryFieldItDeclares() {
        EdgeAssert<Entity, State> edge =
                GraphAssert.assertThat(TestDomain.graph(TIMEOUT)).edge(State.NEW, State.READY);
        assertSame(edge, edge.isAction()
                .hasReason("")
                .hasActionNamed("prepare")
                .hasAsyncAction()
                .hasGuardsExactly(new GuardExpectation("first", false), new GuardExpectation("second", false))
                .hasFailureTarget(State.FAILED)
                .failsOnExactly(IOException.class)
                .hasTimeout(TIMEOUT)
                .hasHookNamed("audit"));
    }

    @Test
    void aSynchronousCellMatchesTheAbsenceOfEveryOptionalField() {
        EdgeAssert<Entity, State> edge =
                GraphAssert.assertThat(syncActionWithAsyncGuard()).edge(State.NEW, State.READY);
        assertSame(edge, edge.isAction()
                .hasSyncAction()
                .hasGuardsExactly(new GuardExpectation("remote", true))
                .hasNoFailureTarget()
                .failsOnExactly()
                .hasNoTimeout()
                .hasNoHook());
    }

    @Test
    void theOtherThreeKindsAndTheirReasonsMatch() {
        GraphAssert<Entity, State> graph = GraphAssert.assertThat(TestDomain.graph(TIMEOUT));
        graph.edge(State.NEW, State.FAILED).isMove().hasReason("").hasNoAction().hasNoGuards();
        graph.edge(State.NEW, State.NEW).isStay().hasReason("").hasNoAction().hasNoGuards();
        graph.edge(State.NEW, State.DONE).isForbidden().hasReason("prepare first").hasNoAction().hasNoGuards();
    }

    // --- one mismatch per method ------------------------------------------------------------------

    @Test
    void aMoveCellIsNotAnAction() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .edge(State.NEW, State.FAILED).isAction());
        assertFailure(error, GRAPH + " NEW -> FAILED", "kind", EdgeKind.ACTION, EdgeKind.MOVE);
    }

    @Test
    void anActionCellIsNotAMove() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .edge(State.NEW, State.READY).isMove());
        assertFailure(error, NEW_READY, "kind", EdgeKind.MOVE, EdgeKind.ACTION);
    }

    @Test
    void aMoveCellIsNotAStay() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .edge(State.NEW, State.FAILED).isStay());
        assertFailure(error, GRAPH + " NEW -> FAILED", "kind", EdgeKind.STAY, EdgeKind.MOVE);
    }

    @Test
    void aStayCellIsNotForbidden() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .edge(State.NEW, State.NEW).isForbidden());
        assertFailure(error, GRAPH + " NEW -> NEW", "kind", EdgeKind.FORBID, EdgeKind.STAY);
    }

    @Test
    void aForbiddenCellCarriesItsOwnReasonOnly() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .edge(State.NEW, State.DONE).hasReason("finish only"));
        assertFailure(error, GRAPH + " NEW -> DONE", "reason", "finish only", "prepare first");
    }

    @Test
    void theActionNameIsComparedExactly() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .edge(State.NEW, State.READY).hasActionNamed("Prepare"));
        assertFailure(error, NEW_READY, "action", Optional.of("Prepare"), Optional.of("prepare"));
    }

    @Test
    void aCellWithAnActionDoesNotSatisfyHasNoAction() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .edge(State.NEW, State.READY).hasNoAction());
        assertFailure(error, NEW_READY, "action", Optional.empty(), Optional.of("prepare"));
    }

    @Test
    void anAsyncGuardOnASyncActionDoesNotMakeTheActionAsync() {
        GraphDefinition<Entity, State> graph = syncActionWithAsyncGuard();
        assertTrue(graph.edge(State.NEW, State.READY).isAsync(),
                "the cell is async as a whole because its guard is");
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(graph).edge(State.NEW, State.READY).hasAsyncAction());
        assertFailure(error, NEW_READY, "async action", Boolean.TRUE, Boolean.FALSE);
    }

    @Test
    void anAsynchronousActionDoesNotSatisfyHasSyncAction() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .edge(State.NEW, State.READY).hasSyncAction());
        assertFailure(error, NEW_READY, "sync action", Boolean.TRUE, Boolean.FALSE);
    }

    @Test
    void aCellWithoutAnActionSatisfiesNeitherAsyncNorSyncAction() {
        GraphAssert<Entity, State> graph = GraphAssert.assertThat(TestDomain.graph(TIMEOUT));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> graph.edge(State.NEW, State.FAILED).hasAsyncAction()),
                GRAPH + " NEW -> FAILED", "async action", Boolean.TRUE, Boolean.FALSE);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> graph.edge(State.NEW, State.FAILED).hasSyncAction()),
                GRAPH + " NEW -> FAILED", "sync action", Boolean.TRUE, Boolean.FALSE);
    }

    @Test
    void guardsKeepTheirDeclarationOrder() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT)).edge(State.NEW, State.READY)
                        .hasGuardsExactly(new GuardExpectation("second", false),
                                new GuardExpectation("first", false)));
        assertFailure(error, NEW_READY, "guards",
                List.of(new GuardExpectation("second", false), new GuardExpectation("first", false)),
                List.of(new GuardExpectation("first", false), new GuardExpectation("second", false)));
    }

    @Test
    void guardsKeepTheirMultiplicity() {
        GraphDefinition<Entity, State> graph = graphWithRepeatedGuardNames();
        GraphAssert.assertThat(graph).edge(State.NEW, State.READY).hasGuardsExactly(
                new GuardExpectation("check", false),
                new GuardExpectation("other", false),
                new GuardExpectation("check", false));
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(graph).edge(State.NEW, State.READY).hasGuardsExactly(
                        new GuardExpectation("check", false), new GuardExpectation("other", false)));
        assertEquals("guards", error.property());
        assertEquals(3, ((List<?>) error.actual()).size());
    }

    @Test
    void aGuardsAsynchronyIsPartOfTheExpectation() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(syncActionWithAsyncGuard()).edge(State.NEW, State.READY)
                        .hasGuardsExactly(new GuardExpectation("remote", false)));
        assertFailure(error, NEW_READY, "guards",
                List.of(new GuardExpectation("remote", false)),
                List.of(new GuardExpectation("remote", true)));
    }

    @Test
    void aGuardedCellDoesNotSatisfyHasNoGuards() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .edge(State.NEW, State.READY).hasNoGuards());
        assertFailure(error, NEW_READY, "guards", List.of(),
                List.of(new GuardExpectation("first", false), new GuardExpectation("second", false)));
    }

    @Test
    void theFailureTargetIsComparedExactly() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .edge(State.NEW, State.READY).hasFailureTarget(State.DONE));
        assertFailure(error, NEW_READY, "failure target", Optional.of(State.DONE),
                Optional.of(State.FAILED));
    }

    @Test
    void aRoutedCellDoesNotSatisfyHasNoFailureTarget() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .edge(State.NEW, State.READY).hasNoFailureTarget());
        assertFailure(error, NEW_READY, "failure target", Optional.empty(), Optional.of(State.FAILED));
    }

    @Test
    void failureClassesAreNotWidenedToSubclasses() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .edge(State.NEW, State.READY).failsOnExactly(Exception.class));
        assertFailure(error, NEW_READY, "failsOn", List.of(Exception.class), List.of(IOException.class));
    }

    @Test
    void failureClassesKeepTheirDeclarationOrder() {
        GraphDefinition<Entity, State> graph = graphWithRepeatedFailureClasses();
        GraphAssert.assertThat(graph).edge(State.NEW, State.READY)
                .failsOnExactly(IOException.class, UncheckedIOException.class, IOException.class);
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(graph).edge(State.NEW, State.READY)
                        .failsOnExactly(UncheckedIOException.class, IOException.class, IOException.class));
        assertFailure(error, NEW_READY, "failsOn",
                List.of(UncheckedIOException.class, IOException.class, IOException.class),
                List.of(IOException.class, UncheckedIOException.class, IOException.class));
    }

    @Test
    void failureClassesKeepTheirMultiplicity() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(graphWithRepeatedFailureClasses()).edge(State.NEW, State.READY)
                        .failsOnExactly(IOException.class, UncheckedIOException.class));
        assertEquals("failsOn", error.property());
        assertEquals(List.of(IOException.class, UncheckedIOException.class, IOException.class),
                error.actual());
    }

    @Test
    void theTimeoutIsComparedExactly() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .edge(State.NEW, State.READY).hasTimeout(Duration.ofMillis(1000).plusNanos(1)));
        assertFailure(error, NEW_READY, "timeout", Optional.of(Duration.ofSeconds(1).plusNanos(1)),
                Optional.of(TIMEOUT));
    }

    @Test
    void aCellWithATimeoutDoesNotSatisfyHasNoTimeout() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .edge(State.NEW, State.READY).hasNoTimeout());
        assertFailure(error, NEW_READY, "timeout", Optional.empty(), Optional.of(TIMEOUT));
    }

    @Test
    void theHookNameIsComparedExactly() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .edge(State.NEW, State.READY).hasHookNamed("notify"));
        assertFailure(error, NEW_READY, "hook", Optional.of("notify"), Optional.of("audit"));
    }

    @Test
    void aHookedCellDoesNotSatisfyHasNoHook() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .edge(State.NEW, State.READY).hasNoHook());
        assertFailure(error, NEW_READY, "hook", Optional.empty(), Optional.of("audit"));
    }

    @Test
    void theFailureMessageNamesTheCellTheContextAndTheProperty() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(TestDomain.graph(TIMEOUT))
                        .edge(State.NEW, State.READY).hasNoTimeout());
        assertEquals(NEW_READY + ": timeout expected <Optional.empty> but was <Optional[PT1S]>",
                error.getMessage());
    }

    // --- required expected values stay argument errors ---------------------------------------------

    @Test
    void aNullExpectedValueIsAnArgumentErrorNotAnAssertionFailure() {
        EdgeAssert<Entity, State> edge =
                GraphAssert.assertThat(TestDomain.graph(TIMEOUT)).edge(State.NEW, State.READY);
        assertEquals("expected", assertThrows(NullPointerException.class,
                () -> edge.hasReason(null)).getMessage());
        assertEquals("expected", assertThrows(NullPointerException.class,
                () -> edge.hasActionNamed(null)).getMessage());
        assertEquals("expected", assertThrows(NullPointerException.class,
                () -> edge.hasFailureTarget(null)).getMessage());
        assertEquals("expected", assertThrows(NullPointerException.class,
                () -> edge.hasTimeout(null)).getMessage());
        assertEquals("expected", assertThrows(NullPointerException.class,
                () -> edge.hasHookNamed(null)).getMessage());
    }

    @Test
    void aNullInsideAVarargsExpectationIsAnArgumentError() {
        EdgeAssert<Entity, State> edge =
                GraphAssert.assertThat(TestDomain.graph(TIMEOUT)).edge(State.NEW, State.READY);
        assertThrows(NullPointerException.class,
                () -> edge.hasGuardsExactly(new GuardExpectation("first", false), null));
        assertThrows(NullPointerException.class, () -> edge.failsOnExactly(IOException.class, null));
    }

    @Test
    void aGuardExpectationRejectsANullName() {
        assertEquals("name", assertThrows(NullPointerException.class,
                () -> new GuardExpectation(null, false)).getMessage());
    }

    // --- one null-subject case per expectation method ----------------------------------------------

    @Test
    void nullDefinitionFailsIsActionWithContext() {
        assertNullCell("kind", EdgeKind.ACTION, EdgeAssert::isAction);
    }

    @Test
    void nullDefinitionFailsIsMoveWithContext() {
        assertNullCell("kind", EdgeKind.MOVE, EdgeAssert::isMove);
    }

    @Test
    void nullDefinitionFailsIsStayWithContext() {
        assertNullCell("kind", EdgeKind.STAY, EdgeAssert::isStay);
    }

    @Test
    void nullDefinitionFailsIsForbiddenWithContext() {
        assertNullCell("kind", EdgeKind.FORBID, EdgeAssert::isForbidden);
    }

    @Test
    void nullDefinitionFailsHasReasonWithContext() {
        assertNullCell("reason", "prepare first", edge -> edge.hasReason("prepare first"));
    }

    @Test
    void nullDefinitionFailsHasActionNamedWithContext() {
        assertNullCell("action", Optional.of("prepare"), edge -> edge.hasActionNamed("prepare"));
    }

    @Test
    void nullDefinitionFailsHasNoActionWithContext() {
        assertNullCell("action", Optional.empty(), EdgeAssert::hasNoAction);
    }

    @Test
    void nullDefinitionFailsHasAsyncActionWithContext() {
        assertNullCell("async action", Boolean.TRUE, EdgeAssert::hasAsyncAction);
    }

    @Test
    void nullDefinitionFailsHasSyncActionWithContext() {
        assertNullCell("sync action", Boolean.TRUE, EdgeAssert::hasSyncAction);
    }

    @Test
    void nullDefinitionFailsHasGuardsExactlyWithContext() {
        assertNullCell("guards", List.of(new GuardExpectation("first", false)),
                edge -> edge.hasGuardsExactly(new GuardExpectation("first", false)));
    }

    @Test
    void nullDefinitionFailsHasNoGuardsWithContext() {
        assertNullCell("guards", List.of(), EdgeAssert::hasNoGuards);
    }

    @Test
    void nullDefinitionFailsHasFailureTargetWithContext() {
        assertNullCell("failure target", Optional.of(State.FAILED),
                edge -> edge.hasFailureTarget(State.FAILED));
    }

    @Test
    void nullDefinitionFailsHasNoFailureTargetWithContext() {
        assertNullCell("failure target", Optional.empty(), EdgeAssert::hasNoFailureTarget);
    }

    @Test
    void nullDefinitionFailsFailsOnExactlyWithContext() {
        assertNullCell("failsOn", List.of(IOException.class),
                edge -> edge.failsOnExactly(IOException.class));
    }

    @Test
    void nullDefinitionFailsHasTimeoutWithContext() {
        assertNullCell("timeout", Optional.of(TIMEOUT), edge -> edge.hasTimeout(TIMEOUT));
    }

    @Test
    void nullDefinitionFailsHasNoTimeoutWithContext() {
        assertNullCell("timeout", Optional.empty(), EdgeAssert::hasNoTimeout);
    }

    @Test
    void nullDefinitionFailsHasHookNamedWithContext() {
        assertNullCell("hook", Optional.of("audit"), edge -> edge.hasHookNamed("audit"));
    }

    @Test
    void nullDefinitionFailsHasNoHookWithContext() {
        assertNullCell("hook", Optional.empty(), EdgeAssert::hasNoHook);
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static void assertNullCell(String property, Object expected,
            Consumer<EdgeAssert<Entity, State>> expectation) {
        EdgeAssert<Entity, State> edge =
                GraphAssert.<Entity, State>assertThat(null).edge(State.NEW, State.READY);
        GraphAssertionError error = assertThrows(GraphAssertionError.class, () -> expectation.accept(edge));
        assertFailure(error, "graph NEW -> READY", property, expected, null);
    }

    private static void assertFailure(GraphAssertionError error, String context, String property,
            Object expected, Object actual) {
        assertEquals(context, error.context());
        assertEquals(property, error.property());
        assertEquals(expected, error.expected());
        assertEquals(actual, error.actual());
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

    private static GraphDefinition<Entity, State> graphWithRepeatedGuardNames() {
        return StateGraph.define(TestDomain.KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .from(State.NEW)
                    .to(State.READY).action("prepare", ctx -> ActionOutcome.ok())
                        .guard("check", ctx -> GuardResult.pass())
                        .guard("other", ctx -> GuardResult.pass())
                        .guard("check", ctx -> GuardResult.pass())
                    .to(State.FAILED).move().stay().forbidOthers("prepare first")
                .from(State.READY).to(State.DONE).move().stay().forbidOthers("finish only")
                .from(State.DONE).stay().forbidOthers("done is final")
                .from(State.FAILED).stay().forbidOthers("failed is final")
                .build();
    }

    private static GraphDefinition<Entity, State> graphWithRepeatedFailureClasses() {
        return StateGraph.define(TestDomain.KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .from(State.NEW)
                    .to(State.READY).action("prepare", ctx -> ActionOutcome.ok())
                        .onFailure(State.FAILED)
                        .failsOn(IOException.class)
                        .failsOn(UncheckedIOException.class)
                        .failsOn(IOException.class)
                    .to(State.FAILED).move().stay().forbidOthers("prepare first")
                .from(State.READY).to(State.DONE).move().stay().forbidOthers("finish only")
                .from(State.DONE).stay().forbidOthers("done is final")
                .from(State.FAILED).stay().forbidOthers("failed is final")
                .build();
    }

    private static GraphDefinition<Entity, State> graphWithOtherHookAndFailureClass() {
        return StateGraph.define(TestDomain.KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .from(State.NEW)
                    .to(State.READY)
                        .asyncAction("prepare", ctx -> CompletableFuture.completedFuture(ActionOutcome.ok()))
                        .guard("first", ctx -> GuardResult.pass())
                        .guard("second", ctx -> GuardResult.pass())
                        .timeout(TIMEOUT).onFailure(State.FAILED).failsOn(UncheckedIOException.class)
                        .afterCommit("notify", entity -> { })
                    .to(State.FAILED).move().stay().forbidOthers("prepare first")
                .from(State.READY).to(State.DONE).move().stay().forbidOthers("finish only")
                .from(State.DONE).stay().forbidOthers("done is final")
                .from(State.FAILED).stay().forbidOthers("failed is final")
                .build();
    }

    private static GraphDefinition<Entity, State> graphWhoseCallbacksFail() {
        return StateGraph.define(TestDomain.KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .from(State.NEW)
                    .to(State.READY)
                        .asyncAction("prepare", ctx -> { throw new AssertionError("the action ran"); })
                        .guard("first", ctx -> { throw new AssertionError("the guard ran"); })
                        .timeout(TIMEOUT).onFailure(State.FAILED).failsOn(IOException.class)
                        .afterCommit("audit", entity -> { throw new AssertionError("the hook ran"); })
                    .to(State.FAILED).move().stay().forbidOthers("prepare first")
                .from(State.READY).to(State.DONE).move().stay().forbidOthers("finish only")
                .from(State.DONE).stay().forbidOthers("done is final")
                .from(State.FAILED).stay().forbidOthers("failed is final")
                .build();
    }
}
