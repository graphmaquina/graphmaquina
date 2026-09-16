package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.StatePolicy;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.graph.DirectedEdge;
import io.github.graphmaquina.graph.DirectedGraph;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.Getter;
import lombok.NonNull;

/** Immutable {@link GraphDefinition} assembled by the DSL from the edges that were actually declared. */
final class DslGraphDefinition<E, S extends Enum<S>, T extends Enum<T>> implements GraphDefinition<E, S, T> {

    @Getter
    private final MachineKey<E, S, T> key;
    @Getter
    private final S initial;
    @Getter
    private final Set<S> terminals;
    @Getter
    private final List<TransitionSpec<E, S, T>> transitions;
    @Getter
    private final DirectedGraph<S, T> graph;
    private final Map<S, Map<T, TransitionSpec<E, S, T>>> index;
    private final Map<S, List<TransitionSpec<E, S, T>>> outgoing;
    private final Map<S, StatePolicy<S, T>> policies;
    private final Map<S, WatchSpec<E, S, T, ?, ?, ?>> watches;
    @Getter
    private final boolean hasAsync;

    DslGraphDefinition(MachineKey<E, S, T> key, S[] states, S initial, Set<S> terminals,
                       List<TransitionSpec<E, S, T>> ordered, Map<S, StatePolicy<S, T>> policies,
                       Map<S, WatchSpec<E, S, T, ?, ?, ?>> watches) {
        this.key = key;
        this.initial = initial;
        EnumSet<S> terminalCopy = EnumSet.noneOf(key.states());
        terminalCopy.addAll(terminals);
        this.terminals = Collections.unmodifiableSet(terminalCopy);
        this.transitions = List.copyOf(ordered);
        Map<S, Map<T, TransitionSpec<E, S, T>>> byState = new EnumMap<>(key.states());
        Map<S, List<TransitionSpec<E, S, T>>> leaving = new EnumMap<>(key.states());
        List<DirectedEdge<S, T>> edges = new ArrayList<>();
        boolean async = false;
        for (S state : states) {
            leaving.put(state, new ArrayList<>());
        }
        for (TransitionSpec<E, S, T> spec : this.transitions) {
            byState.computeIfAbsent(spec.from(), ignored -> new HashMap<>()).put(spec.transition(), spec);
            leaving.get(spec.from()).add(spec);
            edges.add(new DirectedEdge<>(spec.from(), spec.transition(), spec.to()));
            async = async || spec.isAsync();
        }
        Map<S, Map<T, TransitionSpec<E, S, T>>> frozenIndex = new EnumMap<>(key.states());
        for (Map.Entry<S, Map<T, TransitionSpec<E, S, T>>> entry : byState.entrySet()) {
            frozenIndex.put(entry.getKey(), Collections.unmodifiableMap(entry.getValue()));
        }
        this.index = Collections.unmodifiableMap(frozenIndex);
        Map<S, List<TransitionSpec<E, S, T>>> frozen = new EnumMap<>(key.states());
        for (Map.Entry<S, List<TransitionSpec<E, S, T>>> entry : leaving.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        this.outgoing = Collections.unmodifiableMap(frozen);
        this.graph = DirectedGraph.of(Arrays.asList(states), edges);
        this.policies = Collections.unmodifiableMap(new EnumMap<>(policies));
        this.watches = Collections.unmodifiableMap(new LinkedHashMap<>(watches));
        this.hasAsync = async;
    }

    @Override
    public List<TransitionSpec<E, S, T>> outgoingFrom(@NonNull S state) {
        return required(outgoing, state);
    }

    @Override
    public Optional<TransitionSpec<E, S, T>> findTransition(@NonNull S from, @NonNull T transition) {
        requireState(from);
        if (!key.transitions().isInstance(transition)) {
            throw new IllegalArgumentException("transition must belong to " + key.transitions().getName() + ": " + transition);
        }
        return Optional.ofNullable(index.getOrDefault(from, Map.of()).get(transition));
    }

    @Override
    public StatePolicy<S, T> policy(@NonNull S state) {
        return required(policies, state);
    }

    @Override
    public Optional<WatchSpec<E, S, T, ?, ?, ?>> watch(@NonNull S state) {
        requireState(state);
        return Optional.ofNullable(watches.get(state));
    }

    private <V> V required(Map<S, V> map, S state) {
        requireState(state);
        V found = map.get(state);
        if (found == null) {
            throw new IllegalArgumentException("unknown state: " + state);
        }
        return found;
    }

    private void requireState(S state) {
        if (!key.states().isInstance(state)) {
            throw new IllegalArgumentException("state must belong to " + key.states().getName() + ": " + state);
        }
    }

    @Override
    public String toString() {
        return key + "[" + graph.nodes().size() + " states, " + transitions.size() + " transitions]";
    }
}
