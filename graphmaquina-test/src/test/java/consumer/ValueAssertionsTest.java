package consumer;

import static consumer.GraphAssertionErrors.assertFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.test.ActionOutcomeAssert;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.GuardResultAssert;
import io.github.graphmaquina.test.OutcomeAssert;
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import io.github.graphmaquina.test.internal.Checks;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Contract of {@link ActionOutcomeAssert} and {@link GuardResultAssert}, and of the {@link GraphAssertionError}
 * a failed check raises. Kind and reason are compared exactly; a null subject fails as an assertion while a
 * null expected value stays an argument error, even over a null subject; two distinct instances that render
 * alike are named by identity; a value whose {@code toString()} throws or returns null still fails as an
 * assertion; and a throwable reported as the actual value is chained as the cause.
 */
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
        assertEquals("reason is marked non-null but is null", action.getMessage());
        NullPointerException guard = assertThrows(NullPointerException.class,
                () -> GuardResultAssert.assertThat(GuardResult.reject("not ready")).hasReason(null));
        assertEquals("reason is marked non-null but is null", guard.getMessage());
    }

    @Test
    void nullExpectedReasonBeatsANullSubject() {
        NullPointerException action = assertThrows(NullPointerException.class,
                () -> ActionOutcomeAssert.assertThat(null).hasReason(null));
        assertEquals("reason is marked non-null but is null", action.getMessage());
        NullPointerException guard = assertThrows(NullPointerException.class,
                () -> GuardResultAssert.assertThat(null).hasReason(null));
        assertEquals("reason is marked non-null but is null", guard.getMessage());
    }

    @Test
    void nullExpectedIsAnArgumentErrorInBothComparisons() {
        NullPointerException equalError = assertThrows(NullPointerException.class,
                () -> Checks.equal("entity", "identity", null, "declined"));
        assertEquals("expected is marked non-null but is null", equalError.getMessage());
        NullPointerException sameError = assertThrows(NullPointerException.class,
                () -> Checks.same("entity", "identity", null, "declined"));
        assertEquals("expected is marked non-null but is null", sameError.getMessage());
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

    // --- a value that cannot be rendered, and a throwable that is the discrepancy ---------------------

    /**
     * A lazily loaded entity fails its {@code toString()} outside a session. A check over such a value that
     * fails must still report itself as an assertion, naming the instance, instead of surfacing that failure.
     */
    @Test
    void aValueWhoseToStringThrowsStillFailsAsAnAssertion() {
        Unprintable expected = new Unprintable();
        Unprintable actual = new Unprintable();
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> Checks.same("entity", "identity", expected, actual));
        assertEquals("entity", error.context());
        assertEquals("identity", error.property());
        assertSame(expected, error.expected());
        assertSame(actual, error.actual());
        String message = error.getMessage();
        assertTrue(message.contains(Unprintable.class.getName() + "@"
                + Integer.toHexString(System.identityHashCode(expected))), message);
        assertTrue(message.contains(Unprintable.class.getName() + "@"
                + Integer.toHexString(System.identityHashCode(actual))), message);
        assertTrue(message.contains("toString() threw java.lang.IllegalStateException: " + Unprintable.FAILURE),
                message);
    }

    @Test
    void aValueWhoseToStringThrowsACheckedExceptionStillFailsAsAnAssertion() {
        Rendered expected = new Rendered(() -> {
            throw ValueAssertionsTest.<RuntimeException>sneaky(new IOException("disk gone"));
        });
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> Checks.same("entity", "identity", expected, new Rendered(() -> "actual")));
        assertSame(expected, error.expected());
        assertTrue(error.getMessage().contains("toString() threw java.io.IOException: disk gone"), error.getMessage());
    }

    @Test
    void aValueWhoseToStringThrowsAFailureThatCannotBePrintedEitherStillFailsAsAnAssertion() {
        Rendered expected = new Rendered(() -> {
            throw new UnprintableFailure();
        });
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> Checks.same("entity", "identity", expected, new Rendered(() -> "actual")));
        assertTrue(error.getMessage().contains(Rendered.class.getName() + "@"
                + Integer.toHexString(System.identityHashCode(expected)) + " (toString() threw "
                + UnprintableFailure.class.getName() + ")"), error.getMessage());
    }

    @Test
    void aValueWhoseToStringIsInterruptedStillFailsAsAnAssertionAndKeepsTheInterrupt() {
        Rendered expected = new Rendered(() -> {
            if (Thread.interrupted()) {
                throw ValueAssertionsTest.<RuntimeException>sneaky(new InterruptedException("interrupted"));
            }
            return "expected";
        });
        Thread.currentThread().interrupt();
        GraphAssertionError error;
        boolean interrupted;
        try {
            error = assertThrows(GraphAssertionError.class,
                    () -> Checks.same("entity", "identity", expected, new Rendered(() -> "actual")));
        } finally {
            interrupted = Thread.interrupted();
        }
        assertTrue(error.getMessage().contains("toString() threw java.lang.InterruptedException: interrupted"),
                error.getMessage());
        assertTrue(interrupted, "the thread lost its interrupt");
    }

    /**
     * {@code String.valueOf(value)} is null when {@code value.toString()} returns null, so a rendering that
     * trusted it threw a {@link NullPointerException} while it compared the two renderings of a failed check.
     */
    @Test
    void aValueWhoseToStringReturnsNullStillFailsAsAnAssertion() {
        NullPrinting expected = new NullPrinting();
        NullPrinting actual = new NullPrinting();
        GraphAssertionError both = assertThrows(GraphAssertionError.class,
                () -> Checks.same("entity", "identity", expected, actual));
        assertSame(expected, both.expected());
        assertSame(actual, both.actual());
        assertTrue(both.getMessage().contains(NullPrinting.shown(expected)), both.getMessage());
        assertTrue(both.getMessage().contains(NullPrinting.shown(actual)), both.getMessage());
        GraphAssertionError onlyExpected = assertThrows(GraphAssertionError.class,
                () -> Checks.same("entity", "identity", expected, "text"));
        assertEquals("entity: identity expected <" + NullPrinting.shown(expected) + "> but was <text>",
                onlyExpected.getMessage());
        GraphAssertionError onlyActual = assertThrows(GraphAssertionError.class,
                () -> Checks.equal("entity", "name", "text", actual));
        assertEquals("entity: name expected <text> but was <" + NullPrinting.shown(actual) + ">",
                onlyActual.getMessage());
    }

    @Test
    void aViewFailsAsAnAssertionOverAnEntityThatCannotBeRendered() {
        Unprintable loaded = new Unprintable();
        Unprintable other = new Unprintable();
        Outcome<Unprintable, State, Id> outcome = new Outcome.Applied<>(loaded, State.NEW, Id.PREPARE,
                State.READY, List.of(), List.of());
        OutcomeAssert.assertThat(outcome).hasEntitySameAs(loaded);
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).hasEntitySameAs(other));
        assertEquals("outcome", error.context());
        assertEquals("entity", error.property());
        assertSame(other, error.expected());
        assertSame(loaded, error.actual());
    }

    /**
     * An unexpected throwable is the discrepancy, and it is also why the check failed, so it is chained as the
     * cause: a test report prints where it was thrown instead of only what it was called.
     */
    @Test
    void aThrowableReportedAsTheActualValueIsChainedAsTheCause() {
        RuntimeException thrown = deepInsideTheAction();
        GraphAssertionError error = new GraphAssertionError("async test", "outcome", "normal result", thrown);
        assertSame(thrown, error.actual());
        assertSame(thrown, error.getCause());
        StringWriter printed = new StringWriter();
        error.printStackTrace(new PrintWriter(printed));
        assertTrue(printed.toString().contains("Caused by: " + thrown), printed.toString());
        assertTrue(printed.toString().contains("deepInsideTheAction"), printed.toString());
    }

    @Test
    void anActualValueThatIsNotAThrowableChainsNoCause() {
        assertNull(new GraphAssertionError("c", "p", "expected", "actual").getCause());
        assertNull(new GraphAssertionError("c", "p", new IllegalStateException("only expected"), "actual")
                .getCause());
        assertNull(new GraphAssertionError("c", "p", "expected", null).getCause());
    }

    private static RuntimeException deepInsideTheAction() {
        return new IllegalStateException("customer.address is null");
    }

    /** An entity whose {@code toString()} returns null, which {@code String.valueOf} hands on as null. */
    private static final class NullPrinting {

        /** What a message is expected to show for one: its class and identity, and that it printed nothing. */
        static String shown(NullPrinting value) {
            return NullPrinting.class.getName() + "@" + Integer.toHexString(System.identityHashCode(value))
                    + " (toString() returned null)";
        }

        @Override
        public String toString() {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <X extends Throwable> RuntimeException sneaky(Throwable thrown) throws X {
        throw (X) thrown;
    }

    /** A value that renders as its supplier answers or throws. */
    private static final class Rendered {

        private final Supplier<String> text;

        Rendered(Supplier<String> text) {
            this.text = text;
        }

        @Override
        public String toString() {
            return text.get();
        }
    }

    /** A failure whose own rendering fails, since its message cannot be read. */
    private static final class UnprintableFailure extends RuntimeException {

        @Override
        public String getMessage() {
            throw new IllegalStateException("the message cannot be read");
        }
    }

    /** An entity whose rendering fails, as a persistence proxy's does once its session is gone. */
    private static final class Unprintable {

        static final String FAILURE = "could not initialize proxy - no Session";

        @Override
        public String toString() {
            throw new IllegalStateException(FAILURE);
        }
    }
}
