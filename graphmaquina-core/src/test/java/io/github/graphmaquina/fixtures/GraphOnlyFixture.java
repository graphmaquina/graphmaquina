package io.github.graphmaquina.fixtures;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;
import java.util.ArrayList;
import java.util.List;

/**
 * The shared fixture of the graph-only tests: a ticket machine with three states and a catalogue of six
 * identifiers, of which the base builder declares only two edges. Tests add the edges they need on top, which is
 * what makes absence, parallel edges and self-loops observable.
 */
public final class GraphOnlyFixture {
    private GraphOnlyFixture() { }

    /** States of the ticket machine. */
    public enum State { OPEN, REVIEW, DONE }

    /** Catalogue of transition identifiers; the base builder leaves most of them without an edge. */
    public enum Id { SUBMIT, APPROVE, AUTO_APPROVE, RETOUCH, FAIL, BACK }

    /** The entity: a mutable state plus the log its actions and hooks append to. */
    public static final class Ticket {
        public final int id;
        public State state;
        public final List<String> calls = new ArrayList<>();
        public Ticket(int id, State state) { this.id = id; this.state = state; }
    }

    public static final MachineKey<Ticket,State,Id> KEY =
        MachineKey.of(Ticket.class, State.class, Id.class);
    public static GraphBuilder<Ticket,State,Id> builder() {
        return StateGraph.define(KEY).initial(State.OPEN).terminal(State.DONE)
            .statePolicy(State.OPEN, "submit first")
            .statePolicy(State.REVIEW, "review required")
            .transition(Id.SUBMIT, State.OPEN, State.REVIEW).endTransition()
            .transition(Id.APPROVE, State.REVIEW, State.DONE)
                .action("manual", ctx -> { ctx.entity().calls.add("manual"); return ActionOutcome.ok(); })
                .endTransition();
    }
    public static MachineRegistry registry(GraphDefinition<Ticket,State,Id> graph) {
        return MachineRegistry.builder().add(StateMachines.from(graph)
            .state(ticket -> ticket.state, (ticket, state) -> ticket.state = state)
            .identity(ticket -> ticket.id)).build();
    }
}
