package io.github.graphmaquina.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.error.StateMachineException;
import io.github.graphmaquina.error.UnknownFacadeException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

/**
 * The rules {@link FailureTypes} holds: which classes are failure kinds and how one is refused, which throwable types
 * are forbidden as declared failures, and which throwables are excluded from matching whatever their subtype.
 */
class FailureTypesTest {

    /** A failure kind declared as a member record. */
    record MemberKind() implements FailureKind { }

    /** A failure kind declared as a final member class; nothing ever instantiates it. */
    static final class FinalKind implements FailureKind {
        private FinalKind() { }
    }

    /** An interface names a family of kinds, not a kind. */
    interface KindFamily extends FailureKind { }

    /** Its constants are values, while a kind is identified by its class. */
    enum EnumKind implements FailureKind { ONLY }

    /** Abstract, so never final. */
    abstract static class AbstractKind implements FailureKind { }

    /** Concrete, but open to subclasses. */
    static class OpenKind implements FailureKind { }

    /** A throwable that also claims to be a kind. */
    static final class ThrowingKind extends RuntimeException implements FailureKind { }

    /** A named final class that does not implement FailureKind. */
    static final class NotAKind { }

    /** A cancellation all the same: forbidden and excluded like its parent. */
    static final class Cancelled extends CancellationException { }

    /** Not the wrapper itself, so never unwrapped: an edge may declare it. */
    static final class Completed extends CompletionException { }

    /** Not the wrapper itself, so never unwrapped: an edge may declare it. */
    static final class Executed extends ExecutionException { }

    @Test
    void namedFinalClassesThatImplementFailureKindAreKinds() {
        List<Class<?>> valid = List.of(TopLevelKind.class, MemberKind.class, FinalKind.class);
        for (Class<?> type : valid) {
            assertTrue(FailureTypes.isValidKind(type), type.getName());
        }
    }

    @Test
    void interfacesEnumsAbstractOrOpenClassesThrowablesAndOtherClassesAreNotKinds() {
        List<Class<?>> invalid = List.of(FailureKind.class, KindFamily.class, EnumKind.class, AbstractKind.class,
                OpenKind.class, ThrowingKind.class, NotAKind.class, String.class);
        for (Class<?> type : invalid) {
            assertFalse(FailureTypes.isValidKind(type), type.getName());
        }
    }

    /** None of them is a named class, however final: a local record, a local class, an anonymous class, a lambda. */
    @Test
    void localAnonymousAndLambdaClassesAreNotKinds() {
        record LocalRecord() implements FailureKind { }
        final class LocalClass implements FailureKind { }
        FailureKind anonymous = new FailureKind() { };
        Runnable lambda = (Runnable & FailureKind) () -> { };
        List<Class<?>> invalid = List.of(LocalRecord.class, LocalClass.class, anonymous.getClass(), lambda.getClass());
        for (Class<?> type : invalid) {
            assertFalse(FailureTypes.isValidKind(type), type.getName());
        }
    }

    @Test
    void requireValidKindReturnsAKindAndRefusesNullAndAnythingElse() {
        assertSame(MemberKind.class, FailureTypes.requireValidKind(MemberKind.class));
        NullPointerException missing = assertThrows(NullPointerException.class,
                () -> FailureTypes.requireValidKind(null));
        assertEquals("kind", missing.getMessage());
        IllegalArgumentException open = assertThrows(IllegalArgumentException.class,
                () -> FailureTypes.requireValidKind(OpenKind.class));
        assertEquals("io.github.graphmaquina.internal.FailureTypesTest$OpenKind is not a failure kind; a failure kind"
                + " is a named final class or record that implements FailureKind and is not an interface, an enum, a"
                + " local or anonymous class, or a Throwable", open.getMessage());
        assertEquals(" is not a failure kind; a failure kind is a named final class or record that implements"
                + " FailureKind and is not an interface, an enum, a local or anonymous class, or a Throwable",
                FailureTypes.INVALID_KIND_SUFFIX);
        assertEquals(open.getMessage(), FailureTypes.invalidKindMessage(OpenKind.class));
    }

    /** Only a raw Class gets a class that does not implement FailureKind this far: it is refused all the same. */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void requireValidKindRefusesARawClassThatIsNotAKind() {
        Class raw = String.class;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> FailureTypes.requireValidKind(raw));
        assertEquals("java.lang.String is not a failure kind; a failure kind is a named final class or record that"
                + " implements FailureKind and is not an interface, an enum, a local or anonymous class, or a"
                + " Throwable", error.getMessage());
    }

    @Test
    void theRootsErrorsLibraryFailuresCancellationsTheExactWrappersAndNonThrowablesAreForbidden() {
        List<Class<?>> forbidden = List.of(Throwable.class, Exception.class, RuntimeException.class, Error.class,
                OutOfMemoryError.class, StateMachineException.class, UnknownFacadeException.class,
                CancellationException.class, Cancelled.class, CompletionException.class, ExecutionException.class,
                String.class, MemberKind.class);
        for (Class<?> type : forbidden) {
            assertTrue(FailureTypes.isForbiddenThrowable(type), type.getName());
        }
    }

    /** IllegalStateException stays allowed although CancellationException extends it. */
    @Test
    void foreignThrowablesAndSubclassesOfTheWrappersAreAllowed() {
        List<Class<?>> allowed = List.of(IOException.class, UncheckedIOException.class, IllegalStateException.class,
                IllegalArgumentException.class, Completed.class, Executed.class);
        for (Class<?> type : allowed) {
            assertFalse(FailureTypes.isForbiddenThrowable(type), type.getName());
        }
    }

    @Test
    void errorsCancellationsAndLibraryFailuresAreExcludedWithTheirSubtypes() {
        List<Throwable> excluded = List.of(new Error("fatal"), new StackOverflowError(), new CancellationException(),
                new Cancelled(), new StateMachineException("library"),
                new UnknownFacadeException(Object.class, List.of()));
        for (Throwable cause : excluded) {
            assertTrue(FailureTypes.isExcluded(cause), cause.getClass().getName());
        }
        List<Throwable> matchable = List.of(new RuntimeException("plain"), new IllegalStateException("state"),
                new IOException("io"), new Completed());
        for (Throwable cause : matchable) {
            assertFalse(FailureTypes.isExcluded(cause), cause.getClass().getName());
        }
        assertFalse(FailureTypes.isExcluded(null));
    }

}
