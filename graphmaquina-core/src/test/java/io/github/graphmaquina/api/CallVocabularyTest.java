package io.github.graphmaquina.api;

import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.ItemTransition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The values a failed or observed call is described with: a call is an occurrence with identity equality, it names
 * its machine and its request but never its entity, and a failure only reports the states its phase can have
 * written.
 */
class CallVocabularyTest {

    private static MachineCall execute(Doc doc) {
        return new MachineCall(DOCS, doc, CallRequest.EXECUTE, Optional.of(DocTransition.SUBMIT),
                CallOrigin.APPLICATION);
    }

    private static MachineCall reevaluate(Doc doc, CallOrigin origin) {
        return new MachineCall(DOCS, doc, CallRequest.REEVALUATE, Optional.empty(), origin);
    }

    @Test
    void twoCallsThatAskTheSameThingOfTheSameEntityStayTwoCalls() {
        Doc doc = new Doc("1", DocStatus.DRAFT);
        MachineCall first = execute(doc);
        MachineCall second = execute(doc);
        assertNotEquals(first, second);
        Map<MachineCall, String> byCall = new HashMap<>();
        byCall.put(first, "first");
        byCall.put(second, "second");
        assertEquals(2, byCall.size());
        assertEquals("first", byCall.get(first));
    }

    @Test
    void aCallKeepsWhatItWasGiven() {
        Doc doc = new Doc("1", DocStatus.DRAFT);
        MachineCall call = execute(doc);
        assertEquals(DOCS, call.key());
        assertSame(doc, call.entity());
        assertEquals(Optional.of(DocTransition.SUBMIT), call.transition());
        MachineCall reaction = reevaluate(doc, CallOrigin.WATCH_REACTION);
        assertEquals(Optional.empty(), reaction.transition());
    }

    @Test
    void requestAndOriginAreReported() {
        Doc doc = new Doc("1", DocStatus.DRAFT);
        MachineCall executed = execute(doc);
        assertEquals(CallRequest.EXECUTE, executed.request());
        assertEquals(CallOrigin.APPLICATION, executed.origin());
        MachineCall reevaluated = reevaluate(doc, CallOrigin.APPLICATION);
        assertEquals(CallRequest.REEVALUATE, reevaluated.request());
        assertEquals(CallOrigin.APPLICATION, reevaluated.origin());
        MachineCall reaction = reevaluate(doc, CallOrigin.WATCH_REACTION);
        assertEquals(CallRequest.REEVALUATE, reaction.request());
        assertEquals(CallOrigin.WATCH_REACTION, reaction.origin());
        MachineCall advanced = new MachineCall(DOCS, doc, CallRequest.ADVANCE, Optional.empty(),
                CallOrigin.APPLICATION);
        assertEquals(CallRequest.ADVANCE, advanced.request());
        assertEquals(List.of("EXECUTE", "REEVALUATE", "ADVANCE"),
                Arrays.stream(CallRequest.values()).map(Enum::name).toList());
        MachineCall step = new MachineCall(DOCS, doc, CallRequest.ADVANCE, Optional.empty(),
                CallOrigin.AUTOMATIC_STEP);
        assertEquals(List.of(CallRequest.ADVANCE, CallOrigin.AUTOMATIC_STEP), List.of(step.request(), step.origin()));
        assertEquals(List.of("APPLICATION", "WATCH_REACTION", "AUTOMATIC_STEP"),
                Arrays.stream(CallOrigin.values()).map(Enum::name).toList());
    }

