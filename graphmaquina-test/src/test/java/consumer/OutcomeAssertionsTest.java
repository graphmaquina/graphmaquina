package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.IgnoreReason;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.OutcomeStatus;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.runtime.StateMachines;
import io.github.graphmaquina.test.AppliedOutcomeAssert;
import io.github.graphmaquina.test.EntityStateAssert;
import io.github.graphmaquina.test.FailedOutcomeAssert;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.IgnoredOutcomeAssert;
import io.github.graphmaquina.test.OutcomeAssert;
import io.github.graphmaquina.test.ReactionsAssert;
import io.github.graphmaquina.test.RejectedOutcomeAssert;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Contract of the outcome views. Every expectation gets a match and a mismatch, the mismatch is read
 * off the fields of the {@link GraphAssertionError} rather than off its type alone, and every method
 * whose subject can be null gets its own null-subject case so that removing a single guard fails
 * exactly one test.
 */
class OutcomeAssertionsTest {

    // --- the premise of this task: the recorded result and the live entity are separate readings ----

    @Test
    void recordedDestinationAndCurrentEntityStateAreIndependent() {
        Entity entity = new Entity("e", State.READY);
        Outcome<Entity, State, Id> result = new Outcome.Applied<>(entity, State.NEW, Id.PREPARE,
                State.READY, List.of(), List.of());
        entity.setState(State.DONE);
        OutcomeAssert.assertThat(result).isApplied().movedTo(State.READY)
                .hasEntityState(Entity::state, State.DONE);
        assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(result).hasEntityState(Entity::state, State.READY));
    }

    @Test
    void aGuardRejectionIsNotTheAbsenceOfAnEdge() {
        Entity entity = new Entity("e", State.NEW);
        Outcome<Entity, State, Id> rejected = new Outcome.Rejected<>(entity, State.NEW, Id.PREPARE,
                "not ready", RejectionKind.GUARD, Optional.of("first"));
        OutcomeAssert.assertThat(rejected).isRejected().isRejectedByGuardNamed("first");
        assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(rejected).isRejected().hasNoTransition());
    }

    // --- variant narrowing -------------------------------------------------------------------------

    @Test
    void everyVariantNarrowsToItsOwnView() {
        Entity entity = new Entity("e", State.NEW);
        assertSame(State.READY, applied(entity).to());
        OutcomeAssert.assertThat(applied(entity)).isApplied();
        OutcomeAssert.assertThat(failed(entity, Optional.empty())).isFailed();
        OutcomeAssert.assertThat(rejected(entity, Optional.empty())).isRejected();
        OutcomeAssert.assertThat(ignored(entity)).isIgnored();
    }

    @Test
    void appliedSatisfiesNoOtherVariant() {
        Entity entity = new Entity("e", State.NEW);
        Outcome<Entity, State, Id> outcome = applied(entity);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isFailed()), "outcome", "status", OutcomeStatus.FAILED, OutcomeStatus.APPLIED);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isRejected()), "outcome", "status", OutcomeStatus.REJECTED, OutcomeStatus.APPLIED);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isIgnored()), "outcome", "status", OutcomeStatus.IGNORED, OutcomeStatus.APPLIED);
    }

    @Test
    void failedSatisfiesNoOtherVariant() {
        Entity entity = new Entity("e", State.NEW);
        Outcome<Entity, State, Id> outcome = failed(entity, Optional.empty());
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isApplied()), "outcome", "status", OutcomeStatus.APPLIED, OutcomeStatus.FAILED);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isRejected()), "outcome", "status", OutcomeStatus.REJECTED, OutcomeStatus.FAILED);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isIgnored()), "outcome", "status", OutcomeStatus.IGNORED, OutcomeStatus.FAILED);
    }

    @Test
    void rejectedSatisfiesNoOtherVariant() {
        Entity entity = new Entity("e", State.NEW);
        Outcome<Entity, State, Id> outcome = rejected(entity, Optional.empty());
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isApplied()), "outcome", "status", OutcomeStatus.APPLIED, OutcomeStatus.REJECTED);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isFailed()), "outcome", "status", OutcomeStatus.FAILED, OutcomeStatus.REJECTED);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isIgnored()), "outcome", "status", OutcomeStatus.IGNORED, OutcomeStatus.REJECTED);
    }

    @Test
    void ignoredSatisfiesNoOtherVariant() {
        Entity entity = new Entity("e", State.NEW);
        Outcome<Entity, State, Id> outcome = ignored(entity);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isApplied()), "outcome", "status", OutcomeStatus.APPLIED, OutcomeStatus.IGNORED);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isFailed()), "outcome", "status", OutcomeStatus.FAILED, OutcomeStatus.IGNORED);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isRejected()), "outcome", "status", OutcomeStatus.REJECTED, OutcomeStatus.IGNORED);
    }

    // --- shared expectations -----------------------------------------------------------------------

    @Test
    void originIsReadFromEveryVariant() {
        Entity entity = new Entity("e", State.NEW);
        OutcomeAssert.assertThat(applied(entity)).from(State.NEW);
        OutcomeAssert.assertThat(failed(entity, Optional.empty())).from(State.NEW);
        OutcomeAssert.assertThat(rejected(entity, Optional.empty())).from(State.NEW);
        OutcomeAssert.assertThat(ignored(entity)).from(State.NEW);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(applied(entity)).from(State.READY)),
                "outcome", "from", State.READY, State.NEW);
    }

    /**
     * Three variants report the identifier the caller asked for; a reevaluation that selected no edge
     * reports none, which is a different answer from any identifier at all.
     */
    @Test
    void theRequestedIdentifierIsReadFromEveryVariantThatHadOne() {
        Entity entity = new Entity("e", State.NEW);
        OutcomeAssert.assertThat(applied(entity)).requestedTransition(Id.PREPARE);
        OutcomeAssert.assertThat(failed(entity, Optional.empty())).requestedTransition(Id.PREPARE);
        OutcomeAssert.assertThat(rejected(entity, Optional.empty())).requestedTransition(Id.PREPARE);
        OutcomeAssert.assertThat(ignored(entity)).hasNoRequestedTransition();
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(applied(entity)).requestedTransition(Id.FINISH)),
                "outcome", "requested transition", Optional.of(Id.FINISH), Optional.of(Id.PREPARE));
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(ignored(entity)).requestedTransition(Id.PREPARE)),
                "outcome", "requested transition", Optional.of(Id.PREPARE), Optional.empty());
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(applied(entity)).hasNoRequestedTransition()),
                "outcome", "requested transition", Optional.empty(), Optional.of(Id.PREPARE));
    }

    @Test
    void recordedStateIsWhereTheOutcomeSaysTheEntityEndedUp() {
        Entity entity = new Entity("e", State.NEW);
        OutcomeAssert.assertThat(applied(entity)).hasRecordedState(State.READY);
        OutcomeAssert.assertThat(failed(entity, Optional.empty())).hasRecordedState(State.FAILED);
        OutcomeAssert.assertThat(rejected(entity, Optional.empty())).hasRecordedState(State.NEW);
        OutcomeAssert.assertThat(ignored(entity)).hasRecordedState(State.NEW);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(ignored(entity)).hasRecordedState(State.READY)),
                "outcome", "recorded state", State.READY, State.NEW);
    }

    @Test
    void ignoredKeepsTheOriginAndSaysWhyNothingWasSelected() {
        Entity entity = new Entity("e", State.NEW);
        IgnoredOutcomeAssert<Entity, State, Id> view = OutcomeAssert.assertThat(ignored(entity)).isIgnored();
        assertSame(view, view.hasReason(IgnoreReason.NO_RULE_MATCHED)
                .hasNoRequestedTransition().hasRecordedState(State.NEW).from(State.NEW));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasRecordedState(State.READY)),
                "ignored outcome", "recorded state", State.READY, State.NEW);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasReason(IgnoreReason.NO_WATCH)),
                "ignored outcome", "reason", IgnoreReason.NO_WATCH, IgnoreReason.NO_RULE_MATCHED);
    }

    @Test
    void entityStateIsReadOncePerAssertionAndNotAtViewCreation() {
        Entity entity = new Entity("e", State.NEW);
        OutcomeAssert<Entity, State, Id> view = OutcomeAssert.assertThat(applied(entity));
        view.hasEntityState(Entity::state, State.NEW);
        entity.setState(State.FAILED);
        view.hasEntityState(Entity::state, State.FAILED);
    }

    @Test
    void entityStateReportsWhatTheGetterReturned() {
        Entity entity = new Entity("e", State.DONE);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(applied(entity)).hasEntityState(Entity::state, State.READY)),
                "outcome", "entity state", State.READY, State.DONE);
    }

    @Test
    void aGetterThatReturnsNullIsAnAssertionFailureNotACrash() {
        Entity entity = new Entity("e", State.NEW);
        Function<Entity, State> none = (Entity ignoredEntity) -> null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(applied(entity)).hasEntityState(none, State.NEW)),
                "outcome", "entity state", State.NEW, null);
    }

    @Test
    void aNullGetterIsAnArgumentError() {
        Entity entity = new Entity("e", State.NEW);
        assertThrows(NullPointerException.class,
                () -> OutcomeAssert.assertThat(applied(entity)).hasEntityState(null, State.NEW));
    }

    @Test
    void aNullExpectedIsAnArgumentErrorEvenWhenTheSubjectIsNull() {
        Outcome<Entity, State, Id> none = null;
        assertThrows(NullPointerException.class, () -> OutcomeAssert.assertThat(none).from(null));
        assertThrows(NullPointerException.class,
                () -> OutcomeAssert.assertThat(none).requestedTransition(null));
        assertThrows(NullPointerException.class, () -> OutcomeAssert.assertThat(none).hasRecordedState(null));
        assertThrows(NullPointerException.class, () -> OutcomeAssert.assertThat(none).hasEntitySameAs(null));
        assertThrows(NullPointerException.class, () -> OutcomeAssert.assertThat(none).hasStatus(null));
        assertThrows(NullPointerException.class, () -> OutcomeAssert.assertThat(none).hasCode(null));
        assertThrows(NullPointerException.class, () -> OutcomeAssert.assertThat(none).hasExplanation(null));
    }

    @Test
    void entitySelectionIsByReferenceNotByEquality() {
        Twin entity = new Twin("e");
        Twin sameValue = new Twin("e");
        assertEquals(entity, sameValue);
        assertNotSame(entity, sameValue);
        Outcome<Twin, State, Id> outcome =
                new Outcome.Applied<>(entity, State.NEW, Id.PREPARE, State.READY, List.of(), List.of());
        OutcomeAssert.assertThat(outcome).hasEntitySameAs(entity);
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).hasEntitySameAs(sameValue));
        assertEquals("outcome", error.context());
        assertEquals("entity", error.property());
        assertSame(sameValue, error.expected());
        assertSame(entity, error.actual());
    }

    // --- classification and explanation ------------------------------------------------------------

    @Test
    void statusAndCodeAreReadFromEveryVariant() {
        Entity entity = new Entity("e", State.NEW);
        OutcomeAssert.assertThat(applied(entity)).hasStatus(OutcomeStatus.APPLIED).hasCode(OutcomeCode.APPLIED);
        OutcomeAssert.assertThat(failed(entity, Optional.empty())).hasStatus(OutcomeStatus.FAILED)
                .hasCode(OutcomeCode.FAILURE_ROUTED);
        OutcomeAssert.assertThat(rejected(entity, Optional.empty())).hasStatus(OutcomeStatus.REJECTED)
                .hasCode(OutcomeCode.NO_TRANSITION);
        OutcomeAssert.assertThat(rejected(entity, Optional.of("first"))).hasCode(OutcomeCode.GUARD_REJECTED);
        OutcomeAssert.assertThat(ignored(entity)).hasStatus(OutcomeStatus.IGNORED)
                .hasCode(OutcomeCode.NO_RULE_MATCHED);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(applied(entity)).hasStatus(OutcomeStatus.REJECTED)),
                "outcome", "status", OutcomeStatus.REJECTED, OutcomeStatus.APPLIED);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(rejected(entity, Optional.empty())).hasCode(OutcomeCode.GUARD_REJECTED)),
                "outcome", "code", OutcomeCode.GUARD_REJECTED, OutcomeCode.NO_TRANSITION);
    }

    @Test
    void aNarrowedViewKeepsTheSharedClassification() {
        Entity entity = new Entity("e", State.NEW);
        RejectedOutcomeAssert<Entity, State, Id> view =
                OutcomeAssert.assertThat(rejected(entity, Optional.of("first"))).isRejected();
        assertSame(view, view.hasCode(OutcomeCode.GUARD_REJECTED).hasStatus(OutcomeStatus.REJECTED)
                .hasExplanation("not ready"));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasCode(OutcomeCode.NO_TRANSITION)),
                "rejected outcome", "code", OutcomeCode.NO_TRANSITION, OutcomeCode.GUARD_REJECTED);
    }

    @Test
    void theExplanationIsComparedByValue() {
        Entity entity = new Entity("e", State.NEW);
        OutcomeAssert.assertThat(rejected(entity, Optional.empty())).hasExplanation("not ready");
        OutcomeAssert.assertThat(failed(entity, Optional.empty())).hasExplanation("boom");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(rejected(entity, Optional.empty())).hasExplanation("later")),
                "outcome", "explanation", Optional.of("later"), Optional.of("not ready"));
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(applied(entity)).hasExplanation("done")),
                "outcome", "explanation", Optional.of("done"), Optional.empty());
    }

    @Test
    void anOutcomeWithoutAuthorTextHasNoExplanation() {
        Entity entity = new Entity("e", State.NEW);
        OutcomeAssert.assertThat(applied(entity)).hasNoExplanation();
        OutcomeAssert.assertThat(ignored(entity)).hasNoExplanation();
        OutcomeAssert.assertThat(failed(entity, Optional.of(new IOException("down")))).hasNoExplanation();
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(failed(entity, Optional.empty())).hasNoExplanation()),
                "outcome", "explanation", Optional.empty(), Optional.of("boom"));
    }

    /** Any outcome opens its records and reactions; one that committed nothing opens empty lists. */
    @Test
    void recordsAndReactionsOpenFromAnyOutcome() {
        Entity entity = new Entity("e", State.NEW);
        OutcomeAssert.assertThat(rejected(entity, Optional.empty())).records().isEmpty();
        OutcomeAssert.assertThat(ignored(entity)).reactions().isEmpty();
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(rejected(entity, Optional.empty())).records().hasSize(1)),
                "outcome.records", "size", 1, 0);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(ignored(entity)).reactions().hasSize(1)),
                "outcome.reactions", "size", 1, 0);
    }

    /** An outcome read through a reaction keeps the reaction's place in the failures of the shared expectations. */
    @Test
    void anOutcomeReadThroughAReactionKeepsItsPlaceInTheNewExpectations() {
        Entity entity = new Entity("e", State.NEW);
        Reaction done = new Reaction.Done(TestDomain.KEY, entity, rejected(entity, Optional.of("first")));
        OutcomeAssert<Entity, State, Id> view = ReactionsAssert.assertThat(List.of(done))
                .forEntity(TestDomain.KEY, entity).single().isDone().outcome();
        view.hasStatus(OutcomeStatus.REJECTED).hasCode(OutcomeCode.GUARD_REJECTED).hasExplanation("not ready");
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasCode(OutcomeCode.NO_TRANSITION)),
                "reactions for " + TestDomain.KEY + "[0].outcome", "code", OutcomeCode.NO_TRANSITION,
                OutcomeCode.GUARD_REJECTED);
    }

    // --- applied -----------------------------------------------------------------------------------

    @Test
    void appliedMovedToReadsTheCommittedDestination() {
        Entity entity = new Entity("e", State.NEW);
        AppliedOutcomeAssert<Entity, State, Id> view = OutcomeAssert.assertThat(applied(entity)).isApplied();
        assertSame(view, view.movedTo(State.READY));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.movedTo(State.DONE)),
                "applied outcome", "moved to", State.DONE, State.READY);
    }

    /** A list opened from a narrowed outcome names the outcome it came from, not just what it lists. */
    @Test
    void aListOpenedFromANarrowedOutcomeNamesTheOutcomeItCameFrom() {
        Entity entity = new Entity("e", State.NEW);
        AppliedOutcomeAssert<Entity, State, Id> applied = OutcomeAssert.assertThat(applied(entity)).isApplied();
        assertFailure(assertThrows(GraphAssertionError.class, () -> applied.records().hasSize(1)),
                "applied outcome.records", "size", 1, 0);
        assertFailure(assertThrows(GraphAssertionError.class, () -> applied.reactions().hasSize(1)),
                "applied outcome.reactions", "size", 1, 0);
        FailedOutcomeAssert<Entity, State, Id> failed =
                OutcomeAssert.assertThat(failed(entity, Optional.empty())).isFailed();
        assertFailure(assertThrows(GraphAssertionError.class, () -> failed.records().hasSize(1)),
                "failed outcome.records", "size", 1, 0);
        assertFailure(assertThrows(GraphAssertionError.class, () -> failed.reactions().hasSize(1)),
                "failed outcome.reactions", "size", 1, 0);
    }

    // --- failed ------------------------------------------------------------------------------------

    @Test
    void failedMovedToIsTheRecoveryRouteNotTheDestinationThatWasAskedFor() {
        Entity entity = new Entity("e", State.NEW);
        FailedOutcomeAssert<Entity, State, Id> view =
                OutcomeAssert.assertThat(failed(entity, Optional.empty())).isFailed();
        assertNotEquals(State.READY, State.FAILED);
        assertSame(view, view.requestedTransition(Id.PREPARE).hasTarget(State.READY)
                .hasFailureTransition(Id.RECOVER).movedTo(State.FAILED));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.movedTo(State.READY)),
                "failed outcome", "moved to", State.READY, State.FAILED);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasTarget(State.FAILED)),
                "failed outcome", "target", State.FAILED, State.READY);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasFailureTransition(Id.FINISH)),
                "failed outcome", "failure transition", Id.FINISH, Id.RECOVER);
    }

    @Test
    void failedReasonIsComparedByValue() {
        Entity entity = new Entity("e", State.NEW);
        Outcome<Entity, State, Id> outcome = new Outcome.Failed<>(entity, State.NEW, Id.PREPARE,
                State.READY, Id.RECOVER, State.FAILED, new StringBuilder("boom").toString(),
                Optional.empty(), List.of(), List.of());
        OutcomeAssert.assertThat(outcome).isFailed().hasReason("boom");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isFailed().hasReason("other")),
                "failed outcome", "reason", "other", "boom");
    }

    @Test
    void causeIsMatchedByTypeIncludingSubclasses() {
        Entity entity = new Entity("e", State.NEW);
        UncheckedIOException cause = new UncheckedIOException(new IOException("io"));
        FailedOutcomeAssert<Entity, State, Id> view =
                OutcomeAssert.assertThat(failed(entity, Optional.of(cause))).isFailed();
        assertSame(view, view.hasCause(UncheckedIOException.class));
        assertSame(view, view.hasCause(RuntimeException.class));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasCause(IllegalStateException.class)),
                "failed outcome", "cause", IllegalStateException.class, cause);
    }

    @Test
    void causeIsMatchedByReference() {
        Entity entity = new Entity("e", State.NEW);
        IllegalStateException cause = new IllegalStateException("boom");
        IllegalStateException other = new IllegalStateException("boom");
        FailedOutcomeAssert<Entity, State, Id> view =
                OutcomeAssert.assertThat(failed(entity, Optional.of(cause))).isFailed();
        assertSame(view, view.hasCauseSameAs(cause));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasCauseSameAs(other)),
                "failed outcome", "cause", other, cause);
    }

    @Test
    void causeByReferenceRejectsADistinctButEqualThrowable() {
        Entity entity = new Entity("e", State.NEW);
        ValueError cause = new ValueError();
        ValueError sameValue = new ValueError();
        assertEquals(cause, sameValue);
        assertNotSame(cause, sameValue);
        FailedOutcomeAssert<Entity, State, Id> view =
                OutcomeAssert.assertThat(failed(entity, Optional.of(cause))).isFailed();
        assertSame(view, view.hasCauseSameAs(cause));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasCauseSameAs(sameValue)),
                "failed outcome", "cause", sameValue, cause);
    }

    @Test
    void anAbsentCauseSatisfiesOnlyHasNoCause() {
        Entity entity = new Entity("e", State.NEW);
        IllegalStateException absent = new IllegalStateException("never happened");
        FailedOutcomeAssert<Entity, State, Id> view =
                OutcomeAssert.assertThat(failed(entity, Optional.empty())).isFailed();
        assertSame(view, view.hasNoCause());
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasCause(IllegalStateException.class)),
                "failed outcome", "cause", IllegalStateException.class, null);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasCauseSameAs(absent)),
                "failed outcome", "cause", absent, null);
    }

    @Test
    void aPresentCauseDoesNotSatisfyHasNoCause() {
        Entity entity = new Entity("e", State.NEW);
        IllegalStateException cause = new IllegalStateException("boom");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(failed(entity, Optional.of(cause))).isFailed().hasNoCause()),
                "failed outcome", "cause", Optional.empty(), Optional.of(cause));
    }

    // --- rejected ----------------------------------------------------------------------------------

    @Test
    void anAbsentEdgeRejectsWithoutNamingAGuard() {
        Entity entity = new Entity("e", State.NEW);
        RejectedOutcomeAssert<Entity, State, Id> view =
                OutcomeAssert.assertThat(rejected(entity, Optional.empty())).isRejected();
        assertSame(view, view.hasNoTransition());
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.isRejectedByGuardNamed("first")),
                "rejected outcome", "kind", RejectionKind.GUARD, RejectionKind.NO_TRANSITION);
    }

    @Test
    void aGuardRejectionNamesTheGuardThatRejected() {
        Entity entity = new Entity("e", State.NEW);
        RejectedOutcomeAssert<Entity, State, Id> view =
                OutcomeAssert.assertThat(rejected(entity, Optional.of("first"))).isRejected();
        assertSame(view, view.isRejectedByGuardNamed("first"));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.isRejectedByGuardNamed("second")),
                "rejected outcome", "guard", Optional.of("second"), Optional.of("first"));
        assertFailure(assertThrows(GraphAssertionError.class, view::hasNoTransition),
                "rejected outcome", "kind", RejectionKind.NO_TRANSITION, RejectionKind.GUARD);
    }

    @Test
    void rejectedReasonIsComparedByValue() {
        Entity entity = new Entity("e", State.NEW);
        Outcome<Entity, State, Id> outcome = new Outcome.Rejected<>(entity, State.NEW, Id.PREPARE,
                new StringBuilder("not ready").toString(), RejectionKind.NO_TRANSITION, Optional.empty());
        OutcomeAssert.assertThat(outcome).isRejected().hasReason("not ready");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isRejected().hasReason("ready")),
                "rejected outcome", "reason", "ready", "not ready");
    }

    // --- observed entity state ---------------------------------------------------------------------

    @Test
    void entityStateAssertReadsTheEntityAtEachAssertion() {
        Entity entity = new Entity("e", State.NEW);
        EntityStateAssert<Entity, State> view = EntityStateAssert.assertThat(entity, Entity::state);
        assertSame(view, view.isIn(State.NEW));
        entity.setState(State.DONE);
        assertSame(view, view.isIn(State.DONE));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.isIn(State.NEW)),
                "entity", "state", State.NEW, State.DONE);
    }

    @Test
    void entityStateAssertRejectsANullGetterUpFront() {
        Entity entity = new Entity("e", State.NEW);
        assertThrows(NullPointerException.class, () -> EntityStateAssert.assertThat(entity, null));
    }

    // --- null subjects: one case per expectation whose subject can be absent -----------------------

    @Test
    void nullSubjectFailsHasStatus() {
        Outcome<Entity, State, Id> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(none).hasStatus(OutcomeStatus.APPLIED)),
                "outcome", "status", OutcomeStatus.APPLIED, null);
    }

    @Test
    void nullSubjectFailsHasCode() {
        Outcome<Entity, State, Id> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(none).hasCode(OutcomeCode.APPLIED)),
                "outcome", "code", OutcomeCode.APPLIED, null);
    }

    @Test
    void nullSubjectFailsHasExplanation() {
        Outcome<Entity, State, Id> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(none).hasExplanation("why")),
                "outcome", "explanation", Optional.of("why"), null);
    }

    @Test
    void nullSubjectFailsHasNoExplanation() {
        Outcome<Entity, State, Id> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(none).hasNoExplanation()),
                "outcome", "explanation", Optional.empty(), null);
    }

    @Test
    void nullSubjectFailsRecords() {
        Outcome<Entity, State, Id> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(none).records().hasSize(0)),
                "outcome.records", "size", 0, null);
    }

    @Test
    void nullSubjectFailsReactions() {
        Outcome<Entity, State, Id> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(none).reactions().hasSize(0)),
                "outcome.reactions", "size", 0, null);
    }

    @Test
    void nullSubjectFailsFrom() {
        Outcome<Entity, State, Id> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(none).from(State.NEW)), "outcome", "from", State.NEW, null);
    }

    @Test
    void nullSubjectFailsRequestedTransition() {
        Outcome<Entity, State, Id> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(none).requestedTransition(Id.PREPARE)),
                "outcome", "requested transition", Optional.of(Id.PREPARE), null);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(none).hasNoRequestedTransition()),
                "outcome", "requested transition", Optional.empty(), null);
    }

    @Test
    void nullSubjectFailsHasRecordedState() {
        Outcome<Entity, State, Id> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(none).hasRecordedState(State.NEW)),
                "outcome", "recorded state", State.NEW, null);
    }

    @Test
    void nullSubjectFailsHasEntityState() {
        Outcome<Entity, State, Id> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(none).hasEntityState(Entity::state, State.NEW)),
                "outcome", "entity state", State.NEW, null);
    }

    @Test
    void nullSubjectFailsHasEntitySameAs() {
        Outcome<Entity, State, Id> none = null;
        Entity entity = new Entity("e", State.NEW);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(none).hasEntitySameAs(entity)), "outcome", "entity", entity, null);
    }

    @Test
    void nullSubjectFailsIsApplied() {
        Outcome<Entity, State, Id> none = null;
        assertFailure(assertThrows(GraphAssertionError.class, () -> OutcomeAssert.assertThat(none).isApplied()),
                "outcome", "status", OutcomeStatus.APPLIED, null);
    }

    @Test
    void nullSubjectFailsIsFailed() {
        Outcome<Entity, State, Id> none = null;
        assertFailure(assertThrows(GraphAssertionError.class, () -> OutcomeAssert.assertThat(none).isFailed()),
                "outcome", "status", OutcomeStatus.FAILED, null);
    }

    @Test
    void nullSubjectFailsIsRejected() {
        Outcome<Entity, State, Id> none = null;
        assertFailure(assertThrows(GraphAssertionError.class, () -> OutcomeAssert.assertThat(none).isRejected()),
                "outcome", "status", OutcomeStatus.REJECTED, null);
    }

    @Test
    void nullSubjectFailsIsIgnored() {
        Outcome<Entity, State, Id> none = null;
        assertFailure(assertThrows(GraphAssertionError.class, () -> OutcomeAssert.assertThat(none).isIgnored()),
                "outcome", "status", OutcomeStatus.IGNORED, null);
    }

    @Test
    void nullSubjectFailsIsIn() {
        Entity none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> EntityStateAssert.assertThat(none, Entity::state).isIn(State.NEW)),
                "entity", "state", State.NEW, null);
    }

    // --- against the real engine -------------------------------------------------------------------

    /**
     * Rebuilt inside this module from core's {@code HooksTest.hookMayOpenANewRootTransaction}: the hook
     * of {@code REVIEW} opens its own transition back to DRAFT, so the applied outcome records REVIEW
     * while the entity is read at DRAFT. Nothing here imports core's own test fixtures.
     */
    @Test
    void aHookThatOpensANewRootTransactionLeavesTheEntityBehindTheOutcome() {
        List<StateMachine<Paper, Docs, Step>> holder = new ArrayList<>();
        GraphDefinition<Paper, Docs, Step> definition = StateGraph.define(PAPERS)
                .initial(Docs.DRAFT).terminal(Docs.ARCHIVED).allowsCycle(Docs.REVIEW)
                .statePolicy(Docs.DRAFT, "draft only reviews")
                .statePolicy(Docs.REVIEW, "review returns or archives")
                .transition(Step.REVIEW, Docs.DRAFT, Docs.REVIEW)
                    .afterCommit("onReview", (Paper paper) -> holder.get(0).execute(paper, Step.RETURN))
                    .endTransition()
                .transition(Step.RETURN, Docs.REVIEW, Docs.DRAFT).endTransition()
                .transition(Step.ARCHIVE, Docs.REVIEW, Docs.ARCHIVED).endTransition()
                .build();
        StateMachine<Paper, Docs, Step> machine = StateMachines.from(definition)
                .state(Paper::status, Paper::setStatus).build();
        holder.add(machine);

        Paper paper = new Paper(Docs.DRAFT);
        Outcome<Paper, Docs, Step> outcome = machine.execute(paper, Step.REVIEW);

        OutcomeAssert.assertThat(outcome).isApplied()
                .movedTo(Docs.REVIEW)
                .from(Docs.DRAFT)
                .requestedTransition(Step.REVIEW)
                .hasRecordedState(Docs.REVIEW)
                .hasEntitySameAs(paper)
                .hasEntityState(Paper::status, Docs.DRAFT);
        EntityStateAssert.assertThat(paper, Paper::status).isIn(Docs.DRAFT);
        assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).hasEntityState(Paper::status, Docs.REVIEW));
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private static final MachineKey<Paper, Docs, Step> PAPERS =
            MachineKey.of(Paper.class, Docs.class, Step.class);

    /** States of {@link Paper}. */
    enum Docs { DRAFT, REVIEW, ARCHIVED }

    /** Transition identifiers of the paper machine. */
    enum Step { REVIEW, RETURN, ARCHIVE }

    /** Entity of the engine case: its state is written by the machine and read back by the assertions. */
    static final class Paper {

        private Docs status;

        Paper(Docs status) {
            this.status = status;
        }

        Docs status() {
            return status;
        }

        void setStatus(Docs status) {
            this.status = status;
        }
    }

    /** Value entity whose {@code equals} holds between distinct instances, to pin entity selection to identity. */
    record Twin(String id) { }

    /** Throwable whose {@code equals} holds between distinct instances, to pin cause selection to identity. */
    static final class ValueError extends RuntimeException {

        @Override
        public boolean equals(Object other) {
            return other instanceof ValueError;
        }

        @Override
        public int hashCode() {
            return ValueError.class.hashCode();
        }
    }

    private static Outcome.Applied<Entity, State, Id> applied(Entity entity) {
        return new Outcome.Applied<>(entity, State.NEW, Id.PREPARE, State.READY, List.of(), List.of());
    }

    private static Outcome.Failed<Entity, State, Id> failed(Entity entity, Optional<Throwable> cause) {
        return new Outcome.Failed<>(entity, State.NEW, Id.PREPARE, State.READY, Id.RECOVER, State.FAILED,
                "boom", cause, List.of(), List.of());
    }

    /** An empty guard is the absence of an edge; a named one is a guard that rejected the edge it guards. */
    private static Outcome.Rejected<Entity, State, Id> rejected(Entity entity, Optional<String> guard) {
        return new Outcome.Rejected<>(entity, State.NEW, Id.PREPARE, "not ready",
                guard.isPresent() ? RejectionKind.GUARD : RejectionKind.NO_TRANSITION, guard);
    }

    private static Outcome.Ignored<Entity, State, Id> ignored(Entity entity) {
        return new Outcome.Ignored<>(entity, State.NEW, IgnoreReason.NO_RULE_MATCHED);
    }

    private static void assertFailure(
            GraphAssertionError error, String context, String property, Object expected, Object actual) {
        assertEquals(context, error.context());
        assertEquals(property, error.property());
        assertEquals(expected, error.expected());
        assertEquals(actual, error.actual());
    }
}
