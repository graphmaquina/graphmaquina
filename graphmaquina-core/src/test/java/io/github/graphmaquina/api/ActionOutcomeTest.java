package io.github.graphmaquina.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The failure kind a failed {@link ActionOutcome} carries: the one the action named, or none, validated alike by the
 * factory and by the canonical constructor.
 */
class ActionOutcomeTest {

    private static final String INVALID = " is not a failure kind; a failure kind is a named final class or record"
            + " that implements FailureKind and is not an interface, an enum, a local or anonymous class, or a"
            + " Throwable";

    /** A failure kind declared the canonical way. */
    record Declined() implements FailureKind { }

    /** Concrete, but open to subclasses: not a failure kind. */
    static class OpenKind implements FailureKind { }

    @Test
    void failedWithAKindCarriesItBesideTheReason() {
        ActionOutcome.Failed failed = assertInstanceOf(ActionOutcome.Failed.class,
                ActionOutcome.failed(Declined.class, "card declined"));
        assertEquals("card declined", failed.reason());
        assertEquals(Optional.of(Declined.class), failed.failureKind());
        assertEquals(new ActionOutcome.Failed("card declined", Optional.of(Declined.class)), failed);
    }

    @Test
    void failedWithoutAKindCarriesNone() {
        ActionOutcome.Failed failed = assertInstanceOf(ActionOutcome.Failed.class, ActionOutcome.failed("declined"));
        assertEquals(Optional.empty(), failed.failureKind());
        assertEquals(new ActionOutcome.Failed("declined"), failed);
        assertEquals(new ActionOutcome.Failed("declined", Optional.empty()), failed);
        assertNotEquals(ActionOutcome.failed(Declined.class, "declined"), failed);
    }

    @Test
    void theFactoryAndTheCanonicalConstructorRefuseAnInvalidKind() {
        IllegalArgumentException byFactory = assertThrows(IllegalArgumentException.class,
                () -> ActionOutcome.failed(OpenKind.class, "declined"));
        assertEquals(OpenKind.class.getName() + INVALID, byFactory.getMessage());
        IllegalArgumentException byConstructor = assertThrows(IllegalArgumentException.class,
                () -> new ActionOutcome.Failed("declined", Optional.of(OpenKind.class)));
        assertEquals(OpenKind.class.getName() + INVALID, byConstructor.getMessage());
    }

    /** Only raw types get a class that does not implement FailureKind this far: it is refused all the same. */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void aRawClassThatIsNotAKindIsRefused() {
        Class raw = String.class;
        assertThrows(IllegalArgumentException.class, () -> ActionOutcome.failed(raw, "declined"));
        Optional forged = Optional.of(String.class);
        assertThrows(IllegalArgumentException.class, () -> new ActionOutcome.Failed("declined", forged));
    }

    @Test
    void aNullKindOptionalOrReasonIsRefusedWithItsName() {
        NullPointerException kind = assertThrows(NullPointerException.class,
                () -> ActionOutcome.failed(null, "declined"));
        assertTrue(kind.getMessage().startsWith("kind"), kind.getMessage());
        NullPointerException optional = assertThrows(NullPointerException.class,
                () -> new ActionOutcome.Failed("declined", null));
        assertTrue(optional.getMessage().startsWith("failureKind"), optional.getMessage());
        NullPointerException reason = assertThrows(NullPointerException.class,
                () -> ActionOutcome.failed(Declined.class, null));
        assertTrue(reason.getMessage().startsWith("reason"), reason.getMessage());
    }

    @Test
    void aBlankReasonIsRefusedWithAKindToo() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ActionOutcome.failed(Declined.class, " "));
        assertEquals("reason must not be blank", error.getMessage());
    }
}
