package io.github.graphmaquina.fixtures;

import io.github.graphmaquina.api.*;
import io.github.graphmaquina.dsl.*;
import io.github.graphmaquina.runtime.*;
import java.util.ArrayList;
import java.util.List;

public final class GraphOnlyFixture {
    private GraphOnlyFixture() { }
    public enum State { OPEN, REVIEW, DONE }
    public enum Id { SUBMIT, APPROVE, AUTO_APPROVE, RETOUCH, FAIL, BACK }
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
