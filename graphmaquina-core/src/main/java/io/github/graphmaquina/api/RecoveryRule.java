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
 * never through a supertype. A throwable the action throws, unless the action runs as a recovery action, takes
 * {@code transition} too, and the outcome reports {@code kind}, when the most specific type that accepts it, among the
 * types of every rule of the edge and of its failsOn, is one of {@code failsOn}; an Error, a CancellationException and
 * a StateMachineException never match, so no declared type accepts them, and for a failure of the call itself the
 * engine throws it as itself or, when the transaction is poisoned, the failure that poisoned the transaction. Once a
 * failed cascade poisoned the transaction, for an accepted throwable of an action that does not run as a recovery
 * action the engine throws the failure that poisoned the transaction, and no route is taken. For the types the DSL and
 * this record admit, the order of the declarations plays no part; a hand-built TransitionSpec may hold an interface
 * type in its failsOn, and then the order of failsOn's entries can change the route. The recovery edge runs without its
 * guards and without consulting its own rules or onFailure. For a throwable a declared type accepts, from an action
 * that does not run as a recovery action, the engine checks the poison before it reads the throwable's message, for
 * synchronous throws and exceptional stage completions alike; the JDK may already have read that message, for example
 * when {@code supplyAsync} wrapped the throwable in a CompletionException.
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
