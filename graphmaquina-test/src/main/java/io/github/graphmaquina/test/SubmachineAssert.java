package io.github.graphmaquina.test;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.SubmachineSpec;
import io.github.graphmaquina.test.internal.Checks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Chainable, typed assertion over the sub-machine bound to one state, obtained from
 * {@link GraphAssert#submachine(Enum, MachineKey)}. The view is typed by the child machine the caller names, so the
 * compiler accepts only that machine's states as terminal states and only the parent's identifiers as exits. Every
 * expectation therefore checks first that the binding holds that child, compared as a whole key, and fails on the
 * property {@code child} when it holds another. Both expectations read the declared {@link SubmachineSpec} and
 * neither runs a lookup. A state that holds no sub-machine leaves the view without a subject, and the first
 * expectation on it reports that as an assertion failure whose actual value is {@code null}.
 *
 * @param <E> entity type of the parent machine
 * @param <S> state enum of the parent machine
 * @param <T> transition identifier enum of the parent machine
 * @param <P> entity type of the child machine
 * @param <Q> state enum of the child machine
 * @param <U> transition identifier enum of the child machine
 */
public final class SubmachineAssert<E, S extends Enum<S>, T extends Enum<T>, P, Q extends Enum<Q>,
        U extends Enum<U>> {

    private final String context;
    private final MachineKey<P, Q, U> child;
    private final Optional<SubmachineSpec<E, S, T, ?, ?, ?>> actual;

    SubmachineAssert(String context, MachineKey<P, Q, U> child, Optional<SubmachineSpec<E, S, T, ?, ?, ?>> actual) {
        this.context = context;
        this.child = child;
        this.actual = actual;
    }

    /**
     * Every exit in declaration order, each a terminal state of the child and the identifier it selects. The order
     * selects nothing, but the declaration keeps it, so another order fails.
     */
    @SafeVarargs
    public final SubmachineAssert<E, S, T, P, Q, U> hasExitsExactly(ExitExpectation<Q, T>... expected) {
        Objects.requireNonNull(expected, "expected");
        List<ExitExpectation<Q, T>> declared = new ArrayList<>();
        for (ExitExpectation<Q, T> exit : expected) {
            declared.add(exit);
        }
        List<ExitExpectation<Q, T>> wanted = List.copyOf(declared);
        SubmachineSpec<E, S, T, ?, ?, ?> spec = binding("exits", wanted);
        Checks.equal(context, "exits", wanted, exitsOf(spec));
        return this;
    }

    /** The identifier that {@code terminal} selects; a state of the child with no exit never satisfies it. */
    public SubmachineAssert<E, S, T, P, Q, U> hasExit(Q terminal, T transition) {
        Objects.requireNonNull(terminal, "terminal");
        Objects.requireNonNull(transition, "transition");
        String property = "exit " + terminal.name();
        Optional<T> wanted = Optional.of(transition);
        SubmachineSpec<E, S, T, ?, ?, ?> spec = binding(property, wanted);
        Checks.equal(context, property, wanted, Optional.ofNullable(spec.exits().get(terminal)));
        return this;
    }

    /** The binding, once the view has a subject and the binding holds the child the caller named. */
    private SubmachineSpec<E, S, T, ?, ?, ?> binding(String property, Object expected) {
        SubmachineSpec<E, S, T, ?, ?, ?> spec = Checks.present(context, property, expected, actual);
        Checks.equal(context, "child", child, spec.childKey());
        return spec;
    }

    private static <E, S extends Enum<S>, T extends Enum<T>, X extends Enum<X>> List<ExitExpectation<X, T>> exitsOf(
            SubmachineSpec<E, S, T, ?, X, ?> spec) {
        List<ExitExpectation<X, T>> exits = new ArrayList<>();
        for (Map.Entry<X, T> exit : spec.exits().entrySet()) {
            exits.add(new ExitExpectation<>(exit.getKey(), exit.getValue()));
        }
        return List.copyOf(exits);
    }
}
