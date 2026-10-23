package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.RecoveryRule;
import io.github.graphmaquina.api.SubmachineSpec;
import io.github.graphmaquina.api.TransitionSpec;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The walk, at assembly, of the edges that a call of a machine can run implicitly, as the Javadoc of
 * {@link MachineRegistry} lists them, which finds the first asynchronous one: a machine that can run one must declare
 * asyncTimeout.
 */
final class ImplicitAsyncEdges {

    private ImplicitAsyncEdges() { }

    /** The exits of one binding, with the parent's graph that resolves them. */
    record BoundExits<E, S extends Enum<S>, T extends Enum<T>>(GraphDefinition<E, S, T> parent,
                                                               SubmachineSpec<E, S, T, ?, ?, ?> spec) { }

    /** Structural identity of an implicit edge: diagnostic simple names do not distinguish actual machine types. */
    private record ImplicitEdgeKey(MachineKey<?, ?, ?> key, Enum<?> from, Enum<?> transition) { }

    /** An edge that the engine can run implicitly, with the graph it belongs to. */
    private record ImplicitEdge<E, S extends Enum<S>, T extends Enum<T>>(GraphDefinition<E, S, T> graph,
                                                                         TransitionSpec<E, S, T> edge) {

        /** {@code <Key>.<FROM> -<ID>->}, which names the edge in its graph. */
        String describe() {
            return graph.key() + "." + edge.from().name() + " -" + edge.transition().name() + "->";
        }
    }

    /**
     * The first asynchronous edge, as {@code <Key>.<FROM> -<ID>->}, that a call of the machine {@code graph.key()} can
     * run implicitly, as the Javadoc of {@link MachineRegistry} lists them. The breadth-first walk visits each edge
     * once by its actual machine key, origin and identifier, so it ends even on bindings that hand-written definitions
     * make circular. It starts from the exit edges of the bindings in which the machine is the child, in the order the
     * registry indexed those bindings, which is the order of the builders and then of each parent's states. Each
     * binding's exits follow identifier order. Next come exits selected by terminal initial descendants of its own edge
     * destinations, in graph transition order and along each child's initial binding chain. Own edges themselves are
     * not enqueued. The walk then takes the edges in the order it reached them, and adds after each one: its failure
     * routes, in the order of their identifiers; for a terminal destination, the exit that each binding of its machine,
     * in index order, maps that state to; and for a destination that holds a sub-machine, the exits of that binding, in
     * the order of their identifiers, followed by the exits selected by that binding's terminal initial descendants.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> Optional<String> firstAsyncImplicitEdge(
            GraphDefinition<E, S, T> graph, Map<MachineKey<?, ?, ?>, List<BoundExits<?, ?, ?>>> exitsByChild) {
        Deque<ImplicitEdge<?, ?, ?>> queue = new ArrayDeque<>();
        Set<ImplicitEdgeKey> visited = new HashSet<>();
        Set<MachineKey<?, ?, ?>> initialized = new HashSet<>();
        for (BoundExits<?, ?, ?> bound : exitsByChild.getOrDefault(graph.key(), List.of())) {
            enqueueExits(bound, queue, visited);
        }
        for (TransitionSpec<E, S, T> edge : graph.transitions()) {
            enqueueInitialExits(graph, edge.to(), queue, visited, initialized);
        }
        while (!queue.isEmpty()) {
            ImplicitEdge<?, ?, ?> next = queue.removeFirst();
            if (next.edge().isAsync()) {
                return Optional.of(next.describe());
            }
            enqueueSuccessors(next, exitsByChild, queue, visited, initialized);
        }
        return Optional.empty();
    }

    /**
     * What {@code implicit} leads the engine to run implicitly in turn, in the order {@link #firstAsyncImplicitEdge}
     * gives.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> void enqueueSuccessors(
            ImplicitEdge<E, S, T> implicit, Map<MachineKey<?, ?, ?>, List<BoundExits<?, ?, ?>>> exitsByChild,
            Deque<ImplicitEdge<?, ?, ?>> queue, Set<ImplicitEdgeKey> visited, Set<MachineKey<?, ?, ?>> initialized) {
        GraphDefinition<E, S, T> graph = implicit.graph();
        TransitionSpec<E, S, T> edge = implicit.edge();
        Set<T> routes = new TreeSet<>();
        edge.onFailure().ifPresent(routes::add);
        for (RecoveryRule<T> rule : edge.recoveryRules()) {
            routes.add(rule.transition());
        }
        for (T route : routes) {
            enqueue(graph, edge.from(), route, queue, visited);
        }
        S reached = edge.to();
        if (graph.terminals().contains(reached)) {
            for (BoundExits<?, ?, ?> bound : exitsByChild.getOrDefault(graph.key(), List.of())) {
                enqueueExitOf(bound, reached, queue, visited);
            }
        }
        Optional<SubmachineSpec<E, S, T, ?, ?, ?>> held = graph.submachine(reached);
        if (held.isPresent()) {
            enqueueExits(new BoundExits<>(graph, held.get()), queue, visited);
            enqueueInitialExits(graph, reached, queue, visited, initialized);
        }
    }

    /** The exits selected by terminal initial descendants when an edge enters {@code state}, without any cascade. */
    private static <E, S extends Enum<S>, T extends Enum<T>> void enqueueInitialExits(
            GraphDefinition<E, S, T> graph, S state, Deque<ImplicitEdge<?, ?, ?>> queue, Set<ImplicitEdgeKey> visited,
            Set<MachineKey<?, ?, ?>> initialized) {
        Optional<SubmachineSpec<E, S, T, ?, ?, ?>> held = graph.submachine(state);
        if (held.isPresent()) {
            enqueueStartedBinding(graph, held.get(), queue, visited, initialized);
        }
    }

