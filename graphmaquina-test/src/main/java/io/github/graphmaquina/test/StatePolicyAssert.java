package io.github.graphmaquina.test;

import io.github.graphmaquina.api.StatePolicy;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Chainable, typed assertion over the rejection policy of one state, obtained from
 * {@link GraphAssert#policy(Enum)}. A policy is not an edge and never becomes one: it only says what a
 * request with no edge from that state is told. {@link #hasDefaultReason(String)} reads the reason used
 * for any identifier without one of its own, and {@link #rejects(Enum, String)} the reason declared for a
 * single identifier.
 *
 * @param <S> state enum of the machine
 * @param <T> transition identifier enum of the machine
 */
public final class StatePolicyAssert<S extends Enum<S>, T extends Enum<T>> {

    private final String context;

    /** The machine's catalogue, so a foreign identifier is refused; null only when there is no definition. */
    private final Class<T> transitions;

    private final StatePolicy<S, T> actual;
    private final Function<T, Optional<S>> transitionTarget;

    StatePolicyAssert(String context, Class<T> transitions, StatePolicy<S, T> actual,
                      Function<T, Optional<S>> transitionTarget) {
        this.context = context;
        this.transitions = transitions;
        this.actual = actual;
        this.transitionTarget = transitionTarget;
    }

    /** The reason given for every identifier the policy does not name on its own. */
    public StatePolicyAssert<S, T> hasDefaultReason(String expected) {
        Objects.requireNonNull(expected, "expected");
        StatePolicy<S, T> policy = Checks.subject(context, "default reason", expected, actual);
        Checks.equal(context, "default reason", expected, policy.otherwise());
        return this;
    }

    /**
     * The reason this state gives {@code transition} alone. An identifier the policy does not name falls
     * back to the default, so this expectation is satisfied by the default's text too — which is exactly
     * what the engine would report for that request when no edge exists. An existing edge fails this
     * assertion regardless of its guards or the policy's text. An identifier that is not in the machine's catalogue
     * at all is a different matter: it would fall back to the default without ever having been declarable,
     * so it is refused as a programming error rather than quietly accepted.
     */
    public StatePolicyAssert<S, T> rejects(T transition, String expected) {
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(expected, "expected");
        if (transitions != null) {
            GraphAssert.requireDeclaredTransition(transitions, transition);
        }
        String where = context + " " + transition.name();
        StatePolicy<S, T> policy = Checks.subject(where, "reason", expected, actual);
        Checks.equal(where, "transition", Optional.empty(), transitionTarget.apply(transition));
        Checks.equal(where, "reason", expected, policy.reasonFor(transition));
        return this;
    }
}
