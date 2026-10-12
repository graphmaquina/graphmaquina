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
        calls.put("Hop.hop", () -> new FlowEntry.Hop(null, Optional.empty(), AT));
        calls.put("Hop.recovery", () -> new FlowEntry.Hop(record(doc), null, AT));
        calls.put("Hop.at", () -> new FlowEntry.Hop(record(doc), Optional.empty(), null));
        calls.put("Recovery.failed", () -> new FlowEntry.Recovery(null, "r", Optional.empty(), Optional.empty()));
        calls.put("Recovery.reason", () -> new FlowEntry.Recovery(SUBMIT, null, Optional.empty(), Optional.empty()));
        calls.put("Recovery.cause", () -> new FlowEntry.Recovery(SUBMIT, "r", null, Optional.empty()));
        calls.put("Recovery.kind", () -> new FlowEntry.Recovery(SUBMIT, "r", Optional.empty(), null));
        calls.put("Start.start", () -> new FlowEntry.Start(null, AT));
        calls.put("Start.at", () -> new FlowEntry.Start(start(doc, new Doc("2", DRAFT)), null));
        calls.put("Rejected.key", () -> new FlowEntry.Rejected(null, doc, DRAFT, SUBMIT, RejectionKind.NO_TRANSITION,
                "r", Optional.empty(), AT));
        calls.put("Rejected.entity", () -> new FlowEntry.Rejected(DOCS, null, DRAFT, SUBMIT,
                RejectionKind.NO_TRANSITION, "r", Optional.empty(), AT));
        calls.put("Rejected.from", () -> new FlowEntry.Rejected(DOCS, doc, null, SUBMIT, RejectionKind.NO_TRANSITION,
                "r", Optional.empty(), AT));
        calls.put("Rejected.transition", () -> new FlowEntry.Rejected(DOCS, doc, DRAFT, null,
                RejectionKind.NO_TRANSITION, "r", Optional.empty(), AT));
        calls.put("Rejected.kind", () -> new FlowEntry.Rejected(DOCS, doc, DRAFT, SUBMIT, null, "r", Optional.empty(),
                AT));
        calls.put("Rejected.reason", () -> new FlowEntry.Rejected(DOCS, doc, DRAFT, SUBMIT,
                RejectionKind.NO_TRANSITION, null, Optional.empty(), AT));
        calls.put("Rejected.guard", () -> new FlowEntry.Rejected(DOCS, doc, DRAFT, SUBMIT,
                RejectionKind.NO_TRANSITION, "r", null, AT));
        calls.put("Rejected.at", () -> new FlowEntry.Rejected(DOCS, doc, DRAFT, SUBMIT, RejectionKind.NO_TRANSITION,
                "r", Optional.empty(), null));
        calls.put("Failed.failure", () -> new FlowEntry.Failed(null, AT));
        calls.put("Failed.at", () -> new FlowEntry.Failed(failure, null));
        for (Map.Entry<String, Executable> call : calls.entrySet()) {
            String component = call.getKey().substring(call.getKey().indexOf('.') + 1);
            NullPointerException error = assertThrows(NullPointerException.class, call.getValue(), call.getKey());
            assertTrue(error.getMessage().startsWith(component + " "), call.getKey() + ": " + error.getMessage());
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
