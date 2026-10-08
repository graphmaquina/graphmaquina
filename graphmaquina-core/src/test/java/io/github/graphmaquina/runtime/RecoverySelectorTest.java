package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRef;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.RecoveryRule;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.error.StateMachineException;
import io.github.graphmaquina.fixtures.RecoveryFixtures.InvalidResponseException;
import io.github.graphmaquina.fixtures.RecoveryFixtures.PublicationFailures;
import io.github.graphmaquina.fixtures.RecoveryFixtures.ServiceUnavailableException;
import io.github.graphmaquina.fixtures.RecoveryFixtures.Ticket;
import io.github.graphmaquina.fixtures.RecoveryFixtures.TicketChange;
import io.github.graphmaquina.fixtures.RecoveryFixtures.TicketState;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/**
 * The selector on edges built by hand, without a machine. A failure the action returned takes the rule of its very
 * failure kind, and any other one the fallback, whatever the order of the rules and whatever types they list. A
 * thrown one is admitted when a declared type accepts it and it is not excluded, and then takes the route of the most
 * specific declared type, wherever and in whatever order it was declared.
 */
class RecoverySelectorTest {

    /** A failure kind no rule of these edges declares. */
    record Postponed() implements FailureKind { }

    private static final ActionRef<Ticket, TicketState, TicketChange> PUBLISH = new ActionRef.Sync<>("publish",
            (TransitionContext<Ticket, TicketState, TicketChange> ctx) -> ActionOutcome.ok());

    private static final RecoveryRule<TicketChange> RETRY_WHEN_UNAVAILABLE =
            new RecoveryRule<>(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                    List.of(ServiceUnavailableException.class));

    private static final RecoveryRule<TicketChange> RECORD_WHEN_REJECTED = new RecoveryRule<>(
            PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION, List.of());

    /** READY -PUBLISH-> PUBLISHED with a synchronous action, {@code onFailure}, {@code failsOn} and {@code rules}. */
    private static TransitionSpec<Ticket, TicketState, TicketChange> publish(
            Optional<TicketChange> onFailure, List<Class<? extends Throwable>> failsOn,
            List<RecoveryRule<TicketChange>> rules) {
        return new TransitionSpec<>(TicketState.READY, TicketChange.PUBLISH, TicketState.PUBLISHED,
                Optional.of(PUBLISH), List.of(), onFailure, failsOn, Optional.empty(), Optional.empty(), rules);
    }

    private static Optional<Class<? extends FailureKind>> kind(Class<? extends FailureKind> kind) {
        return Optional.of(kind);
    }

    private static RecoverySelector.Route<TicketChange> route(TicketChange recovery,
                                                              Class<? extends FailureKind> kind) {
        return new RecoverySelector.Route<>(Optional.of(recovery), Optional.of(kind));
    }

    private static RecoverySelector.Route<TicketChange> fallback(Optional<TicketChange> recovery) {
        return new RecoverySelector.Route<>(recovery, Optional.empty());
    }

    @Test
    void aReturnedFailureTakesTheRuleOfItsFailureKindInEitherOrderOfTheRules() {
        for (List<RecoveryRule<TicketChange>> rules : List.of(List.of(RETRY_WHEN_UNAVAILABLE, RECORD_WHEN_REJECTED),
                List.of(RECORD_WHEN_REJECTED, RETRY_WHEN_UNAVAILABLE))) {
            TransitionSpec<Ticket, TicketState, TicketChange> edge =
                    publish(Optional.of(TicketChange.MARK_INTEGRATION_FAILED), List.of(), rules);
            assertEquals(Optional.of(TicketChange.SCHEDULE_RETRY),
                    RecoverySelector.returned(edge, kind(PublicationFailures.Unavailable.class)));
            assertEquals(Optional.of(TicketChange.RECORD_REJECTION),
                    RecoverySelector.returned(edge, kind(PublicationFailures.Rejected.class)));
        }
    }

