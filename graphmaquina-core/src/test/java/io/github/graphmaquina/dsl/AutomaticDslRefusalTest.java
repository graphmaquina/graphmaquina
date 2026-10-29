package io.github.graphmaquina.dsl;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * What the automatic vocabulary of the DSL refuses at the call, as the rest of the DSL does: a null argument or
 * element, a state or an identifier of another catalogue, an {@code automaticOrder} once the graph was built, and the
 * {@code automatic()} mark and the continuing failure routes on a stage already closed.
 */
class AutomaticDslRefusalTest {

    private static final String WHERE = "DRAFT -SUBMIT-> REVIEW";

    private static final TransitionAction<Doc, DocStatus, DocTransition> SUCCEEDS =
            (TransitionContext<Doc, DocStatus, DocTransition> context) -> ActionOutcome.ok();

    /** A constant of neither catalogue of the doc machine. */
    enum Foreign { ELSEWHERE }

    /** A failure kind of this test's own. */
    record Declined() implements FailureKind { }

    private static GraphBuilder<Doc, DocStatus, DocTransition> base() {
        return StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED);
    }

    /** The edge DRAFT -SUBMIT-> REVIEW with an action, still open. */
    private static TransitionBuilder<Doc, DocStatus, DocTransition> submit() {
        return base().transition(SUBMIT, DRAFT, REVIEW).action("submit", SUCCEEDS);
    }

    /** The message of the NullPointerException that {@code call} throws. */
    private static String nullRefusal(Executable call) {
        return assertThrows(NullPointerException.class, call).getMessage();
    }

    @Test
    void automaticOrderRefusesANullArgumentOrElement() {
        List<DocTransition> withNull = new ArrayList<>();
        withNull.add(SUBMIT);
        withNull.add(null);
        assertEquals(List.of("from is marked non-null but is null", "transitions is marked non-null but is null"),
                List.of(nullRefusal(() -> base().automaticOrder(null, List.of(SUBMIT))),
                        nullRefusal(() -> base().automaticOrder(DRAFT, null))));
        assertEquals("transitions contains a null element",
                nullRefusal(() -> base().automaticOrder(DRAFT, withNull)));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void automaticOrderRefusesAStateOrAnIdentifierOfAnotherCatalogue() {
        GraphBuilder raw = base();
        assertEquals("state must belong to " + DocStatus.class.getName() + ": ELSEWHERE",
                assertThrows(IllegalArgumentException.class,
                        () -> raw.automaticOrder(Foreign.ELSEWHERE, List.of(SUBMIT))).getMessage());
        assertEquals("transition must belong to " + DocTransition.class.getName() + ": ELSEWHERE",
                assertThrows(IllegalArgumentException.class,
                        () -> raw.automaticOrder(DRAFT, List.of(SUBMIT, Foreign.ELSEWHERE))).getMessage());
    }

    @Test
    void automaticOrderIsRefusedOnceTheGraphIsBuilt() {
        GraphBuilder<Doc, DocStatus, DocTransition> builder = base()
                .statePolicy(DRAFT, "a draft is submitted").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition();
        builder.build();
        assertEquals("build() was already called on the graph Doc/DocStatus",
                assertThrows(IllegalStateException.class, () -> builder.automaticOrder(DRAFT, List.of(SUBMIT)))
                        .getMessage());
    }

    @Test
    void theContinuingRoutesRefuseANullArgumentOrElement() {
        List<Class<? extends Throwable>> withNull = new ArrayList<>();
        withNull.add(IllegalStateException.class);
        withNull.add(null);
        TransitionBuilder<Doc, DocStatus, DocTransition> stage = submit();
        assertEquals(List.of("recovery is marked non-null but is null", "kind is marked non-null but is null",
                        "transition is marked non-null but is null", "failsOn",
                        "failsOn is marked non-null but is null", "failsOn contains a null element"),
                List.of(nullRefusal(() -> stage.onFailureAndContinue(null)),
                        nullRefusal(() -> stage.recoverAndContinue(null, DISCARD)),
                        nullRefusal(() -> stage.recoverAndContinue(Declined.class, null)),
                        nullRefusal(() -> stage.recoverAndContinue(Declined.class, DISCARD,
                                (Class<? extends Throwable>) null)),
                        nullRefusal(() -> stage.recoverAndContinue(Declined.class, DISCARD,
                                (List<Class<? extends Throwable>>) null)),
                        nullRefusal(() -> stage.recoverAndContinue(Declined.class, DISCARD, withNull))));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void theContinuingRoutesRefuseAnIdentifierOfAnotherCatalogue() {
        TransitionBuilder raw = submit();
        List<Executable> calls = List.of(
                () -> raw.onFailureAndContinue(Foreign.ELSEWHERE),
                () -> raw.recoverAndContinue(Declined.class, Foreign.ELSEWHERE),
                () -> raw.recoverAndContinue(Declined.class, Foreign.ELSEWHERE, IllegalStateException.class),
                () -> raw.recoverAndContinue(Declined.class, Foreign.ELSEWHERE, List.of()));
        for (Executable call : calls) {
            assertEquals("transition must belong to " + DocTransition.class.getName() + ": ELSEWHERE",
                    assertThrows(IllegalArgumentException.class, call).getMessage());
        }
    }

    @Test
    void aClosedStageRefusesTheAutomaticMarkAndTheContinuingRoutes() {
        TransitionBuilder<Doc, DocStatus, DocTransition> closed = submit();
        closed.endTransition();
        List<Executable> late = List.of(
                closed::automatic,
                () -> closed.onFailureAndContinue(DISCARD),
                () -> closed.recoverAndContinue(Declined.class, DISCARD),
                () -> closed.recoverAndContinue(Declined.class, DISCARD, IllegalStateException.class),
                () -> closed.recoverAndContinue(Declined.class, DISCARD, List.of(IllegalStateException.class)));
        for (Executable call : late) {
            assertEquals("endTransition() was already called on " + WHERE + " in the graph Doc/DocStatus",
                    assertThrows(IllegalStateException.class, call).getMessage());
        }
    }
}
