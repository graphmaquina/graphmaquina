package io.github.graphmaquina.export;

import io.github.graphmaquina.api.ActionRef;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardRef;
import io.github.graphmaquina.api.StatePolicy;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.api.WatchRule;
import io.github.graphmaquina.api.WatchSpec;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;

/** Structural view of a graph by names only, built by {@link #of} from a {@link GraphDefinition}. */
public record GraphView(@NonNull String name, @NonNull String initial, @NonNull Set<String> terminals,
                        @NonNull List<String> states, @NonNull List<TransitionView> transitions,
                        @NonNull List<PolicyView> policies, @NonNull List<WatchView> watches) {

    public GraphView {
        terminals = Set.copyOf(terminals);
        states = List.copyOf(states);
        transitions = List.copyOf(transitions);
        policies = List.copyOf(policies);
        watches = List.copyOf(watches);
    }

    /**
     * Builds the view of {@code definition}: every state in the graph's node order, every declared edge in the
     * definition's order, each state's policy, and the watches. A rejection is never an edge.
     */
    public static <E, S extends Enum<S>, T extends Enum<T>> GraphView of(@NonNull GraphDefinition<E, S, T> definition) {
        List<String> states = new ArrayList<>();
        for (S state : definition.graph().nodes()) {
            states.add(state.name());
        }
        List<TransitionView> transitions = new ArrayList<>();
        for (TransitionSpec<E, S, T> spec : definition.transitions()) {
            List<String> guards = new ArrayList<>();
            for (GuardRef<E, S, T> guard : spec.guards()) {
                guards.add(guard.name());
            }
            transitions.add(new TransitionView(spec.from().name(), spec.transition().name(), spec.to().name(), spec.kind(),
                    spec.action().map(ActionRef::name), guards, spec.isAsync(), spec.onFailure().map(Enum::name),
                    watchLabel(definition, spec)));
        }
        List<PolicyView> policies = new ArrayList<>();
        for (S state : definition.graph().nodes()) {
            StatePolicy<S, T> policy = definition.policy(state);
            List<String> rejections = new ArrayList<>();
            for (Map.Entry<T, String> rejection : policy.rejectionReasons().entrySet()) {
                rejections.add(rejection.getKey().name() + ": " + rejection.getValue());
            }
            policies.add(new PolicyView(state.name(), policy.otherwise(), rejections));
        }
        List<WatchView> watches = new ArrayList<>();
        for (S state : definition.graph().nodes()) {
            Optional<WatchSpec<E, S, T, ?, ?, ?>> watch = definition.watch(state);
            if (watch.isEmpty()) {
                continue;
            }
            List<String> rules = new ArrayList<>();
            for (WatchRule<E, S, T, ?, ?, ?> rule : watch.get().rules()) {
                rules.add(rule.condition().name() + " → " + rule.transition().name());
            }
            watches.add(new WatchView(state.name(), watch.get().peer().entity().getSimpleName(), rules));
        }
        Set<String> terminals = new LinkedHashSet<>();
        for (S state : definition.graph().nodes()) {
            if (definition.terminals().contains(state)) {
                terminals.add(state.name());
            }
        }
        return new GraphView(definition.key().toString(), definition.initial().name(), terminals, states, transitions,
                policies, watches);
    }

    private static <E, S extends Enum<S>, T extends Enum<T>> Optional<String> watchLabel(GraphDefinition<E, S, T> definition,
                                                                                         TransitionSpec<E, S, T> spec) {
        Optional<WatchSpec<E, S, T, ?, ?, ?>> watch = definition.watch(spec.from());
        if (watch.isEmpty()) {
            return Optional.empty();
        }
        List<String> names = new ArrayList<>();
        for (WatchRule<E, S, T, ?, ?, ?> rule : watch.get().rules()) {
            if (rule.transition() == spec.transition()) {
                names.add(rule.condition().name());
            }
        }
        if (names.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(watch.get().peer().entity().getSimpleName() + ": " + String.join(", ", names));
    }
}