    /** A rule never takes a failure returned without a kind, nor one of a kind it does not declare. */
    @Test
    void anyOtherReturnedFailureTakesTheFallback() {
        TransitionSpec<Ticket, TicketState, TicketChange> edge =
                publish(Optional.of(TicketChange.MARK_INTEGRATION_FAILED), List.of(),
                        List.of(RETRY_WHEN_UNAVAILABLE, RECORD_WHEN_REJECTED));
        assertEquals(Optional.of(TicketChange.MARK_INTEGRATION_FAILED),
                RecoverySelector.returned(edge, Optional.empty()));
        assertEquals(Optional.of(TicketChange.MARK_INTEGRATION_FAILED),
                RecoverySelector.returned(edge, kind(Postponed.class)));
    }

    /** Without a fallback, only a rule's own kind selects anything. */
    @Test
    void withoutAFallbackOnlyARuleOfTheKindSelectsARecovery() {
        TransitionSpec<Ticket, TicketState, TicketChange> edge =
                publish(Optional.empty(), List.of(), List.of(RETRY_WHEN_UNAVAILABLE));
        assertEquals(Optional.of(TicketChange.SCHEDULE_RETRY),
                RecoverySelector.returned(edge, kind(PublicationFailures.Unavailable.class)));
        assertEquals(Optional.empty(), RecoverySelector.returned(edge, kind(PublicationFailures.Rejected.class)));
        assertEquals(Optional.empty(), RecoverySelector.returned(edge, Optional.empty()));
    }

    /** An edge without rules behaves as before rules existed: every returned failure takes onFailure, if any. */
    @Test
    void anEdgeWithoutRulesSendsEveryReturnedFailureToItsFallback() {
        TransitionSpec<Ticket, TicketState, TicketChange> routed =
                publish(Optional.of(TicketChange.MARK_INTEGRATION_FAILED), List.of(), List.of());
        assertEquals(Optional.of(TicketChange.MARK_INTEGRATION_FAILED),
                RecoverySelector.returned(routed, kind(PublicationFailures.Unavailable.class)));
        TransitionSpec<Ticket, TicketState, TicketChange> unrouted = publish(Optional.empty(), List.of(), List.of());
        assertEquals(Optional.empty(),
                RecoverySelector.returned(unrouted, kind(PublicationFailures.Unavailable.class)));
    }

    /** A rule's types admit a throwable as failsOn's do, and nothing else does. */
    @Test
    void aThrowableIsAdmittedOnlyByATypeTheEdgeDeclares() {
        TransitionSpec<Ticket, TicketState, TicketChange> edge =
                publish(Optional.of(TicketChange.MARK_INTEGRATION_FAILED), List.of(InvalidResponseException.class),
                        List.of(RETRY_WHEN_UNAVAILABLE));
        assertTrue(RecoverySelector.admits(edge, new ServiceUnavailableException("provider down")));
        assertTrue(RecoverySelector.admits(edge, new InvalidResponseException("garbled body")));
        assertFalse(RecoverySelector.admits(edge, new IllegalStateException("adapter bug")));
    }

    /**
     * Exclusion comes before the declarations: an edge built by hand whose failsOn lists Throwable itself, and whose
     * rule accepts every IllegalStateException, still admits no Error, no CancellationException, which extends
     * IllegalStateException, and no library exception.
     */
    @Test
    void anExcludedThrowableIsNeverAdmittedWhateverTheEdgeDeclares() {
        TransitionSpec<Ticket, TicketState, TicketChange> edge =
                publish(Optional.of(TicketChange.MARK_INTEGRATION_FAILED), List.of(Throwable.class),
                        List.of(new RecoveryRule<>(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                List.of(IllegalStateException.class))));
        assertFalse(RecoverySelector.admits(edge, new AssertionError("invariant broken")));
        assertFalse(RecoverySelector.admits(edge, new CancellationException("cancelled")));
        assertFalse(RecoverySelector.admits(edge, new StateMachineException("the ledger refused the entry")));
        assertTrue(RecoverySelector.admits(edge, new IllegalStateException("not ready")));
        assertTrue(RecoverySelector.admits(edge, new IOException("connection reset")));
    }

