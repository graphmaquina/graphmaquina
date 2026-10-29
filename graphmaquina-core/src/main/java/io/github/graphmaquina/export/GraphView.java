package io.github.graphmaquina.export;

import io.github.graphmaquina.api.ActionRef;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardRef;
import io.github.graphmaquina.api.RecoveryRule;
import io.github.graphmaquina.api.StatePolicy;
import io.github.graphmaquina.api.SubmachineSpec;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.api.WatchRule;
import io.github.graphmaquina.api.WatchSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;

/**
 * Structural view of a graph by names only, built by {@link #of} from a {@link GraphDefinition}. Every collection it
 * holds keeps the order it was given in, its terminals included, and refuses a null element. Each sub-machine bound
 * to one of its states carries the child's own view, so a view holds the whole tree of its sub-machines.
 */
public record GraphView(@NonNull String name, @NonNull String initial, @NonNull Set<String> terminals,
                        @NonNull List<String> states, @NonNull List<TransitionView> transitions,
                        @NonNull List<PolicyView> policies, @NonNull List<WatchView> watches,
                        @NonNull List<SubmachineView> submachines) {

    public GraphView {
        // Given order, kept as the lists keep theirs: Set.copyOf iterates in an order salted per JVM, so the same
        // graph would list its terminals differently between runs.
        Set<String> ordered = new LinkedHashSet<>();
        for (String terminal : terminals) {
            ordered.add(Objects.requireNonNull(terminal, "terminal"));
        }
        terminals = Collections.unmodifiableSet(ordered);
        states = List.copyOf(states);
        transitions = List.copyOf(transitions);
        policies = List.copyOf(policies);
        watches = List.copyOf(watches);
        submachines = List.copyOf(submachines);
    }

    /** A view of a graph that binds no sub-machine. */
    public GraphView(String name, String initial, Set<String> terminals, List<String> states,
                     List<TransitionView> transitions, List<PolicyView> policies, List<WatchView> watches) {
        this(name, initial, terminals, states, transitions, policies, watches, List.of());
    }

    /**
     * Builds the view of {@code definition}: every state in the graph's node order, every declared edge in the
     * definition's order with its recovery rules in declaration order, each state's policy, the watches, the
     * sub-machines in node order, each with the view of its child, built the same way, and with its exits in
     * declaration order, and the terminals in node order. A rejection is never an edge.
     */
    public static <E, S extends Enum<S>, T extends Enum<T>> GraphView of(@NonNull GraphDefinition<E, S, T> definition) {
        List<String> states = new ArrayList<>();
        for (S state : definition.graph().nodes()) {
            states.add(state.name());
        }
        List<TransitionView> transitions = transitions(definition);
        List<PolicyView> policies = policies(definition);
        List<WatchView> watches = watches(definition);
        List<SubmachineView> submachines = new ArrayList<>();
        for (S state : definition.graph().nodes()) {
            Optional<SubmachineSpec<E, S, T, ?, ?, ?>> submachine = definition.submachine(state);
            if (submachine.isPresent()) {
                submachines.add(submachine(submachine.get()));
            }
        }
        Set<String> terminals = new LinkedHashSet<>();
        for (S state : definition.graph().nodes()) {
            if (definition.terminals().contains(state)) {
                terminals.add(state.name());
            }
        }
        return new GraphView(definition.key().toString(), definition.initial().name(), terminals, states, transitions,
                policies, watches, submachines);
    }

    /** Every declared edge of {@code definition} in its order, with its guards and recovery rules by names. */
    private static <E, S extends Enum<S>, T extends Enum<T>> List<TransitionView> transitions(
            GraphDefinition<E, S, T> definition) {
        List<TransitionView> transitions = new ArrayList<>();
        for (TransitionSpec<E, S, T> spec : definition.transitions()) {
            List<String> guards = new ArrayList<>();
            for (GuardRef<E, S, T> guard : spec.guards()) {
                guards.add(guard.name());
            }
            transitions.add(new TransitionView(spec.from().name(), spec.transition().name(), spec.to().name(),
                    spec.kind(), spec.action().map(ActionRef::name), guards, spec.isAsync(),
                    spec.onFailure().map(Enum::name), watchLabel(definition, spec), recoveries(spec.recoveryRules()),
                    spec.automatic(), automaticPosition(definition, spec), spec.onFailureContinues()));
        }
        return transitions;
    }

    /**
     * The 1-based place of {@code spec} among the automatic edges of its origin, as {@code automaticFrom} lists them,
     * when there are two or more of them and so the state declares an order; empty otherwise.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> Optional<Integer> automaticPosition(
            GraphDefinition<E, S, T> definition, TransitionSpec<E, S, T> spec) {
        if (!spec.automatic()) {
            return Optional.empty();
        }
        List<TransitionSpec<E, S, T>> ordered = definition.automaticFrom(spec.from());
        return ordered.size() < 2 ? Optional.empty() : Optional.of(ordered.indexOf(spec) + 1);
    }

    /** The policy of each state of {@code definition}, in node order, with its rejections by names. */
    private static <E, S extends Enum<S>, T extends Enum<T>> List<PolicyView> policies(
            GraphDefinition<E, S, T> definition) {
        List<PolicyView> policies = new ArrayList<>();
        for (S state : definition.graph().nodes()) {
            StatePolicy<S, T> policy = definition.policy(state);
            List<String> rejections = new ArrayList<>();
            for (Map.Entry<T, String> rejection : policy.rejectionReasons().entrySet()) {
                rejections.add(rejection.getKey().name() + ": " + rejection.getValue());
            }
            policies.add(new PolicyView(state.name(), policy.otherwise(), rejections));
        }
        return policies;
    }

    /** The watch of each state of {@code definition} that has one, in node order, with its rules by names. */
    private static <E, S extends Enum<S>, T extends Enum<T>> List<WatchView> watches(
            GraphDefinition<E, S, T> definition) {
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
        return watches;
    }

    /** The view of one binding: its state, the child's view and the exits by names, in declaration order. */
    private static <E, S extends Enum<S>, T extends Enum<T>, P, Q extends Enum<Q>, U extends Enum<U>> SubmachineView
            submachine(SubmachineSpec<E, S, T, P, Q, U> spec) {
        Map<String, String> exits = new LinkedHashMap<>();
        spec.exits().forEach((Q terminal, T exit) -> exits.put(terminal.name(), exit.name()));
        return new SubmachineView(spec.state().name(), of(spec.child()), exits);
    }

    /**
     * The rules by names, kinds and throwable types as {@link Class#getName()} gives them, in declaration order. An
     * edge without rules gets the shared empty list, so its view allocates nothing for them.
     */
    private static <T extends Enum<T>> List<RecoveryView> recoveries(List<RecoveryRule<T>> rules) {
        if (rules.isEmpty()) {
            return List.of();
        }
        List<RecoveryView> views = new ArrayList<>();
        for (RecoveryRule<T> rule : rules) {
            List<String> failsOn = new ArrayList<>();
            for (Class<? extends Throwable> type : rule.failsOn()) {
                failsOn.add(type.getName());
            }
            views.add(new RecoveryView(rule.kind().getName(), rule.transition().name(), failsOn,
                    rule.continueChain()));
        }
        return views;
    }

    private static <E, S extends Enum<S>, T extends Enum<T>> Optional<String> watchLabel(
            GraphDefinition<E, S, T> definition, TransitionSpec<E, S, T> spec) {
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
