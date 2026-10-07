package io.github.graphmaquina.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.error.StateMachineException;
import io.github.graphmaquina.internal.FailureTypes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.time.Month;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

/**
 * What a {@link RecoveryRule} keeps, and what its constructor refuses: an invalid failure kind, a forbidden or
 * non-throwable type, a type declared twice within the rule, and nulls.
 */
class RecoveryRuleTest {

    private static final String WHERE = "recover(" + RecoveryRuleTest.Unavailable.class.getName() + ", MARCH)";

    /** A failure kind declared the canonical way. */
    record Unavailable() implements FailureKind { }

    /** Concrete, but open to subclasses: not a failure kind. */
    static class OpenKind implements FailureKind { }

    @Test
    void aRuleKeepsItsKindItsTransitionAndAReadOnlyCopyOfItsTypes() {
        List<Class<? extends Throwable>> source = new ArrayList<>(List.of(IOException.class));
        RecoveryRule<Month> rule = new RecoveryRule<>(Unavailable.class, Month.MARCH, source);
        source.add(UncheckedIOException.class);
        assertEquals(Unavailable.class, rule.kind());
        assertEquals(Month.MARCH, rule.transition());
        assertEquals(List.of(IOException.class), rule.failsOn());
        assertThrows(UnsupportedOperationException.class, () -> rule.failsOn().add(UncheckedIOException.class));
        assertEquals(new RecoveryRule<>(Unavailable.class, Month.MARCH, List.of(IOException.class)), rule);
    }

    @Test
    void aRuleMayDeclareNoThrowableType() {
        assertEquals(List.of(), new RecoveryRule<>(Unavailable.class, Month.MARCH, List.of()).failsOn());
    }

    /** Only the same class twice is a repetition: a supertype and its subtype may share a rule. */
    @Test
    void aSupertypeAndItsSubtypeMayShareARule() {
        assertEquals(List.of(IOException.class, SocketTimeoutException.class), new RecoveryRule<>(Unavailable.class,
                Month.MARCH, List.of(IOException.class, SocketTimeoutException.class)).failsOn());
    }

    @Test
    void anInvalidKindIsRefused() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new RecoveryRule<>(OpenKind.class, Month.MARCH, List.of()));
        assertEquals(FailureTypes.invalidKindMessage(OpenKind.class), error.getMessage());
    }

    /** Only a raw Class gets a class that does not implement FailureKind this far: it is refused all the same. */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void aRawClassThatIsNotAKindIsRefused() {
        Class raw = String.class;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new RecoveryRule<Month>(raw, Month.MARCH, List.of()));
        assertEquals(FailureTypes.invalidKindMessage(String.class), error.getMessage());
    }

    @Test
    void aForbiddenThrowableTypeIsRefused() {
        List<Class<? extends Throwable>> forbidden = List.of(Throwable.class, Exception.class, RuntimeException.class,
                Error.class, OutOfMemoryError.class, StateMachineException.class, CancellationException.class,
                CompletionException.class, ExecutionException.class);
        for (Class<? extends Throwable> type : forbidden) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> new RecoveryRule<>(Unavailable.class, Month.MARCH, List.of(type)));
            assertEquals(WHERE + " does not allow " + type.getName() + "; " + FailureTypes.FORBIDDEN_THROWABLE_RULE,
                    error.getMessage());
        }
    }

    /** Only a raw List gets a class that is not a Throwable this far: it is refused all the same. */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void aClassThatIsNotAThrowableIsRefused() {
        List raw = List.of(String.class);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new RecoveryRule<Month>(Unavailable.class, Month.MARCH, raw));
        assertEquals(WHERE + " does not allow java.lang.String; " + FailureTypes.FORBIDDEN_THROWABLE_RULE,
                error.getMessage());
    }

    @Test
    void aTypeDeclaredTwiceWithinTheRuleIsRefused() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new RecoveryRule<>(
                Unavailable.class, Month.MARCH, List.of(IOException.class, UncheckedIOException.class,
                        IOException.class)));
        assertEquals(WHERE + " declares java.io.IOException more than once", error.getMessage());
    }

    @Test
    void aNullArgumentOrTypeIsRefused() {
        NullPointerException kind = assertThrows(NullPointerException.class,
                () -> new RecoveryRule<Month>(null, Month.MARCH, List.of()));
        assertTrue(kind.getMessage().startsWith("kind"), kind.getMessage());
        NullPointerException transition = assertThrows(NullPointerException.class,
                () -> new RecoveryRule<Month>(Unavailable.class, null, List.of()));
        assertTrue(transition.getMessage().startsWith("transition"), transition.getMessage());
        NullPointerException failsOn = assertThrows(NullPointerException.class,
                () -> new RecoveryRule<Month>(Unavailable.class, Month.MARCH, null));
        assertTrue(failsOn.getMessage().startsWith("failsOn"), failsOn.getMessage());
        List<Class<? extends Throwable>> withNull = Arrays.asList(IOException.class, null);
        assertThrows(NullPointerException.class, () -> new RecoveryRule<>(Unavailable.class, Month.MARCH, withNull));
    }
}