    @Test
    void betweenRulesTheSubclassWinsInEitherOrder() {
        RecoveryRule<TicketChange> broad = new RecoveryRule<>(PublicationFailures.Unavailable.class,
                TicketChange.SCHEDULE_RETRY, List.of(IOException.class));
        RecoveryRule<TicketChange> narrow = new RecoveryRule<>(PublicationFailures.Rejected.class,
                TicketChange.RECORD_REJECTION, List.of(SocketTimeoutException.class));
        for (List<RecoveryRule<TicketChange>> rules : List.of(List.of(broad, narrow), List.of(narrow, broad))) {
            TransitionSpec<Ticket, TicketState, TicketChange> edge = publish(Optional.empty(), List.of(), rules);
            assertEquals(route(TicketChange.RECORD_REJECTION, PublicationFailures.Rejected.class),
                    RecoverySelector.thrown(edge, new SocketTimeoutException("read timed out")));
            assertEquals(route(TicketChange.SCHEDULE_RETRY, PublicationFailures.Unavailable.class),
                    RecoverySelector.thrown(edge, new IOException("connection reset")));
        }
    }

    /** Both ways round: the more specific declaration wins wherever it sits. */
    @Test
    void theMostSpecificTypeWinsBetweenARuleAndFailsOn() {
        TransitionSpec<Ticket, TicketState, TicketChange> legacySubtype = publish(
                Optional.of(TicketChange.MARK_INTEGRATION_FAILED), List.of(SocketTimeoutException.class),
                List.of(new RecoveryRule<>(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                        List.of(IOException.class))));
        assertEquals(fallback(Optional.of(TicketChange.MARK_INTEGRATION_FAILED)),
                RecoverySelector.thrown(legacySubtype, new SocketTimeoutException("read timed out")));
        assertEquals(route(TicketChange.SCHEDULE_RETRY, PublicationFailures.Unavailable.class),
                RecoverySelector.thrown(legacySubtype, new IOException("connection reset")));

        TransitionSpec<Ticket, TicketState, TicketChange> ruleSubtype = publish(
                Optional.of(TicketChange.MARK_INTEGRATION_FAILED), List.of(IOException.class),
                List.of(new RecoveryRule<>(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                        List.of(SocketTimeoutException.class))));
        assertEquals(route(TicketChange.SCHEDULE_RETRY, PublicationFailures.Unavailable.class),
                RecoverySelector.thrown(ruleSubtype, new SocketTimeoutException("read timed out")));
        assertEquals(fallback(Optional.of(TicketChange.MARK_INTEGRATION_FAILED)),
                RecoverySelector.thrown(ruleSubtype, new IOException("connection reset")));
    }

    /** A failsOn type that wins routes to onFailure, repeated or not, and to nothing when there is no onFailure. */
    @Test
    void aFailsOnTypeThatWinsTakesTheFallbackWithoutAKind() {
        TransitionSpec<Ticket, TicketState, TicketChange> repeated = publish(
                Optional.of(TicketChange.MARK_INTEGRATION_FAILED),
                List.of(InvalidResponseException.class, InvalidResponseException.class),
                List.of(RETRY_WHEN_UNAVAILABLE));
        assertEquals(fallback(Optional.of(TicketChange.MARK_INTEGRATION_FAILED)),
                RecoverySelector.thrown(repeated, new InvalidResponseException("garbled body")));
        TransitionSpec<Ticket, TicketState, TicketChange> unrouted =
                publish(Optional.empty(), List.of(InvalidResponseException.class), List.of(RETRY_WHEN_UNAVAILABLE));
        assertEquals(fallback(Optional.empty()),
                RecoverySelector.thrown(unrouted, new InvalidResponseException("garbled body")));
        assertEquals(route(TicketChange.SCHEDULE_RETRY, PublicationFailures.Unavailable.class),
                RecoverySelector.thrown(unrouted, new ServiceUnavailableException("provider down")));
    }
}
