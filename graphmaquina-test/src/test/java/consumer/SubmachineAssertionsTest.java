package consumer;

import static consumer.GraphAssertionErrors.assertFailure;
import static io.github.graphmaquina.test.ExitExpectation.exit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.test.ExitExpectation;
import io.github.graphmaquina.test.GraphAssert;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.SubmachineAssert;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs.Flow;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs.Gate;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs.Move;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs.Stage;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs.Step;
import io.github.graphmaquina.test.fixtures.SnapshotGraphs.Verdict;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Contract of {@link SubmachineAssert} and of {@code hasNoSubmachine}. The graph is the snapshot fixture's parent,
 * whose REVIEW holds the branch graph: its terminal state B exits through ACCEPT and C through DROP, in that order,
 * and DRAFT holds nothing. Every lookup of that fixture throws when it runs, so each case also shows that an
 * expectation reads the declaration alone. Every expectation gets a match and a mismatch read off the fields of the
 * {@link GraphAssertionError}, the child named by the caller is checked before anything else, and every expectation
 * gets its own case for a state without a binding and for a null definition.
 */
class SubmachineAssertionsTest {

    /** An enum that belongs to no graph under test, used to reach the views through raw types. */
    enum Foreign { OTHER }

    private static final MachineKey<String, Step, Move> STEPS = MachineKey.of(String.class, Step.class, Move.class);
    private static final String GRAPH = String.class.getName() + "/" + Stage.class.getName();
    private static final String REVIEW = GRAPH + " submachine REVIEW";

    // --- the binding: its child and its exits -------------------------------------------------------

