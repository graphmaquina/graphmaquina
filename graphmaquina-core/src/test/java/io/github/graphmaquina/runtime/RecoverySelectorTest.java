package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRef;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.RecoveryRule;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.fixtures.RecoveryFixtures.PublicationFailures;
import io.github.graphmaquina.fixtures.RecoveryFixtures.ServiceUnavailableException;
import io.github.graphmaquina.fixtures.RecoveryFixtures.Ticket;
import io.github.graphmaquina.fixtures.RecoveryFixtures.TicketChange;
import io.github.graphmaquina.fixtures.RecoveryFixtures.TicketState;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The selector on edges built by hand, without a machine: a failure the action returned takes the rule of its very
 * failure kind, and any other one the fallback, whatever the order of the rules and whatever types they list.
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
}
