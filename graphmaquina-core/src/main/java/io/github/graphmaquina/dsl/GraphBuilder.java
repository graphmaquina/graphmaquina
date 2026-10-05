package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Function;

/**
 * Top-level stage of the DSL: declares the initial state, terminals, policies, edges and watches, then builds.
 * Single use: once {@code build()} has run, nothing reads a declaration any more, so this stage and the stages it
 * opened refuse every further declaration with {@link IllegalStateException}.
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

    GraphDefinition<E, S, T> build();
}
