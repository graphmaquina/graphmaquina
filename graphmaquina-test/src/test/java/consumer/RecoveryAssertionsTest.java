package consumer;

import static consumer.GraphAssertionErrors.assertFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.RecoveryRule;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.test.ActionOutcomeAssert;
import io.github.graphmaquina.test.FailedOutcomeAssert;
import io.github.graphmaquina.test.GraphAssert;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.GraphAssertions;
import io.github.graphmaquina.test.OutcomeAssert;
import io.github.graphmaquina.test.TransitionAssert;
import io.github.graphmaquina.test.fixtures.PublicationFixture;
import io.github.graphmaquina.test.fixtures.PublicationFixture.InvalidResponseException;
import io.github.graphmaquina.test.fixtures.PublicationFixture.ProviderRejectedException;
import io.github.graphmaquina.test.fixtures.PublicationFixture.PublicationFailures;
import io.github.graphmaquina.test.fixtures.PublicationFixture.ServiceUnavailableException;
import io.github.graphmaquina.test.fixtures.PublicationFixture.Ticket;
import io.github.graphmaquina.test.fixtures.PublicationFixture.TicketChange;
import io.github.graphmaquina.test.fixtures.PublicationFixture.TicketState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Contract of the typed-recovery expectations: {@code hasFailureKind} and {@code hasNoFailureKind} over an action
 * result and over a failed outcome, and {@code recoversExactly} over an edge. Every expectation gets a match and a
 * mismatch, read off the fields of the {@link GraphAssertionError}, whose property is {@code failure kind} or
 * {@code recovery rules}; an action result of the wrong variant fails on {@code kind} instead, as every expectation
 * about a failure does. A failure kind is compared by identity, so the marker interface matches no kind, and the
 * rules of an edge are compared exactly and in declaration order.
 */
class RecoveryAssertionsTest {

    private static final String GRAPH = Ticket.class.getName() + "/" + TicketState.class.getName();
    private static final String PUBLISH_EDGE = GRAPH + " READY -PUBLISH->";

    private static final RecoveryRule<TicketChange> UNAVAILABLE = new RecoveryRule<>(
            PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
            List.of(ServiceUnavailableException.class), false);
    private static final RecoveryRule<TicketChange> REJECTED = new RecoveryRule<>(
            PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION,
            List.of(ProviderRejectedException.class), false);

    // --- an action result ------------------------------------------------------------------------------

    @Test
    void aFailedActionSatisfiesTheFailureKindItCarries() {
        ActionOutcomeAssert view = ActionOutcomeAssert.assertThat(
                ActionOutcome.failed(PublicationFailures.Rejected.class, "content rejected"));
        assertSame(view, view.isFailed()
                .hasFailureKind(PublicationFailures.Rejected.class)
                .hasReason("content rejected"));
    }

