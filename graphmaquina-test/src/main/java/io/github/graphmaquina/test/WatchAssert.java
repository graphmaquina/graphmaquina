package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.test.internal.Checks;
import java.util.List;
import java.util.Objects;

/**
 * Chainable, typed assertion over the watch declared on one state, obtained from
 * {@link GraphAssert#watch(Enum)}. Both expectations read the declared {@link WatchSpec} and neither
 * ever evaluates a condition, so a watch whose conditions would fail if invoked can still be inspected.
 * A state that declares no watch leaves the view without a subject, and the first expectation on it
 * reports that as an assertion failure whose actual value is {@code null}.
 *
 * @param <E> entity type of the watching machine
 * @param <S> state enum of the watching machine
 */
public final class WatchAssert<E, S extends Enum<S>> {

    private final String context;
    private final WatchSpec<E, S, ?, ?> actual;

    WatchAssert(String context, WatchSpec<E, S, ?, ?> actual) {
        this.context = context;
        this.actual = actual;
    }

    /** The machine the watch observes, compared as a whole key: both the entity class and the state enum. */
    public WatchAssert<E, S> hasPeer(MachineKey<?, ?> expected) {
        Objects.requireNonNull(expected, "expected");
        WatchSpec<E, S, ?, ?> spec = Checks.subject(context, "peer", expected, actual);
        Checks.equal(context, "peer", expected, spec.peer());
        return this;
    }

    /** Compares condition names and targets in declaration order, keeping repeated rules. */
    public WatchAssert<E, S> hasRulesExactly(List<WatchRuleExpectation<S>> expected) {
        Objects.requireNonNull(expected, "expected");
        List<WatchRuleExpectation<S>> wanted = List.copyOf(expected);
        WatchSpec<E, S, ?, ?> spec = Checks.subject(context, "rules", wanted, actual);
        Checks.equal(context, "rules", wanted, rulesOf(spec));
        return this;
    }

    private static <E, S extends Enum<S>> List<WatchRuleExpectation<S>> rulesOf(WatchSpec<E, S, ?, ?> spec) {
        return spec.rules().stream()
                .map(rule -> new WatchRuleExpectation<>(rule.condition().name(), rule.target()))
                .toList();
    }
}
