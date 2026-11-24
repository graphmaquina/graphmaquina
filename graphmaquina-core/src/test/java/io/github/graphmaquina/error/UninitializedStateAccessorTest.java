package io.github.graphmaquina.error;

import static io.github.graphmaquina.fixtures.DocGraphs.docs;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocGraphs;
import io.github.graphmaquina.fixtures.DocMachines;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import org.junit.jupiter.api.Test;

/**
 * {@link StateMachineException#uninitializedState()} recognises a null state with one accessor, whichever exception
 * reports it: the abort of a call whose root or cascaded peer had no state, the failure of a check, and an
 * {@link UninitializedStateException} itself. Any other failure gives none.
 */
class UninitializedStateAccessorTest {

    @Test
    void aCallOnANullStateGivesTheException() {
        StateMachine<Doc, DocStatus, DocTransition> machine = DocMachines.registry(DocGraphs.plain()).of(DOCS);

        TransitionAbortedException abort = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(new Doc("1", null), SUBMIT));

        assertSame(abort.getCause(), abort.uninitializedState().orElseThrow());
    }

    @Test
    void aCheckOnANullStateGivesTheException() {
        StateMachine<Doc, DocStatus, DocTransition> machine = DocMachines.registry(DocGraphs.plain()).of(DOCS);

        EligibilityCheckException failure = assertThrows(EligibilityCheckException.class,
                () -> machine.eligibility(new Doc("1", null), SUBMIT));

        assertSame(failure.getCause(), failure.uninitializedState().orElseThrow());
    }

    @Test
    void aCascadedPeerWithoutStateGivesTheException() {
        Doc peer = new Doc("2", null);
        StateMachine<Doc, DocStatus, DocTransition> machine = DocMachines.registry(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("publishPeer", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.cascade(DOCS, peer, PUBLISH);
                            return ActionOutcome.ok();
                        }))).of(DOCS);

        TransitionAbortedException abort = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(new Doc("1", DRAFT), SUBMIT));

        assertTrue(abort.uninitializedState().isPresent());
    }

    @Test
    void theExceptionItselfGivesItself() {
        UninitializedStateException exception = new UninitializedStateException(DOCS, new Doc("1", null));

        assertSame(exception, exception.uninitializedState().orElseThrow());
    }

    @Test
    void aGuardFailureGivesNone() {
        StateMachine<Doc, DocStatus, DocTransition> machine = DocMachines.registry(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("boom", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw new IllegalStateException("boom");
                        }))).of(DOCS);

        TransitionAbortedException abort = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(new Doc("1", DRAFT), SUBMIT));

        assertTrue(abort.uninitializedState().isEmpty());
    }
}
