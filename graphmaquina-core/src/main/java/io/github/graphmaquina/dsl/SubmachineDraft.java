package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.SubmachineSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Draft of the sub-machine bound to one state, accumulating its exits as declared, repeats included: the outline
 * hands them all to the validator, and the spec keeps the first exit of each terminal.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class SubmachineDraft<E, S extends Enum<S>, T extends Enum<T>, P, Q extends Enum<Q>, U extends Enum<U>>
        implements SubmachineBuilder<E, S, T, P, Q, U> {

    /** One declared exit: a state of the child and the identifier of the parent it maps to. */
    private record Exit<Q, T>(Q terminal, T transition) { }

    private final DefaultGraphBuilder<E, S, T> graph;
    private final S state;
    private final GraphDefinition<P, Q, U> child;
    private final Function<? super E, Optional<P>> childOf;
    private final Function<? super P, Optional<E>> parentOf;
    private final List<Exit<Q, T>> exits = new ArrayList<>();

    S state() { return state; }

    GraphDefinition<P, Q, U> child() { return child; }

    @Override
    public SubmachineBuilder<E, S, T, P, Q, U> exit(@NonNull Q terminal, @NonNull T transition) {
        requireUnbuilt();
        requireChildState(terminal);
        graph.requireTransition(transition);
        exits.add(new Exit<>(terminal, transition));
        return this;
    }

    @Override
    public GraphBuilder<E, S, T> endSubmachine() {
        requireUnbuilt();
        return graph;
    }

    /** Once the graph is built nothing reads this binding any more, so a late call would be lost. */
    private void requireUnbuilt() {
        graph.requireUnbuilt("the sub-machine of " + state.name());
    }

    private void requireChildState(Q terminal) {
        Class<Q> states = child.key().states();
        if (!states.isInstance(terminal)) {
            throw new IllegalArgumentException("state must belong to " + states.getName() + ": " + terminal.name());
        }
    }

    /** The binding, with the first exit declared for each terminal; the outline reports any repeat as SG801. */
    SubmachineSpec<E, S, T, P, Q, U> toSpec() {
        Map<Q, T> first = new LinkedHashMap<>();
        for (Exit<Q, T> exit : exits) {
            first.putIfAbsent(exit.terminal(), exit.transition());
        }
        return new SubmachineSpec<>(state, child, childOf, parentOf, first);
    }

    /** By names: the child's terminal states in its declaration order, and every exit as declared. */
    SubmachineOutline toOutline() {
        List<String> childTerminals = new ArrayList<>();
        for (Q candidate : child.key().states().getEnumConstants()) {
            if (child.terminals().contains(candidate)) {
                childTerminals.add(candidate.name());
            }
        }
        List<ExitOutline> declared = new ArrayList<>();
        for (Exit<Q, T> exit : exits) {
            declared.add(new ExitOutline(exit.terminal().name(), exit.transition().name()));
        }
        return new SubmachineOutline(state.name(), child.key().toString(), childTerminals, declared);
    }
}
