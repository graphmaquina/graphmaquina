package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.error.UnhandledFailureException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocGraphs;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins what an action failure that no route handles reports: the name of the failed action, the edge it was on, the
 * reason, and that the entity keeps its state.
 */
class UnhandledFailureCharacterizationTest {

    @Test
    void anUnhandledFailureNamesTheActionThatFailed() {
        StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(DocGraphs.docs(
                submit -> submit.action("Submit", context -> ActionOutcome.failed("the gateway said no"))))
                .state(Doc::status, Doc::setStatus).build();
        Doc doc = new Doc("1", DRAFT);
        UnhandledFailureException error = assertThrows(UnhandledFailureException.class,
                () -> machine.execute(doc, SUBMIT));
        assertEquals(Optional.of("Submit"), error.action());
        assertEquals(Optional.of(SUBMIT), error.transition());
        assertEquals(Optional.of(REVIEW), error.to());
        assertEquals("the gateway said no", error.reason());
        assertEquals(DRAFT, doc.status());
    }
}
