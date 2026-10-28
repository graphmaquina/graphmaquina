package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.StatePolicy;
import io.github.graphmaquina.api.SubmachineSpec;
import io.github.graphmaquina.api.TransitionKey;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.graph.DirectedEdge;
import io.github.graphmaquina.graph.DirectedGraph;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
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
    private final Map<TransitionKey<S, T>, TransitionSpec<E, S, T>> index;
    private final Map<S, List<TransitionSpec<E, S, T>>> outgoing;
    private final Map<S, List<TransitionSpec<E, S, T>>> automatic;
    private final Map<S, StatePolicy<S, T>> policies;
    private final Map<S, WatchSpec<E, S, T, ?, ?, ?>> watches;
    private final Map<S, SubmachineSpec<E, S, T, ?, ?, ?>> submachines;
    @Getter
    private final boolean hasAsync;

    DslGraphDefinition(MachineKey<E, S, T> key, S[] states, S initial, Set<S> terminals,
                       List<TransitionSpec<E, S, T>> ordered, Map<S, StatePolicy<S, T>> policies,
                       Map<S, WatchSpec<E, S, T, ?, ?, ?>> watches,
                       Map<S, SubmachineSpec<E, S, T, ?, ?, ?>> submachines, Map<S, List<T>> automaticOrders) {
        this.key = key;
        this.initial = initial;
        EnumSet<S> terminalCopy = EnumSet.noneOf(key.states());
        terminalCopy.addAll(terminals);
        this.terminals = Collections.unmodifiableSet(terminalCopy);
        this.transitions = List.copyOf(ordered);
        Map<TransitionKey<S, T>, TransitionSpec<E, S, T>> byKey = new LinkedHashMap<>();
        Map<S, List<TransitionSpec<E, S, T>>> leaving = new EnumMap<>(key.states());
        List<DirectedEdge<S, T>> edges = new ArrayList<>();
        boolean async = false;
        for (S state : states) {
            leaving.put(state, new ArrayList<>());
        }
        for (TransitionSpec<E, S, T> spec : this.transitions) {
            byKey.put(spec.key(), spec);
            leaving.get(spec.from()).add(spec);
            edges.add(new DirectedEdge<>(spec.from(), spec.transition(), spec.to()));
            async = async || spec.isAsync();
        }
        this.index = Collections.unmodifiableMap(byKey);
        this.outgoing = frozen(key, leaving);
        this.automatic = automaticEdges(key, this.outgoing, automaticOrders);
        this.graph = DirectedGraph.of(Arrays.asList(states), edges);
        this.policies = Collections.unmodifiableMap(new EnumMap<>(policies));
        this.watches = Collections.unmodifiableMap(new LinkedHashMap<>(watches));
        Map<S, SubmachineSpec<E, S, T, ?, ?, ?>> bound = new EnumMap<>(key.states());
        bound.putAll(submachines);
        this.submachines = Collections.unmodifiableMap(bound);
        this.hasAsync = async;
    }

    /** The same lists, each made immutable, under the same states. */
    private static <E, S extends Enum<S>, T extends Enum<T>> Map<S, List<TransitionSpec<E, S, T>>> frozen(
            MachineKey<E, S, T> key, Map<S, List<TransitionSpec<E, S, T>>> leaving) {
        Map<S, List<TransitionSpec<E, S, T>>> frozen = new EnumMap<>(key.states());
        for (Map.Entry<S, List<TransitionSpec<E, S, T>>> entry : leaving.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(frozen);
    }

    @Override
    public List<TransitionSpec<E, S, T>> outgoingFrom(@NonNull S state) {
        return required(outgoing, state);
    }

    @Override
    public List<TransitionSpec<E, S, T>> automaticFrom(@NonNull S state) {
        return required(automatic, state);
    }

    /**
     * The automatic edges of each state: those {@code orders} names for the state, in that order, then every other
     * automatic edge of the state in the order of {@code outgoing}. A name that is not an automatic edge from the state
     * is left out, and a name repeated counts once, so the lists hold exactly the automatic edges.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> Map<S, List<TransitionSpec<E, S, T>>> automaticEdges(
            MachineKey<E, S, T> key, Map<S, List<TransitionSpec<E, S, T>>> outgoing, Map<S, List<T>> orders) {
        Map<S, List<TransitionSpec<E, S, T>>> result = new EnumMap<>(key.states());
        for (Map.Entry<S, List<TransitionSpec<E, S, T>>> entry : outgoing.entrySet()) {
            List<TransitionSpec<E, S, T>> marked = new ArrayList<>();
            for (TransitionSpec<E, S, T> spec : entry.getValue()) {
                if (spec.automatic()) {
                    marked.add(spec);
                }
            }
            List<TransitionSpec<E, S, T>> sorted = new ArrayList<>();
            for (T named : orders.getOrDefault(entry.getKey(), List.of())) {
                for (TransitionSpec<E, S, T> spec : marked) {
                    if (spec.transition() == named && !sorted.contains(spec)) {
                        sorted.add(spec);
                    }
                }
            }
            for (TransitionSpec<E, S, T> spec : marked) {
                if (!sorted.contains(spec)) {
                    sorted.add(spec);
                }
            }
            result.put(entry.getKey(), List.copyOf(sorted));
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public Optional<TransitionSpec<E, S, T>> findTransition(@NonNull S from, @NonNull T transition) {
        requireState(from);
        if (!key.transitions().isInstance(transition)) {
            throw new IllegalArgumentException("transition must belong to " + key.transitions().getName() + ": "
                    + transition.name());
        }
        return Optional.ofNullable(index.get(new TransitionKey<>(from, transition)));
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

    @Override
    public Optional<SubmachineSpec<E, S, T, ?, ?, ?>> submachine(@NonNull S state) {
        requireState(state);
        return Optional.ofNullable(submachines.get(state));
    }

    private <V> V required(Map<S, V> map, S state) {
        requireState(state);
        V found = map.get(state);
        if (found == null) {
            throw new IllegalArgumentException("unknown state: " + state.name());
        }
        return found;
    }

    private void requireState(S state) {
        if (!key.states().isInstance(state)) {
            throw new IllegalArgumentException("state must belong to " + key.states().getName() + ": " + state.name());
        }
    }

    @Override
    public String toString() {
        return key + "[" + graph.nodes().size() + " states, " + transitions.size() + " transitions]";
    }
}