    @Test
    void anotherFailureKindIsReportedWithBothClasses() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> ActionOutcomeAssert.assertThat(
                                ActionOutcome.failed(PublicationFailures.Rejected.class, "content rejected"))
                        .hasFailureKind(PublicationFailures.Unavailable.class));
        assertFailure(error, "action outcome", "failure kind", PublicationFailures.Unavailable.class,
                PublicationFailures.Rejected.class);
        assertEquals("action outcome: failure kind expected <" + PublicationFailures.Unavailable.class
                + "> but was <" + PublicationFailures.Rejected.class + ">", error.getMessage());
    }

    @Test
    void theMarkerInterfaceMatchesNoFailureKind() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> ActionOutcomeAssert.assertThat(
                                ActionOutcome.failed(PublicationFailures.Rejected.class, "content rejected"))
                        .hasFailureKind(FailureKind.class));
        assertFailure(error, "action outcome", "failure kind", FailureKind.class,
                PublicationFailures.Rejected.class);
    }

    @Test
    void aFailureWithoutAKindSatisfiesOnlyHasNoFailureKind() {
        ActionOutcomeAssert view = ActionOutcomeAssert.assertThat(ActionOutcome.failed("declined"));
        assertSame(view, view.hasNoFailureKind());
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> view.hasFailureKind(PublicationFailures.Rejected.class));
        assertFailure(error, "action outcome", "failure kind", PublicationFailures.Rejected.class, null);
    }

    @Test
    void aFailureWithAKindDoesNotSatisfyHasNoFailureKind() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> ActionOutcomeAssert.assertThat(
                                ActionOutcome.failed(PublicationFailures.Rejected.class, "content rejected"))
                        .hasNoFailureKind());
        assertFailure(error, "action outcome", "failure kind", Optional.empty(),
                Optional.of(PublicationFailures.Rejected.class));
        assertEquals("action outcome: failure kind expected <Optional.empty> but was <Optional["
                + PublicationFailures.Rejected.class + "]>", error.getMessage());
    }

    @Test
    void anOkActionFailsBothExpectationsOnItsVariant() {
        ActionOutcomeAssert view = ActionOutcomeAssert.assertThat(ActionOutcome.ok());
        assertFailure(assertThrows(GraphAssertionError.class, view::hasNoFailureKind),
                "action outcome", "kind", "Failed", "Ok");
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> view.hasFailureKind(PublicationFailures.Rejected.class)),
                "action outcome", "kind", "Failed", "Ok");
    }

    @Test
    void aNullActionOutcomeFailsBothExpectationsWithContext() {
        ActionOutcomeAssert view = ActionOutcomeAssert.assertThat(null);
        assertFailure(assertThrows(GraphAssertionError.class, view::hasNoFailureKind),
                "action outcome", "kind", "Failed", null);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> view.hasFailureKind(PublicationFailures.Rejected.class)),
                "action outcome", "kind", "Failed", null);
    }

    @Test
    void aNullExpectedKindStaysAnArgumentErrorEvenOverANullSubject() {
        assertEquals("expected is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> ActionOutcomeAssert.assertThat(ActionOutcome.failed("declined")).hasFailureKind(null))
                .getMessage());
        assertEquals("expected is marked non-null but is null", assertThrows(NullPointerException.class,
                () -> ActionOutcomeAssert.assertThat(null).hasFailureKind(null)).getMessage());
    }

    // --- a failed outcome ------------------------------------------------------------------------------

    @Test
    void aFailedOutcomeSatisfiesTheFailureKindItCarries() {
        FailedOutcomeAssert<Ticket, TicketState, TicketChange> view =
                OutcomeAssert.assertThat(rejected(Optional.of(PublicationFailures.Rejected.class))).isFailed();
        assertSame(view, view.hasFailureKind(PublicationFailures.Rejected.class)
                .hasFailureTransition(TicketChange.RECORD_REJECTION));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> view.hasFailureKind(PublicationFailures.Unavailable.class)),
                "failed outcome", "failure kind", PublicationFailures.Unavailable.class,
                PublicationFailures.Rejected.class);
        assertFailure(assertThrows(GraphAssertionError.class, view::hasNoFailureKind),
                "failed outcome", "failure kind", Optional.empty(), Optional.of(PublicationFailures.Rejected.class));
    }

    @Test
    void aFailedOutcomeWithoutAKindSatisfiesOnlyHasNoFailureKind() {
        FailedOutcomeAssert<Ticket, TicketState, TicketChange> view =
                OutcomeAssert.assertThat(rejected(Optional.empty())).isFailed();
        assertSame(view, view.hasNoFailureKind());
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> view.hasFailureKind(PublicationFailures.Rejected.class));
        assertFailure(error, "failed outcome", "failure kind", PublicationFailures.Rejected.class, null);
        assertEquals("failed outcome: failure kind expected <" + PublicationFailures.Rejected.class
                + "> but was <null>", error.getMessage());
    }

    @Test
    void aNullExpectedKindStaysAnArgumentErrorOnAFailedOutcome() {
        FailedOutcomeAssert<Ticket, TicketState, TicketChange> view =
                OutcomeAssert.assertThat(rejected(Optional.empty())).isFailed();
        assertEquals("expected is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> view.hasFailureKind(null))
                .getMessage());
    }

    // --- what the engine returned ----------------------------------------------------------------------

    @Test
    void aReturnedRejectionIsReadThroughItsFailureKind() {
        Ticket ticket = new Ticket("t");
        try (MachineRegistry registry = PublicationFixture.registry(PublicationFixture.graph(
                (TransitionContext<Ticket, TicketState, TicketChange> context) ->
                        ActionOutcome.failed(PublicationFailures.Rejected.class, "content rejected")))) {
            StateMachine<Ticket, TicketState, TicketChange> machine = registry.of(PublicationFixture.KEY);
            GraphAssertions.assertThat(machine.execute(ticket, TicketChange.PUBLISH))
                    .isFailed()
                    .hasFailureKind(PublicationFailures.Rejected.class)
                    .hasFailureTransition(TicketChange.RECORD_REJECTION)
                    .movedTo(TicketState.REJECTED_BY_PROVIDER)
                    .hasNoCause();
        }
        assertEquals(TicketState.REJECTED_BY_PROVIDER, ticket.state());
    }

    @Test
    void anExceptionTheFallbackTakesCarriesNoFailureKind() {
        Ticket ticket = new Ticket("t");
        InvalidResponseException unreadable = new InvalidResponseException("unreadable response");
        try (MachineRegistry registry = PublicationFixture.registry(PublicationFixture.graph(
                (TransitionContext<Ticket, TicketState, TicketChange> context) -> {
                    throw unreadable;
                }))) {
            StateMachine<Ticket, TicketState, TicketChange> machine = registry.of(PublicationFixture.KEY);
            GraphAssertions.assertThat(machine.execute(ticket, TicketChange.PUBLISH))
                    .isFailed()
                    .hasNoFailureKind()
                    .hasFailureTransition(TicketChange.MARK_INTEGRATION_FAILED)
                    .movedTo(TicketState.INTEGRATION_FAILED)
                    .hasCauseSameAs(unreadable);
        }
        assertEquals(TicketState.INTEGRATION_FAILED, ticket.state());
    }

    // --- the rules an edge declares --------------------------------------------------------------------

    @Test
    void theRulesMatchInTheOrderTheyWereDeclared() {
        TransitionAssert<Ticket, TicketState, TicketChange> edge =
                GraphAssert.assertThat(publicationGraph()).transition(TicketState.READY, TicketChange.PUBLISH);
        assertSame(edge, edge.recoversExactly(List.of(UNAVAILABLE, REJECTED))
                .failsOnExactly(InvalidResponseException.class)
                .hasFailureTransition(TicketChange.MARK_INTEGRATION_FAILED));
    }

    @Test
    void theSameRulesInAnotherOrderDoNotMatch() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(publicationGraph()).transition(TicketState.READY, TicketChange.PUBLISH)
                        .recoversExactly(List.of(REJECTED, UNAVAILABLE)));
        assertFailure(error, PUBLISH_EDGE, "recovery rules", List.of(REJECTED, UNAVAILABLE),
                List.of(UNAVAILABLE, REJECTED));
    }

    @Test
    void aMissingRuleDoesNotMatch() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(publicationGraph()).transition(TicketState.READY, TicketChange.PUBLISH)
                        .recoversExactly(List.of(UNAVAILABLE)));
        assertFailure(error, PUBLISH_EDGE, "recovery rules", List.of(UNAVAILABLE), List.of(UNAVAILABLE, REJECTED));
    }

    @Test
    void aRuleMatchesOnlyWithTheExceptionClassesItLists() {
        RecoveryRule<TicketChange> withoutClasses = new RecoveryRule<>(PublicationFailures.Unavailable.class,
                TicketChange.SCHEDULE_RETRY, List.of(), false);
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> GraphAssert.assertThat(publicationGraph()).transition(TicketState.READY, TicketChange.PUBLISH)
                        .recoversExactly(List.of(withoutClasses, REJECTED)));
        assertFailure(error, PUBLISH_EDGE, "recovery rules", List.of(withoutClasses, REJECTED),
                List.of(UNAVAILABLE, REJECTED));
    }

    @Test
    void anEdgeWithoutRulesMatchesOnlyAnEmptyList() {
        TransitionAssert<Ticket, TicketState, TicketChange> edge = GraphAssert.assertThat(publicationGraph())
                .transition(TicketState.READY, TicketChange.SCHEDULE_RETRY);
        assertSame(edge, edge.recoversExactly(List.of()));
        assertFailure(assertThrows(GraphAssertionError.class, () -> edge.recoversExactly(List.of(UNAVAILABLE))),
                GRAPH + " READY -SCHEDULE_RETRY->", "recovery rules", List.of(UNAVAILABLE), List.of());
    }

    @Test
    void aNullDefinitionFailsRecoversExactlyWithContext() {
        TransitionAssert<Ticket, TicketState, TicketChange> edge =
                GraphAssert.<Ticket, TicketState, TicketChange>assertThat(null)
                        .transition(TicketState.READY, TicketChange.PUBLISH);
        assertFailure(assertThrows(GraphAssertionError.class, () -> edge.recoversExactly(List.of(UNAVAILABLE))),
                "graph READY -PUBLISH->", "recovery rules", List.of(UNAVAILABLE), null);
    }

    @Test
    void aNullListOrANullRuleIsAnArgumentError() {
        TransitionAssert<Ticket, TicketState, TicketChange> edge =
                GraphAssert.assertThat(publicationGraph()).transition(TicketState.READY, TicketChange.PUBLISH);
        assertEquals("expected is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> edge.recoversExactly(null))
                .getMessage());
        List<RecoveryRule<TicketChange>> withNull = new ArrayList<>();
        withNull.add(UNAVAILABLE);
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> edge.recoversExactly(withNull));
    }

    // --- helpers ---------------------------------------------------------------------------------------

    /** The publication graph, with a PUBLISH action that fails the test if an inspection ever runs it. */
    private static GraphDefinition<Ticket, TicketState, TicketChange> publicationGraph() {
        return PublicationFixture.graph((TransitionContext<Ticket, TicketState, TicketChange> context) -> {
            throw new AssertionError("an inspection ran the action");
        });
    }

    /** A root outcome of PUBLISH that recovered through RECORD_REJECTION, carrying {@code kind}. */
    private static Outcome.Failed<Ticket, TicketState, TicketChange> rejected(
            Optional<Class<? extends FailureKind>> kind) {
        return new Outcome.Failed<>(new Ticket("t"), TicketState.READY, TicketChange.PUBLISH, TicketState.PUBLISHED,
                TicketChange.RECORD_REJECTION, TicketState.REJECTED_BY_PROVIDER, "content rejected", Optional.empty(),
                List.of(), List.of(), kind);
    }
}
