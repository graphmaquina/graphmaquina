package io.github.graphmaquina.test;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.SubmachineSpec;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.test.internal.Checks;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Chainable, typed assertion over a {@link GraphDefinition}. Every expectation reads the definition's
 * declared structure, so a graph whose actions, guards, hooks or sub-machine lookups would fail if invoked
 * can be inspected without running any of them. {@link #transition(Enum, Enum)} narrows to the single edge
 * keyed by an origin and an identifier, {@link #policy(Enum)} to the rejection policy of a state,
 * {@link #watch(Enum)} to the watch declared on a state, {@link #submachine(Enum, MachineKey)} to the
 * sub-machine bound to a state, and {@link #topology()} to the reachability graph.
 *
 * <p>There is no expectation over destinations alone: an identifier is what selects an edge, and two
 * identifiers may well reach the same state from the same origin.
 *
 * @param <E> entity type of the machine
 * @param <S> state enum of the machine
 * @param <T> transition identifier enum of the machine
 */
public final class GraphAssert<E, S extends Enum<S>, T extends Enum<T>> {

    private final GraphDefinition<E, S, T> actual;

    private GraphAssert(GraphDefinition<E, S, T> actual) {
        this.actual = actual;
    }

    public static <E, S extends Enum<S>, T extends Enum<T>> GraphAssert<E, S, T> assertThat(
            GraphDefinition<E, S, T> actual) {
        return new GraphAssert<>(actual);
    }

    public GraphAssert<E, S, T> hasInitial(S expected) {
        Objects.requireNonNull(expected, "expected");
        String context = context();
        GraphDefinition<E, S, T> definition = Checks.subject(context, "initial", expected, actual);
        Checks.equal(context, "initial", expected, definition.initial());
        return this;
    }

    /** Terminals are unordered, so a repeated argument names the same terminal rather than a second one. */
    @SafeVarargs
    public final GraphAssert<E, S, T> hasTerminalsExactly(S... expected) {
        List<S> declared = new ArrayList<>();
        for (S terminal : expected) {
            declared.add(terminal);
        }
        Set<S> wanted = Set.copyOf(declared);
        String context = context();
        GraphDefinition<E, S, T> definition = Checks.subject(context, "terminals", wanted, actual);
        Checks.equal(context, "terminals", wanted, definition.terminals());
        return this;
    }

    /**
     * The identifiers declared from {@code from}, as an unordered set: a repeated argument names the same
     * edge rather than a second one. Two edges leaving {@code from} under different identifiers are two
     * entries here even when they reach the same destination.
     */
    @SafeVarargs
    public final GraphAssert<E, S, T> hasTransitionsExactly(S from, T... transitions) {
        Objects.requireNonNull(from, "from");
        List<T> declared = new ArrayList<>();
        for (T transition : transitions) {
            declared.add(transition);
        }
        Set<T> wanted = Set.copyOf(declared);
        String context = context() + " " + from.name();
        GraphDefinition<E, S, T> definition = Checks.subject(context, "transitions", wanted, actual);
        requireDeclaredState(definition, from, "from");
        Checks.equal(context, "transitions", wanted, outgoing(definition, from));
        return this;
    }

    /**
     * No edge is keyed by {@code (from, transition)}: the request would be rejected by the state's policy.
     * Both arguments must belong to the definition's own enums, because an identifier of a foreign enum
     * would simply miss the index and make this expectation pass without proving anything.
     */
    public GraphAssert<E, S, T> hasNoTransition(S from, T transition) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(transition, "transition");
        Optional<?> none = Optional.empty();
        String context = context() + " " + from.name() + " -" + transition.name() + "->";
        GraphDefinition<E, S, T> definition = Checks.subject(context, "transition", none, actual);
        requireDeclaredState(definition, from, "from");
        requireDeclaredTransition(definition.key().transitions(), transition);
        Checks.equal(context, "transition", none,
                definition.findTransition(from, transition).map(TransitionSpec::to));
        return this;
    }

    /** The graph is asynchronous when some edge declares an asynchronous action OR an asynchronous guard. */
    public GraphAssert<E, S, T> hasAsync() {
        String context = context();
        GraphDefinition<E, S, T> definition = Checks.subject(context, "async", Boolean.TRUE, actual);
        Checks.equal(context, "async", Boolean.TRUE, definition.hasAsync());
        return this;
    }

    /** Fails as soon as any edge declares an asynchronous action OR an asynchronous guard. */
    public GraphAssert<E, S, T> hasNoAsync() {
        String context = context();
        GraphDefinition<E, S, T> definition = Checks.subject(context, "async", Boolean.FALSE, actual);
        Checks.equal(context, "async", Boolean.FALSE, definition.hasAsync());
        return this;
    }

    public GraphAssert<E, S, T> hasNoWatch(S state) {
        Objects.requireNonNull(state, "state");
        Optional<?> none = Optional.empty();
        String context = context() + " " + state.name();
        GraphDefinition<E, S, T> definition = Checks.subject(context, "watch", none, actual);
        requireDeclaredState(definition, state, "state");
        Checks.equal(context, "watch", none, definition.watch(state));
        return this;
    }

    /**
     * No sub-machine is bound to {@code state}, which must belong to the definition's own state enum: a
     * state of a foreign enum would miss the binding and make this expectation pass without proving
     * anything. A failure reports the key of the child that is bound.
     */
    public GraphAssert<E, S, T> hasNoSubmachine(S state) {
        Objects.requireNonNull(state, "state");
        Optional<?> none = Optional.empty();
        String context = context() + " " + state.name();
        GraphDefinition<E, S, T> definition = Checks.present(context, "submachine", none, definition());
        requireDeclaredState(definition, state, "state");
        Checks.equal(context, "submachine", none, definition.submachine(state).map(SubmachineSpec::childKey));
        return this;
    }

    /**
     * Narrows to the edge keyed by {@code (from, transition)}. Both arguments must belong to the
     * definition's own enums: a state or an identifier of a foreign enum — reachable only through raw
     * types — would ask about a machine this definition does not describe. An undeclared key, like a
     * definition under test that is itself null, yields a view without a subject, and the first
     * expectation on it reports that as an assertion failure.
     */
    public TransitionAssert<E, S, T> transition(S from, T transition) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(transition, "transition");
        String context = context() + " " + from.name() + " -" + transition.name() + "->";
        if (actual == null) {
            return new TransitionAssert<>(context, null);
        }
        requireDeclaredState(actual, from, "from");
        requireDeclaredTransition(actual.key().transitions(), transition);
        return new TransitionAssert<>(context, actual.findTransition(from, transition).orElse(null));
    }

    /**
     * Narrows to the rejection policy of {@code state}, which every state has. A definition under test
     * that is null yields a view without a subject, and the first expectation on it reports that as an
     * assertion failure.
     */
    public StatePolicyAssert<S, T> policy(S state) {
        Objects.requireNonNull(state, "state");
        String context = context() + " policy " + state.name();
        if (actual == null) {
            return new StatePolicyAssert<>(context, null, null, null);
        }
        requireDeclaredState(actual, state, "state");
        return new StatePolicyAssert<>(context, actual.key().transitions(), actual.policy(state),
                transition -> actual.findTransition(state, transition).map(TransitionSpec::to));
    }

    /**
     * Narrows to the watch declared on {@code state}, which must belong to the definition's own state
     * enum. A state that declares no watch — like a definition under test that is itself null — yields a
     * view without a subject, and the first expectation on it reports that as an assertion failure.
     */
    public WatchAssert<E, S, T> watch(S state) {
        Objects.requireNonNull(state, "state");
        String context = context() + " watch " + state.name();
        if (actual == null) {
            return new WatchAssert<>(context, null);
        }
        requireDeclaredState(actual, state, "state");
        return new WatchAssert<>(context, actual.watch(state).orElse(null));
    }

    /**
     * Narrows to the sub-machine bound to {@code state}, which must belong to the definition's own state
     * enum. The view is typed by {@code child}, the key of the machine the binding is expected to hold, and
     * each expectation on it checks that key first. A state that holds no sub-machine — like a definition
     * under test that is itself null — yields a view without a subject, and the first expectation on it
     * reports that as an assertion failure. No lookup of the binding runs.
     */
    public <P, Q extends Enum<Q>, U extends Enum<U>> SubmachineAssert<E, S, T, P, Q, U> submachine(
            S state, MachineKey<P, Q, U> child) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(child, "child");
        String context = context() + " submachine " + state.name();
        Optional<GraphDefinition<E, S, T>> definition = definition();
        definition.ifPresent((GraphDefinition<E, S, T> declared) -> requireDeclaredState(declared, state, "state"));
        return new SubmachineAssert<>(context, child,
                definition.flatMap((GraphDefinition<E, S, T> declared) -> declared.submachine(state)));
    }

    /**
     * Narrows to the reachability graph: every state is a node, and every declared edge is an edge, with
     * parallel identifiers between the same pair collapsed into one. When the definition under test is
     * null so is the graph, and the first expectation on the returned view reports that as an assertion
     * failure.
     */
    public TopologyAssert<S> topology() {
        String context = context() + " topology";
        if (actual == null) {
            return new TopologyAssert<>(context, null);
        }
        return new TopologyAssert<>(context, actual.graph().topology());
    }

    /** The definition under test, wrapped once: the field is null only for a view built from a null subject. */
    private Optional<GraphDefinition<E, S, T>> definition() {
        return Optional.ofNullable(actual);
    }

    private String context() {
        if (actual == null) {
            return "graph";
        }
        MachineKey<E, S, T> key = actual.key();
        return key.entity().getName() + "/" + key.states().getName();
    }

    private static <E, S extends Enum<S>, T extends Enum<T>> Set<T> outgoing(
            GraphDefinition<E, S, T> definition, S from) {
        Set<T> declared = new LinkedHashSet<>();
        for (TransitionSpec<E, S, T> spec : definition.outgoingFrom(from)) {
            declared.add(spec.transition());
        }
        return declared;
    }

    private static <E, S extends Enum<S>, T extends Enum<T>> void requireDeclaredState(
            GraphDefinition<E, S, T> definition, S state, String role) {
        Class<S> states = definition.key().states();
        if (!states.isInstance(state)) {
            throw new IllegalArgumentException(
                    role + " " + state + " is not a state of " + states.getName());
        }
    }

    /**
     * Shared by {@link #hasNoTransition(Enum, Enum)}, {@link #transition(Enum, Enum)} and
     * {@link StatePolicyAssert#rejects(Enum, String)}, so that an identifier of a foreign enum — reachable
     * only through raw types — is refused there instead of quietly missing an index and reporting a green
     * absence.
     */
    static <T extends Enum<T>> void requireDeclaredTransition(Class<T> transitions, T transition) {
        if (!transitions.isInstance(transition)) {
            throw new IllegalArgumentException(
                    "transition " + transition + " is not a transition of " + transitions.getName());
        }
    }
}
