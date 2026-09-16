package io.github.graphmaquina.test;

import io.github.graphmaquina.api.StatePolicy;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Objects;

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
    private final StatePolicy<S, T> actual;

    StatePolicyAssert(String context, StatePolicy<S, T> actual) {
        this.context = context;
        this.actual = actual;
    }

    /** The reason given for every identifier the policy does not name on its own. */
    public StatePolicyAssert<S, T> hasDefaultReason(String expected) {
        Objects.requireNonNull(expected, "expected");
        StatePolicy<S, T> policy = Checks.subject(context, "default reason", expected, actual);
        Checks.equal(context, "default reason", expected, policy.otherwise());
        return this;
    }

    /**
     * The reason declared for {@code transition} alone. An identifier with no reason of its own falls back
     * to the default, so this expectation is satisfied by the default's text too — which is exactly what
     * the engine would report for that request.
     */
    public StatePolicyAssert<S, T> rejects(T transition, String expected) {
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(expected, "expected");
        String where = context + " " + transition.name();
        StatePolicy<S, T> policy = Checks.subject(where, "reason", expected, actual);
        Checks.equal(where, "reason", expected, policy.reasonFor(transition));
        return this;
    }
}
