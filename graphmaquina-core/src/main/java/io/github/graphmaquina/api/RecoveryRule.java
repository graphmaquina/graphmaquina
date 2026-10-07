package io.github.graphmaquina.api;

import io.github.graphmaquina.internal.FailureTypes;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.NonNull;

/**
 * One recovery rule declared on an edge: a failure kind, the identifier of the recovery edge the rule names, and the
 * throwable types the rule declares, possibly none. The constructor copies {@code failsOn} and refuses, with an
 * IllegalArgumentException, a kind that is not a valid {@link FailureKind}, a type that may not be declared, and a
 * type declared twice in the rule.
 *
 * <p>A failure the edge's action returns with {@code kind}, as {@link ActionOutcome#failed(Class, String)} builds it,
 * takes the recovery edge {@code transition}, resolved from the origin of the edge that failed, instead of the edge's
 * onFailure, and the outcome reports the kind in {@link Outcome.Failed#failureKind()}. Kinds are compared by identity,
 * never through a supertype. A throwable the action throws takes {@code transition} too, and the outcome reports
 * {@code kind}, when the most specific type that accepts it, among the types of every rule of the edge and of its
 * failsOn, is one of {@code failsOn}. The order of the declarations plays no part. The recovery edge runs without its
 * guards and without consulting its own rules or onFailure. For synchronous throws and exceptional stage
 * completions alike, the poison check precedes reading the cause's message.
 *
 * <p>A valid kind is a named final class or record that implements FailureKind and is not an interface, an enum, a
 * local or anonymous class, or a Throwable. The types that may not be declared are Throwable, Exception and
 * RuntimeException themselves; an Error, a CancellationException or a StateMachineException, with their subtypes;
 * exactly CompletionException or ExecutionException; and any class that is not a Throwable, which only raw types can
 * pass.
 *
 * @param <T> transition identifier enum
 * @param kind the failure kind of the rule
 * @param transition identifier of the recovery edge the rule names
 * @param failsOn throwable types the rule declares, in declaration order; may be empty
 */
public record RecoveryRule<T extends Enum<T>>(@NonNull Class<? extends FailureKind> kind, @NonNull T transition,
                                              @NonNull List<Class<? extends Throwable>> failsOn) {

    /**
     * Copies the declared types, then checks that the kind and every type declaration are valid and unique.
     *
     * @param kind failure kind of this rule
     * @param transition identifier of the recovery edge this rule names
     * @param failsOn throwable types declared by this rule, in declaration order; may be empty
     * @throws NullPointerException if an argument or a declared type is null
     * @throws IllegalArgumentException if the kind is invalid, a type is forbidden, or a type is repeated
     */
    public RecoveryRule {
        failsOn = List.copyOf(failsOn);
        FailureTypes.requireValidKind(kind);
        String where = "recover(" + kind.getName() + ", " + transition + ")";
        Set<Class<? extends Throwable>> declared = new HashSet<>();
        for (Class<? extends Throwable> type : failsOn) {
            if (FailureTypes.isForbiddenThrowable(type)) {
                throw new IllegalArgumentException(where + " does not allow " + type.getName() + "; "
                        + FailureTypes.FORBIDDEN_THROWABLE_RULE);
            }
            if (!declared.add(type)) {
                throw new IllegalArgumentException(where + " declares " + type.getName() + " more than once");
            }
        }
    }
}
