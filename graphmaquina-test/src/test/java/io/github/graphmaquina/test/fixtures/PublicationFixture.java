package io.github.graphmaquina.test.fixtures;

import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;
import java.time.Duration;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The fixture of this module's typed-recovery tests: a publication machine whose PUBLISH action each test injects,
 * synchronous or asynchronous. PUBLISH leaves READY towards PUBLISHED and declares, in this order, a rule for
 * {@link PublicationFailures.Unavailable} through SCHEDULE_RETRY that also takes a
 * {@link ServiceUnavailableException}, a rule for {@link PublicationFailures.Rejected} through RECORD_REJECTION that
 * also takes a {@link ProviderRejectedException}, {@code failsOn} {@link InvalidResponseException}, and the fallback
 * MARK_INTEGRATION_FAILED. The three recovery edges are moves from READY; RETRY leads RETRY_PENDING back to READY,
 * and CLOSE leads REJECTED_BY_PROVIDER and INTEGRATION_FAILED to CLOSED.
 */
public final class PublicationFixture {

    private PublicationFixture() { }

    /** States of the publication machine. */
    public enum TicketState { READY, PUBLISHED, RETRY_PENDING, REJECTED_BY_PROVIDER, INTEGRATION_FAILED, CLOSED }

    /** Catalogue of transition identifiers of the publication machine. */
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

    /** Mutable entity carrying its current {@link TicketState}, which starts at READY. */
    @Getter
    @RequiredArgsConstructor
    public static final class Ticket {

        private final String id;
        private TicketState state = TicketState.READY;

        public void setState(TicketState state) { this.state = state; }
    }

    public static final MachineKey<Ticket, TicketState, TicketChange> KEY =
            MachineKey.of(Ticket.class, TicketState.class, TicketChange.class);

    /** The publication graph with a synchronous PUBLISH action named {@code publish}. */
    public static GraphDefinition<Ticket, TicketState, TicketChange> graph(
            TransitionAction<Ticket, TicketState, TicketChange> action) {
        TransitionBuilder<Ticket, TicketState, TicketChange> publish = states()
                .transition(TicketChange.PUBLISH, TicketState.READY, TicketState.PUBLISHED)
                .action("publish", action);
        return remainingEdges(rules(publish).endTransition());
    }

    /** The publication graph with an asynchronous PUBLISH action named {@code publish}. */
    public static GraphDefinition<Ticket, TicketState, TicketChange> asyncGraph(
            AsyncTransitionAction<Ticket, TicketState, TicketChange> action) {
        TransitionBuilder<Ticket, TicketState, TicketChange> publish = states()
                .transition(TicketChange.PUBLISH, TicketState.READY, TicketState.PUBLISHED)
                .asyncAction("publish", action);
        return remainingEdges(rules(publish).endTransition());
    }

    /** A registry over {@code graph} alone, with a machine-wide asynchronous timeout of five seconds. */
    public static MachineRegistry registry(GraphDefinition<Ticket, TicketState, TicketChange> graph) {
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Ticket::state, Ticket::setState)
                        .identity(Ticket::id).asyncTimeout(Duration.ofSeconds(5)))
                .build();
    }

    private static GraphBuilder<Ticket, TicketState, TicketChange> states() {
        return StateGraph.define(KEY)
                .initial(TicketState.READY).terminal(TicketState.PUBLISHED).terminal(TicketState.CLOSED)
                .allowsCycle(TicketState.READY)
                .statePolicy(TicketState.READY, "publish the ticket")
                .statePolicy(TicketState.RETRY_PENDING, "retry the publication explicitly")
                .statePolicy(TicketState.REJECTED_BY_PROVIDER, "close the rejected ticket")
                .statePolicy(TicketState.INTEGRATION_FAILED, "close after manual review");
    }

    private static TransitionBuilder<Ticket, TicketState, TicketChange> rules(
            TransitionBuilder<Ticket, TicketState, TicketChange> publish) {
        return publish
                .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                        ServiceUnavailableException.class)
                .recover(PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION,
                        ProviderRejectedException.class)
                .failsOn(InvalidResponseException.class)
                .onFailure(TicketChange.MARK_INTEGRATION_FAILED);
    }

    private static GraphDefinition<Ticket, TicketState, TicketChange> remainingEdges(
            GraphBuilder<Ticket, TicketState, TicketChange> builder) {
        return builder
                .transition(TicketChange.SCHEDULE_RETRY, TicketState.READY, TicketState.RETRY_PENDING)
                        .endTransition()
                .transition(TicketChange.RECORD_REJECTION, TicketState.READY, TicketState.REJECTED_BY_PROVIDER)
                        .endTransition()
                .transition(TicketChange.MARK_INTEGRATION_FAILED, TicketState.READY, TicketState.INTEGRATION_FAILED)
                        .endTransition()
                .transition(TicketChange.RETRY, TicketState.RETRY_PENDING, TicketState.READY).endTransition()
                .transition(TicketChange.CLOSE, TicketState.REJECTED_BY_PROVIDER, TicketState.CLOSED).endTransition()
                .transition(TicketChange.CLOSE, TicketState.INTEGRATION_FAILED, TicketState.CLOSED).endTransition()
                .build();
    }
}
