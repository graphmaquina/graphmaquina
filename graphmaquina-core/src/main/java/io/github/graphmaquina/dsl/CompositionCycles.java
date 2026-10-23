package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.SubmachineSpec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The composition-cycle check of a graph being built (SG805): whether its sub-machines, followed through the
 * children's own sub-machines, contain a definition of the graph's own key again.
 */
final class CompositionCycles {

    private CompositionCycles() { }

    /** One step of the walk of {@link #check}: a definition, its path and that path's ancestor keys. */
    private record Visit(GraphDefinition<?, ?, ?> definition, String path, Set<MachineKey<?, ?, ?>> ancestors) {
        Visit {
            ancestors = Set.copyOf(ancestors);
        }
    }

    /**
     * Records SG805 in {@code problems} when {@code submachines}, the sub-machine drafts of the graph of {@code key},
     * followed through the children's own sub-machines, reach a definition of {@code key} along a path this walk
     * expands. The walk is breadth first, so the path it reports is a shortest one: it starts from the graph's
     * bindings in the order of their states, and visits each child's states in declaration order. It never expands a
     * key that is already on the path, so a hand-written definition that hands out new instances of itself still
     * ends; distinct definitions of the same key are each checked on their own paths, but a second definition of a
     * key already on a path is not expanded there. It takes a definition's bindings to be the same each time it asks,
     * so what a visit expands depends only on its definition and the keys on its path, and the walk expands each such
     * pair once: paths that reach one definition through the same keys, such as a child bound in several states of
     * each level, cost one expansion, not one per path. A return to {@code key} is checked before either pruning.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> void check(MachineKey<E, S, T> key,
                                                                List<SubmachineDraft<E, S, T, ?, ?, ?>> submachines,
                                                                Problems problems) {
        List<SubmachineDraft<E, S, T, ?, ?, ?>> byState = new ArrayList<>(submachines);
        byState.sort(Comparator.comparingInt((SubmachineDraft<E, S, T, ?, ?, ?> draft) -> draft.state().ordinal()));
        Deque<Visit> queue = new ArrayDeque<>();
        for (SubmachineDraft<E, S, T, ?, ?, ?> draft : byState) {
            queue.add(new Visit(draft.child(), key + "." + draft.state().name(), Set.of(key)));
        }
        Map<GraphDefinition<?, ?, ?>, Set<Set<MachineKey<?, ?, ?>>>> expanded = new IdentityHashMap<>();
        while (!queue.isEmpty()) {
            Visit visit = queue.removeFirst();
            MachineKey<?, ?, ?> reached = visit.definition().key();
            if (reached.equals(key)) {
                problems.add("SG805", "the sub-machines of " + key + " contain " + key + " again: " + visit.path()
                        + " -> " + key);
                return;
            }
            if (!visit.ancestors().contains(reached) && expanded.computeIfAbsent(visit.definition(),
                    (GraphDefinition<?, ?, ?> definition) -> new HashSet<>()).add(visit.ancestors())) {
                Set<MachineKey<?, ?, ?>> ancestors = new HashSet<>(visit.ancestors());
                ancestors.add(reached);
                visitChildren(visit.definition(), visit.path(), ancestors, queue);
            }
        }
    }

    /** Queues each child in state order, with its path and an immutable copy of that path's ancestor keys. */
    private static <P, Q extends Enum<Q>, U extends Enum<U>> void visitChildren(GraphDefinition<P, Q, U> definition,
            String path, Set<MachineKey<?, ?, ?>> ancestors, Deque<Visit> queue) {
        for (Q state : definition.key().states().getEnumConstants()) {
            Optional<SubmachineSpec<P, Q, U, ?, ?, ?>> bound = definition.submachine(state);
            if (bound.isPresent()) {
                queue.add(new Visit(bound.get().child(), path + " -> " + definition.key() + "." + state.name(),
                        ancestors));
            }
        }
    }
}