    /**
     * A started child's terminal initial state selects its binding's mapped exit. Its bound initial descendants may
     * select their own exits too. Each child's initial binding is expanded once, so foreign definition cycles end;
     * mapping the terminal precedes that check because different bindings of the same child can map different exits.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>, P, Q extends Enum<Q>, U extends Enum<U>> void
            enqueueStartedBinding(GraphDefinition<E, S, T> parent, SubmachineSpec<E, S, T, P, Q, U> spec,
                                  Deque<ImplicitEdge<?, ?, ?>> queue, Set<ImplicitEdgeKey> visited,
                                  Set<MachineKey<?, ?, ?>> initialized) {
        GraphDefinition<P, Q, U> child = spec.child();
        Q initial = child.initial();
        if (child.terminals().contains(initial)) {
            enqueueExitOf(new BoundExits<>(parent, spec), initial, queue, visited);
        }
        if (initialized.add(child.key())) {
            enqueueInitialExits(child, initial, queue, visited, initialized);
        }
    }

    /** Every exit edge of {@code bound}, in the order of its identifiers. */
    private static <E, S extends Enum<S>, T extends Enum<T>> void enqueueExits(
            BoundExits<E, S, T> bound, Deque<ImplicitEdge<?, ?, ?>> queue, Set<ImplicitEdgeKey> visited) {
        for (T exit : new TreeSet<>(bound.spec().exits().values())) {
            enqueue(bound.parent(), bound.spec().state(), exit, queue, visited);
        }
    }

    /** The exit edge that {@code bound} maps the child's {@code terminal} to, if it maps it to one. */
    private static <E, S extends Enum<S>, T extends Enum<T>> void enqueueExitOf(
            BoundExits<E, S, T> bound, Enum<?> terminal, Deque<ImplicitEdge<?, ?, ?>> queue,
            Set<ImplicitEdgeKey> visited) {
        Optional<T> exit = Optional.ofNullable(bound.spec().exits().get(terminal));
        if (exit.isPresent()) {
            enqueue(bound.parent(), bound.spec().state(), exit.get(), queue, visited);
        }
    }

    /** The edge {@code id} from {@code from} in {@code graph}, unless it has none or the walk already reached it. */
    private static <E, S extends Enum<S>, T extends Enum<T>> void enqueue(
            GraphDefinition<E, S, T> graph, S from, T id, Deque<ImplicitEdge<?, ?, ?>> queue,
            Set<ImplicitEdgeKey> visited) {
        Optional<TransitionSpec<E, S, T>> edge = graph.findTransition(from, id);
        if (edge.isPresent()) {
            ImplicitEdge<E, S, T> implicit = new ImplicitEdge<>(graph, edge.get());
            if (visited.add(new ImplicitEdgeKey(graph.key(), implicit.edge().from(),
                    implicit.edge().transition()))) {
                queue.addLast(implicit);
            }
        }
    }
}
