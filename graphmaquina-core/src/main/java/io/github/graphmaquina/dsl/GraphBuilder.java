package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Function;

/**
 * Top-level stage of the DSL: declares the initial state, terminals, policies, edges, watches and sub-machines, then
 * builds.
 * Single use: once {@code build()} has run, even one that threw
 * {@link io.github.graphmaquina.error.InvalidGraphException}, nothing reads a declaration any more, so this stage
 * and the stages it opened refuse every further declaration, and a second {@code build()}, with
 * {@link IllegalStateException}.
 */
public interface GraphBuilder<E, S extends Enum<S>, T extends Enum<T>> {

    GraphBuilder<E, S, T> initial(S state);

    /** One per call: a generic varargs would trigger an unchecked warning at the call site. */
    GraphBuilder<E, S, T> terminal(S state);

    GraphBuilder<E, S, T> allowsCycle(S state);

    /** The reason a request with no edge from {@code state} is rejected, unless a more specific one was declared. */
    GraphBuilder<E, S, T> statePolicy(S state, String otherwise);

    /** The reason this very identifier is rejected from {@code state}. Only valid where no edge is declared. */
    GraphBuilder<E, S, T> reject(S state, T transition, String reason);

    /**
     * Opens one edge keyed by {@code (from, transition)}; {@code endTransition()} closes it. An edge left open is
     * not dropped: {@code build()} reports it as SG507.
     */
    TransitionBuilder<E, S, T> transition(T transition, S from, S to);

    <P, Q extends Enum<Q>, U extends Enum<U>> WatchBuilder<E, S, T, P, Q, U> watch(
            S state, MachineKey<P, Q, U> peer,
            Function<? super E, ? extends Collection<? extends P>> peersOf,
            Function<? super P, Optional<E>> ownerOf);

    /**
     * Binds the sub-machine {@code child} to {@code state}; {@code exit(...)} on the returned stage maps each terminal
     * state of the child to an identifier of this graph. {@code childOf} reaches the child entity from a parent
     * entity, and {@code parentOf} the parent entity from a child entity; this stage only stores them. An edge that
     * enters {@code state} while {@code childOf} answers empty aborts its step before the action runs.
     * {@code build()} reports SG801 when a terminal state of the child has no exit or more than one, or an exit names
     * a state that is not terminal in the child; SG802 when an exit names an identifier with no edge from
     * {@code state}; SG803 when {@code state} is terminal or holds a second sub-machine; SG804 when an edge leaving
     * {@code state} names an exit's identifier in its {@code onFailure} or in a recovery rule, or the watch of
     * {@code state} executes one; and SG805 when the child, or a sub-machine reached through the children's own
     * sub-machines, is a definition of this graph's key.
     */
    <P, Q extends Enum<Q>, U extends Enum<U>> SubmachineBuilder<E, S, T, P, Q, U> submachine(
            S state, GraphDefinition<P, Q, U> child,
            Function<? super E, Optional<P>> childOf, Function<? super P, Optional<E>> parentOf);

    /**
     * The child governs another state of the same entity: childOf and parentOf are the identity, which returns the
     * entity it is given. Otherwise as {@link #submachine(Enum, GraphDefinition, Function, Function)}.
     */
    <Q extends Enum<Q>, U extends Enum<U>> SubmachineBuilder<E, S, T, E, Q, U> submachine(
            S state, GraphDefinition<E, Q, U> child);

    GraphDefinition<E, S, T> build();
}
