package io.stategraph.dsl;

import io.stategraph.api.GraphDefinition;
import io.stategraph.api.MachineKey;
import io.stategraph.error.InvalidGraphException;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Default, single-use implementation of {@link GraphBuilder}: accumulates drafts and assembles them on build(). */
final class DefaultGraphBuilder<E, S extends Enum<S>> implements GraphBuilder<E, S> {

    private final MachineKey<E, S> key;
    private final S[] states;
    private Optional<S> initial = Optional.empty();
    private int initialDeclarations;
    private final Set<S> terminals;
    private final Set<S> cycleAllowed;
    private final Map<CellKey<S>, EdgeDraft<E, S>> cells = new LinkedHashMap<>();
    private final Map<S, String> rowForbids;
    private final Map<S, WatchDraft<E, S, ?, ?>> watches;
    private final Problems problems = new Problems();
    private boolean built;

    DefaultGraphBuilder(MachineKey<E, S> key) {
        this.key = key;
        this.states = key.states().getEnumConstants();
        this.terminals = EnumSet.noneOf(key.states());
        this.cycleAllowed = EnumSet.noneOf(key.states());
        this.rowForbids = new EnumMap<>(key.states());
        this.watches = new EnumMap<>(key.states());
    }

    Problems problems() { return problems; }

    @Override
    public GraphBuilder<E, S> initial(S state) {
        Objects.requireNonNull(state, "state");
        initialDeclarations++;
        initial = Optional.of(state);
        return this;
    }

    @Override
    public GraphBuilder<E, S> terminal(S state) {
        Objects.requireNonNull(state, "state");
        terminals.add(state);
        return this;
    }

    @Override
    public GraphBuilder<E, S> allowsCycle(S state) {
        Objects.requireNonNull(state, "state");
        cycleAllowed.add(state);
        return this;
    }

    @Override
    public RowBuilder<E, S> from(S state) {
        Objects.requireNonNull(state, "state");
        return new RowDraft<>(this, state);
    }

    EdgeDraft<E, S> declare(RowDraft<E, S> row, S from, S to) {
        EdgeDraft<E, S> draft = new EdgeDraft<>(row, from, to);
        EdgeDraft<E, S> previous = cells.putIfAbsent(new CellKey<>(from, to), draft);
        if (previous != null) {
            problems.add("SG102", from.ordinal(), to.ordinal(), "the cell " + from + " → " + to + " is declared twice");
        }
        return draft;
    }

    void forbidRow(S from, String reason) {
        if (reason.isBlank()) {
            problems.add("SG103", from.ordinal(), -1, "forbidOthers of row " + from + " has a blank reason");
        }
        if (rowForbids.putIfAbsent(from, reason) != null) {
            problems.add("SG102", from.ordinal(), -1, "forbidOthers repeated on row " + from);
        }
    }

    <P, Q extends Enum<Q>> WatchDraft<E, S, P, Q> declareWatch(RowDraft<E, S> row, S state, MachineKey<P, Q> peer,
                                                               Function<? super E, ? extends Collection<? extends P>> peersOf,
                                                               Function<? super P, Optional<E>> ownerOf) {
        WatchDraft<E, S, P, Q> draft = new WatchDraft<>(this, row, state, peer, peersOf, ownerOf);
        if (watches.putIfAbsent(state, draft) != null) {
            problems.add("SG403", state.ordinal(), -1, "the state " + state + " declares two watches");
        }
        return draft;
    }

    @Override
    public GraphDefinition<E, S> build() {
        if (built) {
            throw new IllegalStateException("build() was already called on the graph " + key);
        }
        built = true;
        Assembly<E, S> assembly = GraphAssembler.assemble(key, states, initialDeclarations, cells, rowForbids, watches, problems);
        // Task 6: GraphValidator.validate(key, states, initial, terminals, cycleAllowed, assembly, problems);
        if (!problems.isEmpty()) {
            throw new InvalidGraphException(key, problems.sorted());
        }
        return new DslGraphDefinition<>(key, states, initial.orElseThrow(), terminals, assembly.specs(), assembly.watches());
    }
}
