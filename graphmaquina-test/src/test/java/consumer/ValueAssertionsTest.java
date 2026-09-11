package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.test.ActionOutcomeAssert;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.GuardResultAssert;
import io.github.graphmaquina.test.internal.Checks;
import org.junit.jupiter.api.Test;

class ValueAssertionsTest {

    @Test
    void failedActionDoesNotSatisfyOk() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> ActionOutcomeAssert.assertThat(ActionOutcome.failed("declined")).isOk());
        assertEquals("kind", error.property());
        assertEquals("Ok", error.expected());
        assertEquals("Failed", error.actual());
    }

    @Test
    void guardReasonIsComparedExactly() {
        GuardResultAssert.assertThat(GuardResult.reject("not ready"))
                .isRejected().hasReason("not ready");
        assertThrows(GraphAssertionError.class,
                () -> GuardResultAssert.assertThat(GuardResult.reject("not ready"))
                        .hasReason("ready"));
    }

    @Test
    void okActionSatisfiesOk() {
        ActionOutcomeAssert view = ActionOutcomeAssert.assertThat(ActionOutcome.ok());
        assertSame(view, view.isOk());
    }

    @Test
    void failedActionSatisfiesFailed() {
        ActionOutcomeAssert view = ActionOutcomeAssert.assertThat(ActionOutcome.failed("declined"));
        assertSame(view, view.isFailed());
    }

    @Test
    void okActionDoesNotSatisfyFailed() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> ActionOutcomeAssert.assertThat(ActionOutcome.ok()).isFailed());
        assertFailure(error, "action outcome", "kind", "Failed", "Ok");
    }

    @Test
    void passGuardSatisfiesPass() {
        GuardResultAssert view = GuardResultAssert.assertThat(GuardResult.pass());
        assertSame(view, view.isPass());
    }

    @Test
    void rejectedGuardSatisfiesRejected() {
        GuardResultAssert view = GuardResultAssert.assertThat(GuardResult.reject("not ready"));
        assertSame(view, view.isRejected());
    }

    @Test
    void rejectedGuardDoesNotSatisfyPass() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GuardResultAssert.assertThat(GuardResult.reject("not ready")).isPass());
        assertFailure(error, "guard result", "kind", "Pass", "Reject");
    }

    @Test
    void passGuardDoesNotSatisfyRejected() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GuardResultAssert.assertThat(GuardResult.pass()).isRejected());
        assertFailure(error, "guard result", "kind", "Reject", "Pass");
    }

    @Test
    void actionReasonIsComparedExactly() {
        ActionOutcomeAssert view = ActionOutcomeAssert.assertThat(ActionOutcome.failed("declined"));
        assertSame(view, view.isFailed().hasReason("declined"));
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> ActionOutcomeAssert.assertThat(ActionOutcome.failed("declined")).hasReason("approved"));
        assertFailure(error, "action outcome", "reason", "approved", "declined");
    }

    @Test
    void guardReasonMismatchReportsTheReasonProperty() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GuardResultAssert.assertThat(GuardResult.reject("not ready")).hasReason("ready"));
        assertFailure(error, "guard result", "reason", "ready", "not ready");
    }

    @Test
    void okActionHasNoReasonSoItFailsOnKind() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> ActionOutcomeAssert.assertThat(ActionOutcome.ok()).hasReason("declined"));
        assertFailure(error, "action outcome", "kind", "Failed", "Ok");
    }

    @Test
    void passGuardHasNoReasonSoItFailsOnKind() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GuardResultAssert.assertThat(GuardResult.pass()).hasReason("not ready"));
        assertFailure(error, "guard result", "kind", "Reject", "Pass");
    }

    @Test
    void sameComparesByReferenceNotByValue() {
        ActionOutcome first = ActionOutcome.failed("declined");
        ActionOutcome second = ActionOutcome.failed("declined");
        assertEquals(first, second);
        assertNotSame(first, second);
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> Checks.same("entity", "identity", first, second));
        assertEquals("entity", error.context());
        assertEquals("identity", error.property());
        assertSame(first, error.expected());
        assertSame(second, error.actual());
        Checks.same("entity", "identity", first, first);
    }

    @Test
    void failureRendersItsContextPropertyExpectedAndActual() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> ActionOutcomeAssert.assertThat(ActionOutcome.failed("declined")).isOk());
        assertEquals("action outcome", error.context());
        assertEquals("action outcome: kind expected <Ok> but was <Failed>", error.getMessage());
    }

    @Test
    void nullActionOutcomeFailsIsOkWithContext() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> ActionOutcomeAssert.assertThat(null).isOk());
        assertFailure(error, "action outcome", "kind", "Ok", null);
    }

    @Test
    void nullActionOutcomeFailsIsFailedWithContext() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> ActionOutcomeAssert.assertThat(null).isFailed());
        assertFailure(error, "action outcome", "kind", "Failed", null);
    }

    @Test
    void nullActionOutcomeFailsHasReasonWithContext() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> ActionOutcomeAssert.assertThat(null).hasReason("declined"));
        assertFailure(error, "action outcome", "kind", "Failed", null);
    }

    @Test
    void nullGuardResultFailsIsPassWithContext() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GuardResultAssert.assertThat(null).isPass());
        assertFailure(error, "guard result", "kind", "Pass", null);
    }

    @Test
    void nullGuardResultFailsIsRejectedWithContext() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GuardResultAssert.assertThat(null).isRejected());
        assertFailure(error, "guard result", "kind", "Reject", null);
    }

    @Test
    void nullGuardResultFailsHasReasonWithContext() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GuardResultAssert.assertThat(null).hasReason("not ready"));
        assertFailure(error, "guard result", "kind", "Reject", null);
    }

    @Test
    void nullExpectedReasonStaysAnArgumentError() {
        NullPointerException action = assertThrows(NullPointerException.class,
                () -> ActionOutcomeAssert.assertThat(ActionOutcome.failed("declined")).hasReason(null));
        assertEquals("reason", action.getMessage());
        NullPointerException guard = assertThrows(NullPointerException.class,
                () -> GuardResultAssert.assertThat(GuardResult.reject("not ready")).hasReason(null));
        assertEquals("reason", guard.getMessage());
    }

    @Test
    void nullExpectedReasonBeatsANullSubject() {
        NullPointerException action = assertThrows(NullPointerException.class,
                () -> ActionOutcomeAssert.assertThat(null).hasReason(null));
        assertEquals("reason", action.getMessage());
        NullPointerException guard = assertThrows(NullPointerException.class,
                () -> GuardResultAssert.assertThat(null).hasReason(null));
        assertEquals("reason", guard.getMessage());
    }

    @Test
    void nullExpectedIsAnArgumentErrorInBothComparisons() {
        NullPointerException equalError = assertThrows(NullPointerException.class,
                () -> Checks.equal("entity", "identity", null, "declined"));
        assertEquals("expected", equalError.getMessage());
        NullPointerException sameError = assertThrows(NullPointerException.class,
                () -> Checks.same("entity", "identity", null, "declined"));
        assertEquals("expected", sameError.getMessage());
    }

    @Test
    void reasonsThatDifferByASingleCharacterDoNotMatch() {
        GraphAssertionError prefix = assertThrows(GraphAssertionError.class,
                () -> ActionOutcomeAssert.assertThat(ActionOutcome.failed("declined")).hasReason("declined "));
        assertFailure(prefix, "action outcome", "reason", "declined ", "declined");
        GraphAssertionError truncated = assertThrows(GraphAssertionError.class,
                () -> ActionOutcomeAssert.assertThat(ActionOutcome.failed("declined")).hasReason("decline"));
        assertFailure(truncated, "action outcome", "reason", "decline", "declined");
        GraphAssertionError casing = assertThrows(GraphAssertionError.class,
                () -> GuardResultAssert.assertThat(GuardResult.reject("not ready")).hasReason("not Ready"));
        assertFailure(casing, "guard result", "reason", "not Ready", "not ready");
    }

    @Test
    void identityMismatchNamesBothInstancesInsteadOfRepeatingOneRendering() {
        ActionOutcome first = ActionOutcome.failed("declined");
        ActionOutcome second = ActionOutcome.failed("declined");
        assertEquals(String.valueOf(first), String.valueOf(second));
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> Checks.same("entity", "identity", first, second));
        String message = error.getMessage();
        assertTrue(message.startsWith("entity: identity expected <" + first + "> but was <" + second + ">"),
                message);
        assertTrue(message.contains(Integer.toHexString(System.identityHashCode(first))), message);
        assertTrue(message.contains(Integer.toHexString(System.identityHashCode(second))), message);
    }

    private static void assertFailure(GraphAssertionError error, String context, String property,
            Object expected, Object actual) {
        assertEquals(context, error.context());
        assertEquals(property, error.property());
        assertEquals(expected, error.expected());
        assertEquals(actual, error.actual());
    }
}
