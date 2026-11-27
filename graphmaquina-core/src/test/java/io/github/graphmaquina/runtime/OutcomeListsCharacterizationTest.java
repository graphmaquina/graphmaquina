package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocGraphs;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.Keys;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the lists an applied outcome hands out, which the allocation trims of the runtime must keep: the records of a
 * one-hop call and of a call that cascades are immutable lists of the JDK's own classes, refuse a null probe as those
 * classes do, and an outcome with no starts lists none.
 */
class OutcomeListsCharacterizationTest {

    @Test
    void theRecordsOfAOneHopMoveAreAnImmutableListOfOne() {
        Outcome<Doc, DocStatus, DocTransition> outcome =
                machine(DocGraphs.plain()).execute(new Doc("1", DRAFT), SUBMIT);
        List<TransitionRecord<?, ?, ?>> records = outcome.records();
        assertEquals(1, records.size());
        assertEquals("java.util.ImmutableCollections$List12", records.getClass().getName());
        assertThrows(NullPointerException.class, () -> records.contains(null));
        assertThrows(UnsupportedOperationException.class, () -> records.add(records.getFirst()));
        assertEquals("java.util.ImmutableCollections$ListN", outcome.starts().getClass().getName());
    }

    @Test
    void theRecordsOfACascadeOfThreeHopsAreAnImmutableListOfThree() {
        Doc peer = new Doc("2", REVIEW);
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(DocGraphs.docs(submit -> submit.action("cascade",
                context -> {
                    context.cascade(Keys.DOCS, peer, PUBLISH);
                    context.cascade(Keys.DOCS, peer, ARCHIVE);
                    return ActionOutcome.ok();
                })));
        List<TransitionRecord<?, ?, ?>> records = machine.execute(new Doc("1", DRAFT), SUBMIT).records();
        assertEquals(3, records.size());
        assertEquals("java.util.ImmutableCollections$ListN", records.getClass().getName());
        assertThrows(NullPointerException.class, () -> records.contains(null));
    }

    private static StateMachine<Doc, DocStatus, DocTransition> machine(
            GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        return StateMachines.from(graph).state(Doc::status, Doc::setStatus).build();
    }
}
