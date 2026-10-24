package io.github.graphmaquina.internal;

import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.error.StateMachineException;
import java.lang.reflect.Modifier;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import lombok.NonNull;

/**
 * Rules on failure kinds and on throwable types: which classes are valid failure kinds, which throwable types are
 * forbidden as declared expected failures, and which throwables are never domain failures. Not part of the API.
 */
public final class FailureTypes {

    /** The shared suffix of the message that refuses a type as a failure kind. */
    public static final String INVALID_KIND_SUFFIX = " is not a failure kind; a failure kind is a named final class"
            + " or record that implements FailureKind and is not an interface, an enum, a local or anonymous class,"
            + " or a Throwable";

    /** Which throwable types may be declared as expected failures, and why the others may not. */
    public static final String FORBIDDEN_THROWABLE_RULE = "only throwable types foreign to the library, and never"
            + " Throwable, Exception, RuntimeException, an Error or a CancellationException, which are never domain"
            + " failures, nor CompletionException or ExecutionException, which are unwrapped before matching:"
            + " declare the type of their cause";

    private FailureTypes() { }

    /**
     * Whether {@code type} is a valid failure kind: a named class, top-level or member, that is final and implements
     * {@link FailureKind}, and that is not an interface, an enum, a local, anonymous or hidden class, or a Throwable.
     * A final class is never abstract, so a valid kind is concrete; the class of a lambda that also implements the
     * marker is hidden, so it is not named.
     *
     * @param type the class to check
     * @return whether {@code type} is a valid failure kind
     */
    public static boolean isValidKind(Class<?> type) {
        // isFinal alone already refuses an interface, and an anonymous class as javac compiles it: the explicit
        // checks keep the predicate equal to the rule as written.
        return FailureKind.class.isAssignableFrom(type) && !type.isInterface() && !type.isEnum()
                && !type.isLocalClass() && !type.isAnonymousClass() && !type.isHidden()
                && Modifier.isFinal(type.getModifiers()) && !Throwable.class.isAssignableFrom(type);
    }

    /**
     * The message that refuses {@code type} as a failure kind, naming it by its binary name.
     *
     * @param type the class refused
     * @return the message
     */
    public static String invalidKindMessage(Class<?> type) {
        return type.getName() + INVALID_KIND_SUFFIX;
    }

    /**
     * Returns {@code kind} when it is a valid failure kind, as {@link #isValidKind(Class)} defines it.
     *
     * @param kind the class to check
     * @return {@code kind}
     * @throws NullPointerException if {@code kind} is null
     * @throws IllegalArgumentException with {@link #invalidKindMessage(Class)} if {@code kind} is not a valid failure
     *     kind, which includes a raw {@code Class} of a class that does not implement {@link FailureKind}
     */
    public static Class<? extends FailureKind> requireValidKind(@NonNull Class<? extends FailureKind> kind) {
        if (!isValidKind(kind)) {
            throw new IllegalArgumentException(invalidKindMessage(kind));
        }
        return kind;
    }

    /**
     * Whether {@code type} is forbidden as a declared expected failure: Throwable, Exception and RuntimeException
     * themselves; an Error, a StateMachineException or a CancellationException, with their subtypes; exactly
     * CompletionException or ExecutionException, whose subclasses stay allowed; and any class that is not a
     * Throwable, which only a raw {@code Class} can carry. The throwable types in this list are the ones diagnostic
     * SG302 refuses.
     *
     * @param type the class to check
     * @return whether {@code type} is forbidden
     */
    public static boolean isForbiddenThrowable(Class<?> type) {
        return !Throwable.class.isAssignableFrom(type)
                || type == Throwable.class || type == Exception.class || type == RuntimeException.class
                || Error.class.isAssignableFrom(type) || StateMachineException.class.isAssignableFrom(type)
                || CancellationException.class.isAssignableFrom(type)
                || type == CompletionException.class || type == ExecutionException.class;
    }

    /**
     * Whether {@code cause} is never a domain failure, whatever an edge declares: an Error, a CancellationException or
     * a StateMachineException, with their subtypes.
     *
     * @param cause the throwable to check
     * @return whether {@code cause} is excluded
     */
    public static boolean isExcluded(Throwable cause) {
        return cause instanceof Error || cause instanceof CancellationException
                || cause instanceof StateMachineException;
    }
}
