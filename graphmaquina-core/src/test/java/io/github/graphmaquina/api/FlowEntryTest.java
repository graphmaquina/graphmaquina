package io.github.graphmaquina.api;

import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.fixtures.Doc;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The entries of a trail are values: each refuses a null component, naming it, and compares by its components. A
 * {@code Failed} entry prints its failure's exception as that exception prints itself.
 */
class FlowEntryTest {

    private static final Instant AT = Instant.ofEpochSecond(1);

    private static TransitionRecord<Doc, ?, ?> record(Doc doc) {
        return new TransitionRecord<>(DOCS, doc, DRAFT, SUBMIT, REVIEW, TransitionKind.MOVE, Optional.empty(), 0);
    }

    private static SubmachineStart<Doc, ?, ?> start(Doc child, Doc parent) {
        return new SubmachineStart<>(DOCS, child, DRAFT, DRAFT, DOCS, parent, REVIEW, 0);
    }

    @Test
    void everyEntryRefusesANullComponentByName() {
        Doc doc = new Doc("1", DRAFT);
        CallFailure failure = new CallFailure(CallPhase.ACTION, StatesWritten.NONE, new IllegalStateException("x"));
        Map<String, Executable> calls = new LinkedHashMap<>();
        calls.put("hop", () -> new FlowEntry.Hop(null, Optional.empty(), AT));
        calls.put("recovery", () -> new FlowEntry.Hop(record(doc), null, AT));
        calls.put("at", () -> new FlowEntry.Hop(record(doc), Optional.empty(), null));
        calls.put("failed", () -> new FlowEntry.Recovery(null, "r", Optional.empty(), Optional.empty()));
        calls.put("reason", () -> new FlowEntry.Recovery(SUBMIT, null, Optional.empty(), Optional.empty()));
        calls.put("cause", () -> new FlowEntry.Recovery(SUBMIT, "r", null, Optional.empty()));
        calls.put("kind", () -> new FlowEntry.Recovery(SUBMIT, "r", Optional.empty(), null));
        calls.put("start", () -> new FlowEntry.Start(null, AT));
        calls.put("key", () -> new FlowEntry.Rejected(null, doc, DRAFT, SUBMIT, RejectionKind.NO_TRANSITION, "r",
                Optional.empty(), AT));
        calls.put("entity", () -> new FlowEntry.Rejected(DOCS, null, DRAFT, SUBMIT, RejectionKind.NO_TRANSITION, "r",
                Optional.empty(), AT));
        calls.put("guard", () -> new FlowEntry.Rejected(DOCS, doc, DRAFT, SUBMIT, RejectionKind.NO_TRANSITION, "r",
                null, AT));
        calls.put("failure", () -> new FlowEntry.Failed(null, AT));
        for (Map.Entry<String, Executable> call : calls.entrySet()) {
            NullPointerException error = assertThrows(NullPointerException.class, call.getValue(), call.getKey());
            assertTrue(error.getMessage().startsWith(call.getKey() + " "), error.getMessage());
        }
    }

    @Test
    void entriesCompareByTheirComponents() {
        Doc doc = new Doc("1", DRAFT);
        FlowEntry.Recovery recovery = new FlowEntry.Recovery(SUBMIT, "no reviewer", Optional.empty(), Optional.empty());
        assertEquals(new FlowEntry.Hop(record(doc), Optional.of(recovery), AT),
                new FlowEntry.Hop(record(doc), Optional.of(recovery), Instant.ofEpochSecond(1)));
        assertNotEquals(new FlowEntry.Hop(record(doc), Optional.of(recovery), AT),
                new FlowEntry.Hop(record(doc), Optional.empty(), AT));
        assertNotEquals(new FlowEntry.Hop(record(doc), Optional.empty(), AT),
                new FlowEntry.Hop(record(doc), Optional.empty(), Instant.ofEpochSecond(2)));
        assertEquals(new FlowEntry.Start(start(doc, doc), AT), new FlowEntry.Start(start(doc, doc), AT));
        assertEquals(new FlowEntry.Rejected(DOCS, doc, DRAFT, SUBMIT, RejectionKind.GUARD, "closed",
                        Optional.of("Ready"), AT),
                new FlowEntry.Rejected(DOCS, doc, DRAFT, SUBMIT, RejectionKind.GUARD, "closed",
                        Optional.of("Ready"), AT));
        assertEquals(AT, new FlowEntry.Start(start(doc, doc), AT).at());
    }

    @Test
    void aFailedEntryPrintsItsExceptionAsTheExceptionDoes() {
        IllegalStateException error = new IllegalStateException("reviewer unavailable");
        FlowEntry.Failed failed = new FlowEntry.Failed(
                new CallFailure(CallPhase.ACTION, StatesWritten.NONE, error), AT);
        assertEquals("Failed[failure=CallFailure[phase=ACTION, statesWritten=NONE, error=" + error + "], at=" + AT
                + "]", failed.toString());
    }
}
