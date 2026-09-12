package io.github.graphmaquina.test;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.test.internal.Checks;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Chainable, typed assertion over a {@link GraphDefinition}. Every expectation reads the definition's
 * declared structure, so a graph whose actions, guards or hooks would fail if invoked can be inspected
 * without running any of them. {@link #edge(Enum, Enum)} narrows to a single cell of the matrix.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 */
public final class GraphAssert<E, S extends Enum<S>> {

    private final GraphDefinition<E, S> actual;

    private GraphAssert(GraphDefinition<E, S> actual) {
        this.actual = actual;
    }

    public static <E, S extends Enum<S>> GraphAssert<E, S> assertThat(GraphDefinition<E, S> actual) {
        return new GraphAssert<>(actual);
    }

    public GraphAssert<E, S> hasInitial(S expected) {
        Objects.requireNonNull(expected, "expected");
        String context = context();
        GraphDefinition<E, S> definition = Checks.subject(context, "initial", expected, actual);
        Checks.equal(context, "initial", expected, definition.initial());
        return this;
    }

    /** Terminals are unordered, so a repeated argument names the same terminal rather than a second one. */
    @SafeVarargs
    public final GraphAssert<E, S> hasTerminalsExactly(S... expected) {
        List<S> declared = new ArrayList<>();
        for (S terminal : expected) {
            declared.add(terminal);
        }
        Set<S> wanted = Set.copyOf(declared);
        String context = context();
        GraphDefinition<E, S> definition = Checks.subject(context, "terminals", wanted, actual);
        Checks.equal(context, "terminals", wanted, definition.terminals());
        return this;
    }

    /** The ACTION and MOVE destinations declared from {@code from}, as an unordered set. */
    @SafeVarargs
    public final GraphAssert<E, S> hasAllowedTargetsExactly(S from, S... targets) {
        Objects.requireNonNull(from, "from");
        List<S> declared = new ArrayList<>();
        for (S target : targets) {
            declared.add(target);
        }
        Set<S> wanted = Set.copyOf(declared);
        String context = context() + " " + from.name();
        GraphDefinition<E, S> definition = Checks.subject(context, "allowed targets", wanted, actual);
        requireDeclaredState(definition, from, "from");
        Checks.equal(context, "allowed targets", wanted, definition.allowedFrom(from));
        return this;
    }

    /** The graph is asynchronous when some cell declares an asynchronous action OR an asynchronous guard. */
    public GraphAssert<E, S> hasAsync() {
        String context = context();
        GraphDefinition<E, S> definition = Checks.subject(context, "async", Boolean.TRUE, actual);
        Checks.equal(context, "async", Boolean.TRUE, definition.hasAsync());
        return this;
    }

    /** Fails as soon as any cell declares an asynchronous action OR an asynchronous guard. */
    public GraphAssert<E, S> hasNoAsync() {
        String context = context();
        GraphDefinition<E, S> definition = Checks.subject(context, "async", Boolean.FALSE, actual);
        Checks.equal(context, "async", Boolean.FALSE, definition.hasAsync());
        return this;
    }

    public GraphAssert<E, S> hasNoWatch(S state) {
        Objects.requireNonNull(state, "state");
        Optional<?> none = Optional.empty();
        String context = context() + " " + state.name();
        GraphDefinition<E, S> definition = Checks.subject(context, "watch", none, actual);
        requireDeclaredState(definition, state, "state");
        Checks.equal(context, "watch", none, definition.watch(state));
        return this;
    }

    /**
     * Narrows to the cell {@code from -> to}. Both states must belong to the definition's own state enum:
     * the matrix is indexed by ordinal, so a state of a foreign enum — reachable only through raw types —
     * would silently read a different cell. When the definition under test is itself null the cell is null
     * too, and the first expectation on the returned view reports that as an assertion failure.
     */
    public EdgeAssert<E, S> edge(S from, S to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        String context = context() + " " + from.name() + " -> " + to.name();
        if (actual == null) {
            return new EdgeAssert<>(context, null);
        }
        requireDeclaredState(actual, from, "from");
        requireDeclaredState(actual, to, "to");
        return new EdgeAssert<>(context, actual.edge(from, to));
    }

    private String context() {
        if (actual == null) {
            return "graph";
        }
        MachineKey<E, S> key = actual.key();
        return key.entity().getName() + "/" + key.states().getName();
    }

    private static <E, S extends Enum<S>> void requireDeclaredState(
            GraphDefinition<E, S> definition, S state, String role) {
        Class<S> states = definition.key().states();
        if (!states.isInstance(state)) {
            throw new IllegalArgumentException(
                    role + " " + state + " is not a state of " + states.getName());
        }
    }
}
