package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.RecoveryRule;
import io.github.graphmaquina.api.TransitionSpec;
import java.util.Optional;

/**
 * Selects the recovery a failure the action returned takes on its edge: the recovery rule that declares the very
 * failure kind the failure carries, compared by identity, never through a supertype, and else the edge's onFailure,
 * which also takes a failure returned without a kind. Neither the reason nor the order of the rules plays a part, and
 * an edge declares each kind in one rule at most. The selector only picks an identifier: the transaction resolves it
 * from the failing edge's origin, and an empty one leaves the failure unhandled.
 */
final class RecoverySelector {

    private RecoverySelector() { }

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
}
