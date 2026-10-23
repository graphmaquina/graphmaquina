package io.github.graphmaquina.fixtures;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.runtime.StateMachines;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The shared fixture of the typed-recovery tests: the publication machine every example graph is built on. PUBLISH
 * leaves READY towards PUBLISHED and is configured entirely by each test, which declares its action and every recovery
 * rule, failsOn and onFailure it needs. The rest of the graph is fixed: SCHEDULE_RETRY, RECORD_REJECTION and
 * MARK_INTEGRATION_FAILED leave READY, RETRY returns to READY, and CLOSE ends both failure states.
 */
public final class RecoveryFixtures {
    private RecoveryFixtures() { }

    /** States of the publication machine. */
    public enum TicketState { READY, PUBLISHED, RETRY_PENDING, REJECTED_BY_PROVIDER, INTEGRATION_FAILED, CLOSED }

    /** Catalogue of transition identifiers. */
    public enum TicketChange { PUBLISH, SCHEDULE_RETRY, RECORD_REJECTION, MARK_INTEGRATION_FAILED, RETRY, CLOSE }

    /** The failure kinds a publication adapter reports. */
    public static final class PublicationFailures {
        private PublicationFailures() { }

        /** The provider could not take the publication now. */
        public record Unavailable() implements FailureKind { }

        /** The provider refused the content. */
        public record Rejected() implements FailureKind { }
    }

    /** Thrown by an adapter when the provider is temporarily unavailable. */
    public static final class ServiceUnavailableException extends RuntimeException {
        public ServiceUnavailableException(String message) {
            super(message);
        }
    }

    /** Thrown by an adapter when the provider rejects the request. */
    public static final class ProviderRejectedException extends RuntimeException {
        public ProviderRejectedException(String message) {
            super(message);
        }
    }

    /** Thrown by an adapter when the provider's response cannot be read. */
    public static final class InvalidResponseException extends RuntimeException {
        public InvalidResponseException(String message) {
            super(message);
        }
    }

    /** The entity: its state, how many times the engine wrote that state, and the log its actions append to. */
    public static final class Ticket {
        public final String id;
        public TicketState state = TicketState.READY;
        public int stateWrites;
        public final List<String> calls = new ArrayList<>();

        public Ticket(String id) {
            this.id = id;
        }
    }

    public static final MachineKey<Ticket, TicketState, TicketChange> KEY =
            MachineKey.of(Ticket.class, TicketState.class, TicketChange.class);

    /**
     * The publication graph with PUBLISH configured by {@code publish}, which declares the action and every rule,
     * failsOn and onFailure the test needs; the fixture adds nothing to PUBLISH.
     */
    public static GraphDefinition<Ticket, TicketState, TicketChange> graph(
            UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> publish) {
        GraphBuilder<Ticket, TicketState, TicketChange> builder = StateGraph.define(KEY)
                .initial(TicketState.READY).terminal(TicketState.PUBLISHED).terminal(TicketState.CLOSED)
                .allowsCycle(TicketState.READY)
                .statePolicy(TicketState.READY, "publish the ticket")
                .statePolicy(TicketState.RETRY_PENDING, "retry the publication explicitly")
                .statePolicy(TicketState.REJECTED_BY_PROVIDER, "close the rejected ticket")
                .statePolicy(TicketState.INTEGRATION_FAILED, "close after manual review");
        return publish.apply(builder.transition(TicketChange.PUBLISH, TicketState.READY, TicketState.PUBLISHED))
                .endTransition()
                .transition(TicketChange.SCHEDULE_RETRY, TicketState.READY, TicketState.RETRY_PENDING).endTransition()
                .transition(TicketChange.RECORD_REJECTION, TicketState.READY, TicketState.REJECTED_BY_PROVIDER)
                        .endTransition()
                .transition(TicketChange.MARK_INTEGRATION_FAILED, TicketState.READY, TicketState.INTEGRATION_FAILED)
                        .endTransition()
                .transition(TicketChange.RETRY, TicketState.RETRY_PENDING, TicketState.READY).endTransition()
                .transition(TicketChange.CLOSE, TicketState.REJECTED_BY_PROVIDER, TicketState.CLOSED).endTransition()
                .transition(TicketChange.CLOSE, TicketState.INTEGRATION_FAILED, TicketState.CLOSED).endTransition()
                .build();
    }

    /**
     * The rules of the example graph on top of a PUBLISH that already has its action: two recover rules, the
     * failsOn of the invalid response and the fallback.
     */
    public static TransitionBuilder<Ticket, TicketState, TicketChange> exampleRules(
            TransitionBuilder<Ticket, TicketState, TicketChange> withAction) {
        return withAction
                .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                        ServiceUnavailableException.class)
                .recover(PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION,
                        ProviderRejectedException.class)
                .failsOn(InvalidResponseException.class)
                .onFailure(TicketChange.MARK_INTEGRATION_FAILED);
    }

    /** The example graph with a synchronous PUBLISH action named "publish". */
    public static GraphDefinition<Ticket, TicketState, TicketChange> exampleGraph(
            TransitionAction<Ticket, TicketState, TicketChange> action) {
        return graph((TransitionBuilder<Ticket, TicketState, TicketChange> publish) ->
                exampleRules(publish.action("publish", action)));
    }

    /** The example graph with an asynchronous PUBLISH action named "publish". */
    public static GraphDefinition<Ticket, TicketState, TicketChange> exampleAsyncGraph(
            AsyncTransitionAction<Ticket, TicketState, TicketChange> action) {
        return graph((TransitionBuilder<Ticket, TicketState, TicketChange> publish) ->
                exampleRules(publish.asyncAction("publish", action)));
    }

    /**
     * PUBLISH with the rules of the example graph and a synchronous action that logs "publish" and returns a failure
     * of the kind Unavailable, whose rule selects SCHEDULE_RETRY.
     */
    public static UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> unavailable() {
        return (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> exampleRules(
                publish.action("publish", (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> {
                    ctx.entity().calls.add("publish");
                    return ActionOutcome.failed(PublicationFailures.Unavailable.class, "provider busy");
                }));
    }

    /** A standalone machine over {@code graph}; its setter counts every state write in {@link Ticket#stateWrites}. */
    public static StateMachine<Ticket, TicketState, TicketChange> machine(
            GraphDefinition<Ticket, TicketState, TicketChange> graph) {
        return StateMachines.from(graph)
                .state((Ticket ticket) -> ticket.state, (Ticket ticket, TicketState state) -> {
                    ticket.state = state;
                    ticket.stateWrites++;
                })
                .identity((Ticket ticket) -> ticket.id)
                .asyncTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Each hop the outcome committed, as {@code FROM -ID-> TO KIND action at depth N}. */
    public static List<String> hops(Outcome<?, ?, ?> outcome) {
        List<String> hops = new ArrayList<>();
        for (TransitionRecord<?, ?, ?> record : outcome.records()) {
            hops.add(record.from() + " -" + record.transition() + "-> " + record.to() + " " + record.kind() + " "
                    + record.action().orElse("no action") + " at depth " + record.depth());
        }
        return hops;
    }
}
