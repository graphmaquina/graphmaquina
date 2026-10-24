package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.test.internal.Checks;
import java.util.List;
import lombok.NonNull;

/**
 * Chainable, typed assertion over the watch declared on one state, obtained from
 * {@link GraphAssert#watch(Enum)}. Both expectations read the declared {@link WatchSpec} and neither
 * ever evaluates a condition, so a watch whose conditions would fail if invoked can still be inspected.
 * A state that declares no watch leaves the view without a subject, and the first expectation on it
 * reports that as an assertion failure whose actual value is {@code null}.
 *
 * @param <E> entity type of the watching machine
 * @param <S> state enum of the watching machine
 * @param <T> transition identifier enum of the watching machine
 */
public final class WatchAssert<E, S extends Enum<S>, T extends Enum<T>> {

    private final String context;
    private final WatchSpec<E, S, T, ?, ?, ?> actual;

    WatchAssert(String context, WatchSpec<E, S, T, ?, ?, ?> actual) {
        this.context = context;
        this.actual = actual;
    }

    /** The machine the watch observes, compared as a whole key: entity class, state enum and identifiers. */
    public WatchAssert<E, S, T> hasPeer(@NonNull MachineKey<?, ?, ?> expected) {
        WatchSpec<E, S, T, ?, ?, ?> spec = Checks.subject(context, "peer", expected, actual);
        Checks.equal(context, "peer", expected, spec.peer());
        return this;
    }

    /** Compares condition names and executed identifiers in declaration order, keeping repeated rules. */
    public WatchAssert<E, S, T> hasRulesExactly(@NonNull List<WatchRuleExpectation<T>> expected) {
        List<WatchRuleExpectation<T>> wanted = List.copyOf(expected);
        WatchSpec<E, S, T, ?, ?, ?> spec = Checks.subject(context, "rules", wanted, actual);
        Checks.equal(context, "rules", wanted, rulesOf(spec));
        return this;
    }

    private static <E, S extends Enum<S>, T extends Enum<T>> List<WatchRuleExpectation<T>> rulesOf(
            WatchSpec<E, S, T, ?, ?, ?> spec) {
        return spec.rules().stream()
                .map(rule -> new WatchRuleExpectation<>(rule.condition().name(), rule.transition()))
                .toList();
    }
}
