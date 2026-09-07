package io.stategraph.export;

import io.stategraph.api.ActionRef;
import io.stategraph.api.EdgeSpec;
import io.stategraph.api.GraphDefinition;
import io.stategraph.api.GuardRef;
import io.stategraph.api.WatchRule;
import io.stategraph.api.WatchSpec;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;

/** Structural view of a graph by names only. The DSL produces it today; a processor will produce it tomorrow. */
public record GraphView(@NonNull String name, @NonNull String initial, @NonNull Set<String> terminals,
                        @NonNull List<EdgeView> edges, @NonNull List<WatchView> watches) {

    public GraphView {
        terminals = Set.copyOf(terminals);
        edges = List.copyOf(edges);
        watches = List.copyOf(watches);
    }

    /** Builds the view of {@code definition} by walking its full N&times;N matrix in ordinal order. */
    public static <E, S extends Enum<S>> GraphView of(@NonNull GraphDefinition<E, S> definition) {
        S[] states = definition.key().states().getEnumConstants();
        List<EdgeView> edges = new ArrayList<>();
        for (S from : states) {
            for (S to : states) {
                EdgeSpec<E, S> spec = definition.edge(from, to);
                List<String> failureActions = new ArrayList<>();
                for (S other : states) {
                    EdgeSpec<E, S> candidate = definition.edge(from, other);
                    if (candidate.onFailure().isPresent() && candidate.onFailure().get() == to && candidate.action().isPresent()) {
                        failureActions.add(candidate.action().get().name());
                    }
                }
                Optional<String> failureOf =
                        failureActions.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", failureActions));
                Optional<String> watchRule = Optional.empty();
                Optional<WatchSpec<E, S, ?, ?>> watch = definition.watch(from);
                if (watch.isPresent()) {
                    watchRule = ruleLabel(watch.get(), to);
                }
                List<String> guards = new ArrayList<>();
                for (GuardRef<E, S> guard : spec.guards()) {
                    guards.add(guard.name());
                }
                edges.add(new EdgeView(from.name(), to.name(), spec.kind(), spec.reason(), spec.action().map(ActionRef::name),
                        guards, spec.isAsync(), failureOf, watchRule));
            }
        }
        List<WatchView> watches = new ArrayList<>();
        for (S state : states) {
            Optional<WatchSpec<E, S, ?, ?>> watch = definition.watch(state);
            if (watch.isPresent()) {
                List<String> rules = new ArrayList<>();
                for (WatchRule<E, S, ?, ?> rule : watch.get().rules()) {
                    rules.add(rule.condition().name() + " → " + rule.target().name());
                }
                watches.add(new WatchView(state.name(), watch.get().peer().entity().getSimpleName(), rules));
            }
        }
        Set<String> terminals = new LinkedHashSet<>();
        for (S state : states) {
            if (definition.terminals().contains(state)) {
                terminals.add(state.name());
            }
        }
        return new GraphView(definition.key().toString(), definition.initial().name(), terminals, edges, watches);
    }

    private static <E, S extends Enum<S>> Optional<String> ruleLabel(WatchSpec<E, S, ?, ?> watch, S to) {
        List<String> names = new ArrayList<>();
        for (WatchRule<E, S, ?, ?> rule : watch.rules()) {
            if (rule.target() == to) {
                names.add(rule.condition().name());
            }
        }
        if (names.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(watch.peer().entity().getSimpleName() + ": " + String.join(", ", names));
    }
}