    @Test
    void theExitsAreComparedInDeclarationOrder() {
        SubmachineAssert<String, Stage, Verdict, String, Step, Move> view = review();
        assertSame(view, view.hasExitsExactly(exit(Step.B, Verdict.ACCEPT), exit(Step.C, Verdict.DROP)));
        List<ExitExpectation<Step, Verdict>> declared =
                List.of(exit(Step.B, Verdict.ACCEPT), exit(Step.C, Verdict.DROP));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> view.hasExitsExactly(exit(Step.C, Verdict.DROP), exit(Step.B, Verdict.ACCEPT))),
                REVIEW, "exits", List.of(exit(Step.C, Verdict.DROP), exit(Step.B, Verdict.ACCEPT)), declared);
    }

    @Test
    void aMissingWrongOrExtraExitIsReportedAgainstTheDeclaredList() {
        SubmachineAssert<String, Stage, Verdict, String, Step, Move> view = review();
        List<ExitExpectation<Step, Verdict>> declared =
                List.of(exit(Step.B, Verdict.ACCEPT), exit(Step.C, Verdict.DROP));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasExitsExactly(exit(Step.B, Verdict.ACCEPT))),
                REVIEW, "exits", List.of(exit(Step.B, Verdict.ACCEPT)), declared);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> view.hasExitsExactly(exit(Step.B, Verdict.ACCEPT), exit(Step.C, Verdict.ACCEPT))),
                REVIEW, "exits", List.of(exit(Step.B, Verdict.ACCEPT), exit(Step.C, Verdict.ACCEPT)), declared);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasExitsExactly(exit(Step.B, Verdict.ACCEPT),
                        exit(Step.C, Verdict.DROP), exit(Step.A, Verdict.SUBMIT))),
                REVIEW, "exits",
                List.of(exit(Step.B, Verdict.ACCEPT), exit(Step.C, Verdict.DROP), exit(Step.A, Verdict.SUBMIT)),
                declared);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasExitsExactly()),
                REVIEW, "exits", List.of(), declared);
    }

    @Test
    void oneExitIsTheIdentifierItsTerminalStateSelects() {
        SubmachineAssert<String, Stage, Verdict, String, Step, Move> view = review();
        assertSame(view, view.hasExit(Step.C, Verdict.DROP));
        view.hasExit(Step.B, Verdict.ACCEPT);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasExit(Step.C, Verdict.ACCEPT)),
                REVIEW, "exit C", Optional.of(Verdict.ACCEPT), Optional.of(Verdict.DROP));
    }

    @Test
    void aStateOfTheChildThatIsNotTerminalHasNoExit() {
        assertFailure(assertThrows(GraphAssertionError.class, () -> review().hasExit(Step.A, Verdict.SUBMIT)),
                REVIEW, "exit A", Optional.of(Verdict.SUBMIT), Optional.empty());
    }

    /** The view is typed by the child the caller names, so a binding of another child fails before any exit is read. */
    @Test
    void theChildIsComparedAsAWholeKeyBeforeAnyExit() {
        GraphAssert<String, Stage, Verdict> graph = GraphAssert.assertThat(parent());
        graph.submachine(Stage.REVIEW, MachineKey.of(String.class, Step.class, Move.class))
                .hasExit(Step.B, Verdict.ACCEPT);
        MachineKey<String, Flow, Gate> otherChild = MachineKey.of(String.class, Flow.class, Gate.class);
        SubmachineAssert<String, Stage, Verdict, String, Flow, Gate> other =
                graph.submachine(Stage.REVIEW, otherChild);
        assertFailure(assertThrows(GraphAssertionError.class, () -> other.hasExit(Flow.CLOSED, Verdict.ACCEPT)),
                REVIEW, "child", otherChild, STEPS);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> other.hasExitsExactly(exit(Flow.CLOSED, Verdict.ACCEPT))),
                REVIEW, "child", otherChild, STEPS);
        MachineKey<Object, Step, Move> otherEntity = MachineKey.of(Object.class, Step.class, Move.class);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> graph.submachine(Stage.REVIEW, otherEntity).hasExit(Step.B, Verdict.ACCEPT)),
                REVIEW, "child", otherEntity, STEPS);
        MachineKey<String, Step, Gate> otherIdentifiers = MachineKey.of(String.class, Step.class, Gate.class);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> graph.submachine(Stage.REVIEW, otherIdentifiers).hasExit(Step.B, Verdict.ACCEPT)),
                REVIEW, "child", otherIdentifiers, STEPS);
    }

    // --- absence ------------------------------------------------------------------------------------

    @Test
    void aStateThatHoldsNoSubmachineSatisfiesHasNoSubmachine() {
        GraphAssert<String, Stage, Verdict> graph = GraphAssert.assertThat(parent());
        assertSame(graph, graph.hasNoSubmachine(Stage.DRAFT));
        graph.hasNoSubmachine(Stage.ACCEPTED);
        assertFailure(assertThrows(GraphAssertionError.class, () -> graph.hasNoSubmachine(Stage.REVIEW)),
                GRAPH + " REVIEW", "submachine", Optional.empty(), Optional.of(STEPS));
    }

    @Test
    void aStateThatHoldsNoSubmachineHasNoSubject() {
        SubmachineAssert<String, Stage, Verdict, String, Step, Move> view =
                GraphAssert.assertThat(parent()).submachine(Stage.DRAFT, STEPS);
        String context = GRAPH + " submachine DRAFT";
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasExit(Step.B, Verdict.ACCEPT)),
                context, "exit B", Optional.of(Verdict.ACCEPT), null);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> view.hasExitsExactly(exit(Step.B, Verdict.ACCEPT))),
                context, "exits", List.of(exit(Step.B, Verdict.ACCEPT)), null);
    }

    // --- foreign enums through raw types --------------------------------------------------------------

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void aParentStateOfAForeignEnumIsRejectedBeforeTheBindingIsRead() {
        GraphAssert raw = GraphAssert.assertThat(parent());
        IllegalArgumentException selector = assertThrows(IllegalArgumentException.class,
                () -> raw.submachine(Foreign.OTHER, STEPS));
        assertTrue(selector.getMessage().startsWith("state OTHER is not a state of "), selector.getMessage());
        IllegalArgumentException absence = assertThrows(IllegalArgumentException.class,
                () -> raw.hasNoSubmachine(Foreign.OTHER));
        assertTrue(absence.getMessage().startsWith("state OTHER is not a state of "), absence.getMessage());
    }

    /** A terminal or an exit of another enum can only miss, so it fails as an assertion rather than as an argument. */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void aTerminalOrAnExitOfAForeignEnumFailsAsAnAssertion() {
        SubmachineAssert raw = review();
        assertFailure(assertThrows(GraphAssertionError.class, () -> raw.hasExit(Foreign.OTHER, Verdict.ACCEPT)),
                REVIEW, "exit OTHER", Optional.of(Verdict.ACCEPT), Optional.empty());
        assertFailure(assertThrows(GraphAssertionError.class, () -> raw.hasExit(Step.B, Foreign.OTHER)),
                REVIEW, "exit B", Optional.of(Foreign.OTHER), Optional.of(Verdict.ACCEPT));
        ExitExpectation foreign = new ExitExpectation(Foreign.OTHER, Verdict.ACCEPT);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> raw.hasExitsExactly(foreign, exit(Step.C, Verdict.DROP))),
                REVIEW, "exits", List.of(foreign, exit(Step.C, Verdict.DROP)),
                List.of(exit(Step.B, Verdict.ACCEPT), exit(Step.C, Verdict.DROP)));
    }

    // --- arguments and the expectation record -------------------------------------------------------

    @Test
    void aNullRequiredArgumentIsAnArgumentErrorNotAnAssertionFailure() {
        GraphAssert<String, Stage, Verdict> graph = GraphAssert.assertThat(parent());
        assertEquals("state is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> graph.submachine(null, STEPS)).getMessage());
        assertEquals("child is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> graph.submachine(Stage.REVIEW, null)).getMessage());
        assertEquals("state is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> graph.hasNoSubmachine(null)).getMessage());
        SubmachineAssert<String, Stage, Verdict, String, Step, Move> view = review();
        assertEquals("terminal is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> view.hasExit(null, Verdict.ACCEPT)).getMessage());
        assertEquals("transition is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> view.hasExit(Step.B, null)).getMessage());
        assertThrows(NullPointerException.class, () -> view.hasExitsExactly(exit(Step.B, Verdict.ACCEPT), null));
        assertEquals("terminal is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> exit(null, Verdict.ACCEPT)).getMessage());
        assertEquals("transition is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> new ExitExpectation<Step, Verdict>(Step.B, null)).getMessage());
    }

    @Test
    void theFactoryBuildsTheRecord() {
        ExitExpectation<Step, Verdict> built = exit(Step.B, Verdict.ACCEPT);
        assertEquals(new ExitExpectation<>(Step.B, Verdict.ACCEPT), built);
        assertEquals(Step.B, built.terminal());
        assertEquals(Verdict.ACCEPT, built.transition());
    }

    // --- one null-subject case per expectation method -----------------------------------------------

    @Test
    void nullDefinitionFailsHasNoSubmachineWithContext() {
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> GraphAssert.<String, Stage, Verdict>assertThat(null).hasNoSubmachine(Stage.DRAFT)),
                "graph DRAFT", "submachine", Optional.empty(), null);
    }

    @Test
    void nullDefinitionFailsHasExitWithContext() {
        assertFailure(assertThrows(GraphAssertionError.class, () -> nullReview().hasExit(Step.B, Verdict.ACCEPT)),
                "graph submachine REVIEW", "exit B", Optional.of(Verdict.ACCEPT), null);
    }

    @Test
    void nullDefinitionFailsHasExitsExactlyWithContext() {
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> nullReview().hasExitsExactly(exit(Step.B, Verdict.ACCEPT))),
                "graph submachine REVIEW", "exits", List.of(exit(Step.B, Verdict.ACCEPT)), null);
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** REVIEW holds the branch graph; B exits through ACCEPT and C through DROP. */
    private static GraphDefinition<String, Stage, Verdict> parent() {
        return SnapshotGraphs.submachineGraph(Verdict.DROP);
    }

    private static SubmachineAssert<String, Stage, Verdict, String, Step, Move> review() {
        return GraphAssert.assertThat(parent()).submachine(Stage.REVIEW, STEPS);
    }

    private static SubmachineAssert<String, Stage, Verdict, String, Step, Move> nullReview() {
        return GraphAssert.<String, Stage, Verdict>assertThat(null).submachine(Stage.REVIEW, STEPS);
    }
}
