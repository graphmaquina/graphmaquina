package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.RecoveryRule;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.internal.FailureTypes;
import java.util.Optional;

/**
 * Selects the recovery a failed action takes on its edge. A failure the action returned takes the recovery rule that
 * declares the very failure kind it carries, compared by identity, never through a supertype, and else the edge's
 * onFailure, which also takes a failure returned without a kind. A throwable is admitted when a type the edge declares
 * accepts it, among the types of its rules and of its own failsOn, unless it is an Error, a CancellationException or a
 * StateMachineException, which are never admitted whatever the edge declares. The most specific accepting type then
 * wins, whatever the order of the declarations, for the types the DSL and RecoveryRule admit: a rule's type routes to
 * that rule's recovery, with its kind, and a failsOn type to onFailure, without one. For the types the DSL and
 * RecoveryRule admit, that winner is unique: the accepting types all lie on the superclass chain of the throwable's
 * class, and the edge's constructor refuses a type declared in two rules, or in a rule and in failsOn; failsOn may
 * repeat its own types, which all take onFailure. A hand-built TransitionSpec does not harden its failsOn, so it may
 * hold an interface type, and then the order of failsOn's entries can change the route. The scan is deterministic:
 * failsOn first, then the rules in order, and an accepting type replaces the current winner only when it is the same
 * type or a subtype of it, so an accepting entry keeps the win over any later accepting type that is not its subtype.
 * Neither a reason nor a message plays a part. The selector only picks an identifier: the transaction keeps a failure
 * of the call itself out of it, checks the poison between admitting a throwable and choosing its route, and resolves
 * the identifier from the failing edge's origin; an empty one leaves the failure unhandled.
 */
final class RecoverySelector {

    private RecoverySelector() { }

    /**
     * Where an admitted throwable goes: the recovery identifier, empty when the edge has none for it, and the failure
     * kind the outcome reports.
     */
    record Route<T extends Enum<T>>(Optional<T> recovery, Optional<Class<? extends FailureKind>> failureKind) { }

    /**
     * The recovery identifier a failure returned with {@code failureKind} takes on {@code edge}: the transition of
     * the rule that declares that kind, or else the edge's onFailure, empty when it declares none.
     */
    static <T extends Enum<T>> Optional<T> returned(TransitionSpec<?, ?, T> edge,
                                                    Optional<Class<? extends FailureKind>> failureKind) {
        if (failureKind.isPresent()) {
            for (RecoveryRule<T> rule : edge.recoveryRules()) {
                if (rule.kind() == failureKind.get()) {
                    return Optional.of(rule.transition());
                }
            }
        }
        return edge.onFailure();
    }

    /**
     * Whether {@code cause} is a domain failure of {@code edge}: it is not excluded, and a type that one of the edge's
     * rules or its own failsOn declares accepts it.
     */
    static boolean admits(TransitionSpec<?, ?, ?> edge, Throwable cause) {
        if (FailureTypes.isExcluded(cause)) {
            return false;
        }
        for (Class<? extends Throwable> type : edge.failsOn()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        for (RecoveryRule<?> rule : edge.recoveryRules()) {
            for (Class<? extends Throwable> type : rule.failsOn()) {
                if (type.isInstance(cause)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The route of a throwable that {@link #admits} admitted: that of the most specific type {@code edge} declares
     * that accepts {@code cause}. A type of a rule routes to the rule's recovery with the rule's kind, and a type of
     * the edge's own failsOn to onFailure without a kind.
     */
    static <T extends Enum<T>> Route<T> thrown(TransitionSpec<?, ?, T> edge, Throwable cause) {
        Class<? extends Throwable> winner = null;
        for (Class<? extends Throwable> type : edge.failsOn()) {
            if (type.isInstance(cause) && (winner == null || winner.isAssignableFrom(type))) {
                winner = type;
            }
        }
        RecoveryRule<T> owner = null;
        for (RecoveryRule<T> rule : edge.recoveryRules()) {
            for (Class<? extends Throwable> type : rule.failsOn()) {
                if (type.isInstance(cause) && (winner == null || winner.isAssignableFrom(type))) {
                    winner = type;
                    owner = rule;
                }
            }
        }
        if (owner == null) {
            return new Route<>(edge.onFailure(), Optional.empty());
        }
        return new Route<>(Optional.of(owner.transition()), Optional.of(owner.kind()));
    }
}