    @Test
    void executeNeedsATransitionAndOnlyExecuteHasOne() {
        Doc doc = new Doc("1", DocStatus.DRAFT);
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> new MachineCall(DOCS, doc, CallRequest.EXECUTE, Optional.empty(), CallOrigin.APPLICATION));
        assertEquals("an execute requests an identifier", missing.getMessage());
        IllegalArgumentException reevaluation = assertThrows(IllegalArgumentException.class,
                () -> new MachineCall(DOCS, doc, CallRequest.REEVALUATE, Optional.of(DocTransition.SUBMIT),
                        CallOrigin.APPLICATION));
        assertEquals("only an execute requests an identifier", reevaluation.getMessage());
        IllegalArgumentException advance = assertThrows(IllegalArgumentException.class,
                () -> new MachineCall(DOCS, doc, CallRequest.ADVANCE, Optional.of(DocTransition.SUBMIT),
                        CallOrigin.APPLICATION));
        assertEquals("only an execute requests an identifier", advance.getMessage());
    }

    @Test
    void aWatchReactionIsAReevaluation() {
        Doc doc = new Doc("1", DocStatus.DRAFT);
        IllegalArgumentException execute = assertThrows(IllegalArgumentException.class,
                () -> new MachineCall(DOCS, doc, CallRequest.EXECUTE, Optional.of(DocTransition.SUBMIT),
                        CallOrigin.WATCH_REACTION));
        assertEquals("a watch reaction is a reevaluation", execute.getMessage());
        IllegalArgumentException advance = assertThrows(IllegalArgumentException.class,
                () -> new MachineCall(DOCS, doc, CallRequest.ADVANCE, Optional.empty(), CallOrigin.WATCH_REACTION));
        assertEquals("a watch reaction is a reevaluation", advance.getMessage());
    }

    @Test
    void anAutomaticStepIsAnAdvance() {
        Doc doc = new Doc("1", DocStatus.DRAFT);
        IllegalArgumentException execute = assertThrows(IllegalArgumentException.class,
                () -> new MachineCall(DOCS, doc, CallRequest.EXECUTE, Optional.of(DocTransition.SUBMIT),
                        CallOrigin.AUTOMATIC_STEP));
        assertEquals("an automatic step is an advance", execute.getMessage());
        IllegalArgumentException reevaluate = assertThrows(IllegalArgumentException.class,
                () -> reevaluate(doc, CallOrigin.AUTOMATIC_STEP));
        assertEquals("an automatic step is an advance", reevaluate.getMessage());
    }

    @Test
    void toStringNamesRequestAndOrigin() {
        Doc doc = new Doc("secret", DocStatus.DRAFT);
        assertEquals("Doc/DocStatus SUBMIT", execute(doc).toString());
        assertEquals("Doc/DocStatus reevaluate", reevaluate(doc, CallOrigin.APPLICATION).toString());
        assertEquals("Doc/DocStatus reevaluate (watch reaction)",
                reevaluate(doc, CallOrigin.WATCH_REACTION).toString());
        assertEquals("Doc/DocStatus advance", new MachineCall(DOCS, doc, CallRequest.ADVANCE, Optional.empty(),
                CallOrigin.APPLICATION).toString());
        assertEquals("Doc/DocStatus advance (automatic step)", new MachineCall(DOCS, doc, CallRequest.ADVANCE,
                Optional.empty(), CallOrigin.AUTOMATIC_STEP).toString());
    }

    @Test
    void aCallRefusesNullsAndAForeignIdentifier() {
        Doc doc = new Doc("1", DocStatus.DRAFT);
        assertThrows(NullPointerException.class, () -> new MachineCall(null, doc, CallRequest.REEVALUATE,
                Optional.empty(), CallOrigin.APPLICATION));
        assertThrows(NullPointerException.class, () -> new MachineCall(DOCS, null, CallRequest.REEVALUATE,
                Optional.empty(), CallOrigin.APPLICATION));
        assertThrows(NullPointerException.class, () -> new MachineCall(DOCS, doc, null, Optional.empty(),
                CallOrigin.APPLICATION));
        assertThrows(NullPointerException.class, () -> new MachineCall(DOCS, doc, CallRequest.REEVALUATE, null,
                CallOrigin.APPLICATION));
        assertThrows(NullPointerException.class, () -> new MachineCall(DOCS, doc, CallRequest.REEVALUATE,
                Optional.empty(), null));
        IllegalArgumentException foreign = assertThrows(IllegalArgumentException.class,
                () -> new MachineCall(DOCS, doc, CallRequest.EXECUTE, Optional.of(ItemTransition.PACK),
                        CallOrigin.APPLICATION));
        assertEquals("transition must belong to io.github.graphmaquina.fixtures.DocTransition: PACK",
                foreign.getMessage());
    }

    @Test
    void thePhasesAndTheWriteLevelsAreTheDocumentedOnesInOrder() {
        assertEquals(List.of("RESOLVE", "GUARD", "ACTION", "COMMIT", "HOOK", "REACTIONS", "AUTOMATIC_STEPS"),
                Arrays.stream(CallPhase.values()).map(Enum::name).toList());
        assertEquals(List.of("NONE", "PARTIAL", "ALL"),
                Arrays.stream(StatesWritten.values()).map(Enum::name).toList());
    }

    @Test
    void aFailureReportsOnlyTheStatesItsPhaseCanHaveWritten() {
        IllegalStateException error = new IllegalStateException("boom");
        List<String> possible = new ArrayList<>();
        for (CallPhase phase : CallPhase.values()) {
            for (StatesWritten written : StatesWritten.values()) {
                try {
                    CallFailure failure = new CallFailure(phase, written, error);
                    assertSame(error, failure.error());
                    possible.add(phase + "/" + written);
                } catch (IllegalArgumentException refused) {
                    assertEquals("a call that failed in " + phase + " cannot have written " + written,
                            refused.getMessage());
                }
            }
        }
        assertEquals(List.of("RESOLVE/NONE", "GUARD/NONE", "ACTION/NONE", "COMMIT/NONE", "COMMIT/PARTIAL",
                "HOOK/ALL", "REACTIONS/ALL", "AUTOMATIC_STEPS/ALL"), possible);
    }

    @Test
    void aFailureRefusesNulls() {
        IllegalStateException error = new IllegalStateException("boom");
        assertThrows(NullPointerException.class, () -> new CallFailure(null, StatesWritten.NONE, error));
        assertThrows(NullPointerException.class, () -> new CallFailure(CallPhase.ACTION, null, error));
        assertThrows(NullPointerException.class, () -> new CallFailure(CallPhase.ACTION, StatesWritten.NONE, null));
    }
}
