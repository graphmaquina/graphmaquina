package io.github.graphmaquina.api;

import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void twoCallsThatAskTheSameThingOfTheSameEntityStayTwoCalls() {
        Doc doc = new Doc("1", DocStatus.DRAFT);
        MachineCall first = new MachineCall(DOCS, doc, Optional.of(DocTransition.SUBMIT), false);
        MachineCall second = new MachineCall(DOCS, doc, Optional.of(DocTransition.SUBMIT), false);
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
        MachineCall call = new MachineCall(DOCS, doc, Optional.of(DocTransition.SUBMIT), false);
        assertEquals(DOCS, call.key());
        assertSame(doc, call.entity());
        assertEquals(Optional.of(DocTransition.SUBMIT), call.transition());
        assertFalse(call.reaction());
        MachineCall reaction = new MachineCall(DOCS, doc, Optional.empty(), true);
        assertEquals(Optional.empty(), reaction.transition());
        assertTrue(reaction.reaction());
    }

    @Test
    void aCallNamesItsMachineAndItsRequestButNeverItsEntity() {
        Doc doc = new Doc("secret", DocStatus.DRAFT);
        assertEquals("Doc/DocStatus SUBMIT",
                new MachineCall(DOCS, doc, Optional.of(DocTransition.SUBMIT), false).toString());
        assertEquals("Doc/DocStatus reevaluate", new MachineCall(DOCS, doc, Optional.empty(), false).toString());
        assertEquals("Doc/DocStatus reevaluate (watch reaction)",
                new MachineCall(DOCS, doc, Optional.empty(), true).toString());
    }

    @Test
    void aCallRefusesNullsAForeignIdentifierAndAReactionThatRequestsOne() {
        Doc doc = new Doc("1", DocStatus.DRAFT);
        assertThrows(NullPointerException.class, () -> new MachineCall(null, doc, Optional.empty(), false));
        assertThrows(NullPointerException.class, () -> new MachineCall(DOCS, null, Optional.empty(), false));
        assertThrows(NullPointerException.class, () -> new MachineCall(DOCS, doc, null, false));
        IllegalArgumentException foreign = assertThrows(IllegalArgumentException.class,
                () -> new MachineCall(DOCS, doc, Optional.of(ItemTransition.PACK), false));
        assertEquals("transition must belong to io.github.graphmaquina.fixtures.DocTransition: PACK",
                foreign.getMessage());
        IllegalArgumentException reaction = assertThrows(IllegalArgumentException.class,
                () -> new MachineCall(DOCS, doc, Optional.of(DocTransition.SUBMIT), true));
        assertEquals("a watch reaction is a reevaluation and requests no identifier", reaction.getMessage());
    }

    @Test
    void thePhasesAndTheWriteLevelsAreTheDocumentedOnesInOrder() {
        assertEquals(List.of("RESOLVE", "GUARD", "ACTION", "COMMIT", "HOOK", "REACTIONS"),
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
                "HOOK/ALL", "REACTIONS/ALL"), possible);
    }

    @Test
    void aFailureRefusesNulls() {
        IllegalStateException error = new IllegalStateException("boom");
        assertThrows(NullPointerException.class, () -> new CallFailure(null, StatesWritten.NONE, error));
        assertThrows(NullPointerException.class, () -> new CallFailure(CallPhase.ACTION, null, error));
        assertThrows(NullPointerException.class, () -> new CallFailure(CallPhase.ACTION, StatesWritten.NONE, null));
    }
}
