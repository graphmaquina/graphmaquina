package consumer;

import static consumer.GraphAssertionErrors.assertFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.AutomaticStep;
import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.api.IgnoreReason;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.IgnoredOutcomeAssert;
import io.github.graphmaquina.test.OutcomeAssert;
import io.github.graphmaquina.test.RejectionsAssert;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Contract of what the outcome view offers over the automatic steps of a call: the list, the three searches and their
 * "none" counterparts, and the rejections of a blocked attempt. A search fails with an absent actual value when it
 * finds nothing, a "none" check carries what it found, a result of another machine is a programming error in the
 * calling test, and every expectation gets its own null-subject case. The views of the list itself are in
 * {@link AutomaticStepsAssertionsTest}.
 */
class AutomaticOutcomeAssertionsTest {

    private static final String OUTCOME = "outcome";
    private static final String FAILURE = "automatic failure";
    private static final String ABORT = "automatic abort";
    private static final String BLOCK = "automatic block";

    // --- automaticSteps() ----------------------------------------------------------------------------

    @Test
    void theStepsOfAnOutcomeAreAViewNamedAfterIt() {
        Entity entity = new Entity("e", State.READY);
        OutcomeAssert<Entity, State, Id> view =
                OutcomeAssert.assertThat(withSteps(entity, done(entity, applied(entity))));
        view.automaticSteps().hasSize(1);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.automaticSteps().hasSize(2)),
                "outcome.automaticSteps", "size", 2, 1);
        OutcomeAssert.assertThat(applied(entity)).automaticSteps().isEmpty();
    }

    @Test
    void nullSubjectFailsTheStepsOfAnOutcome() {
        Outcome<Entity, State, Id> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(none).automaticSteps().hasSize(1)),
                "outcome.automaticSteps", "size", 1, null);
    }

    // --- automaticFailure() --------------------------------------------------------------------------

    @Test
    void anAutomaticFailureIsFoundAndReadWithTheViewOfAFailedOutcome() {
        Entity entity = new Entity("e", State.READY);
        OutcomeAssert<Entity, State, Id> view = OutcomeAssert.assertThat(withSteps(entity,
                done(entity, applied(entity)), done(entity, failed(entity))));
        view.automaticFailure().hasTarget(State.DONE).hasFailureTransition(Id.RECOVER).movedTo(State.FAILED);
    }

    @Test
    void anAutomaticFailureNestedInAnAttemptIsFound() {
        Entity entity = new Entity("e", State.READY);
        Outcome<Entity, State, Id> inner = withSteps(entity, done(entity, failed(entity)));
        OutcomeAssert.assertThat(withSteps(entity, done(entity, inner))).automaticFailure()
                .hasFailureTransition(Id.RECOVER);
    }

    @Test
    void noAutomaticFailureFailsTheSearchWithAnAbsentActualValue() {
        Entity entity = new Entity("e", State.READY);
        OutcomeAssert<Entity, State, Id> view =
                OutcomeAssert.assertThat(withSteps(entity, done(entity, applied(entity))));
        assertFailure(assertThrows(GraphAssertionError.class, view::automaticFailure),
                OUTCOME, FAILURE, "a failed automatic attempt", null);
    }

    @Test
    void nullSubjectFailsTheSearchForAFailure() {
        Outcome<Entity, State, Id> none = null;
        assertFailure(assertThrows(GraphAssertionError.class, () -> OutcomeAssert.assertThat(none).automaticFailure()),
                OUTCOME, FAILURE, "a failed automatic attempt", null);
    }

    @Test
    void aFailureOfAnotherMachineIsAProgrammingErrorNotAWrongAnswer() {
        Entity entity = new Entity("e", State.READY);
        Outcome<?, ?, ?> foreign = new Outcome.Failed<>("other", Other.A, Other.GO, Other.B, Other.GO, Other.B,
                "boom", Optional.empty(), List.of(), List.of());
        OutcomeAssert<Entity, State, Id> view = OutcomeAssert.assertThat(withSteps(entity, done("other", foreign)));
        assertThrows(IllegalArgumentException.class, view::automaticFailure);
    }

    // --- hasNoAutomaticFailure() ---------------------------------------------------------------------

    @Test
    void hasNoAutomaticFailureHoldsWithoutOneAndCarriesTheOneItFinds() {
        Entity entity = new Entity("e", State.READY);
        OutcomeAssert<Entity, State, Id> clean = OutcomeAssert.assertThat(withSteps(entity,
                done(entity, applied(entity))));
        assertSame(clean, clean.hasNoAutomaticFailure());
        Outcome.Failed<Entity, State, Id> failure = failed(entity);
        OutcomeAssert<Entity, State, Id> broken = OutcomeAssert.assertThat(withSteps(entity, done(entity, failure)));
        assertFailure(assertThrows(GraphAssertionError.class, broken::hasNoAutomaticFailure),
                OUTCOME, FAILURE, "none", failure);
    }

    @Test
    void nullSubjectFailsHasNoAutomaticFailure() {
        Outcome<Entity, State, Id> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(none).hasNoAutomaticFailure()), OUTCOME, FAILURE, "none", null);
    }

    // --- automaticAbort() ----------------------------------------------------------------------------

    @Test
    void anAutomaticAbortIsFoundAndReadByItsCause() {
        Entity entity = new Entity("e", State.READY);
        IllegalStateException cause = new IllegalStateException("down");
        OutcomeAssert.assertThat(withSteps(entity, done(entity, applied(entity)),
                        new AutomaticStep.Aborted(TestDomain.KEY, entity, cause)))
                .automaticAbort().hasCause(IllegalStateException.class).hasCauseSameAs(cause);
    }

    @Test
    void noAutomaticAbortFailsTheSearchWithAnAbsentActualValue() {
        Entity entity = new Entity("e", State.READY);
        OutcomeAssert<Entity, State, Id> view = OutcomeAssert.assertThat(applied(entity));
        assertFailure(assertThrows(GraphAssertionError.class, view::automaticAbort),
                OUTCOME, ABORT, "an aborted automatic step", null);
    }

    @Test
    void nullSubjectFailsTheSearchForAnAbort() {
        Outcome<Entity, State, Id> none = null;
        assertFailure(assertThrows(GraphAssertionError.class, () -> OutcomeAssert.assertThat(none).automaticAbort()),
                OUTCOME, ABORT, "an aborted automatic step", null);
    }

    @Test
    void hasNoAutomaticAbortHoldsWithoutOneAndCarriesTheCauseOfTheOneItFinds() {
        Entity entity = new Entity("e", State.READY);
        OutcomeAssert<Entity, State, Id> clean = OutcomeAssert.assertThat(applied(entity));
        assertSame(clean, clean.hasNoAutomaticAbort());
        IllegalStateException cause = new IllegalStateException("down");
        OutcomeAssert<Entity, State, Id> broken = OutcomeAssert.assertThat(withSteps(entity,
                new AutomaticStep.Aborted(TestDomain.KEY, entity, cause)));
        assertFailure(assertThrows(GraphAssertionError.class, broken::hasNoAutomaticAbort),
                OUTCOME, ABORT, "none", cause);
    }

    @Test
    void nullSubjectFailsHasNoAutomaticAbort() {
        Outcome<Entity, State, Id> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(none).hasNoAutomaticAbort()), OUTCOME, ABORT, "none", null);
    }

    // --- automaticBlock() and rejections() -----------------------------------------------------------

    @Test
    void anAutomaticBlockIsFoundAndItsRejectionsAreRead() {
        Entity entity = new Entity("e", State.READY);
        IgnoredOutcomeAssert<Entity, State, Id> blocked = OutcomeAssert.assertThat(
                withSteps(entity, done(entity, blockedAttempt(entity)))).automaticBlock();
        blocked.hasReason(IgnoreReason.NO_CANDIDATE_ADMITTED);
        blocked.rejections().hasSize(1).single().isIneligible().isRejectedByGuardNamed("open")
                .hasReason("not yet").transition(Id.FINISH).from(State.READY);
    }

    @Test
    void noAutomaticBlockFailsTheSearchWithAnAbsentActualValue() {
        Entity entity = new Entity("e", State.READY);
        OutcomeAssert<Entity, State, Id> view = OutcomeAssert.assertThat(withSteps(entity,
                done(entity, new Outcome.Ignored<>(entity, State.READY, IgnoreReason.NO_AUTOMATIC_TRANSITION))));
        assertFailure(assertThrows(GraphAssertionError.class, view::automaticBlock),
                OUTCOME, BLOCK, "a blocked automatic attempt", null);
    }

    @Test
    void nullSubjectFailsTheSearchForABlock() {
        Outcome<Entity, State, Id> none = null;
        assertFailure(assertThrows(GraphAssertionError.class, () -> OutcomeAssert.assertThat(none).automaticBlock()),
                OUTCOME, BLOCK, "a blocked automatic attempt", null);
    }

    @Test
    void aBlockOfAnotherMachineIsAProgrammingErrorNotAWrongAnswer() {
        Entity entity = new Entity("e", State.READY);
        Outcome<?, ?, ?> foreign = new Outcome.Ignored<>("other", Other.A, IgnoreReason.NO_CANDIDATE_ADMITTED,
                List.of(new Eligibility.Ineligible<>("other", Other.A, Other.GO, "no", RejectionKind.NO_TRANSITION,
                        Optional.empty())));
        OutcomeAssert<Entity, State, Id> view = OutcomeAssert.assertThat(withSteps(entity, done("other", foreign)));
        assertThrows(IllegalArgumentException.class, view::automaticBlock);
    }

    @Test
    void hasNoAutomaticBlockHoldsWithoutOneAndCarriesTheOneItFinds() {
        Entity entity = new Entity("e", State.READY);
        OutcomeAssert<Entity, State, Id> clean = OutcomeAssert.assertThat(applied(entity));
        assertSame(clean, clean.hasNoAutomaticBlock());
        Outcome.Ignored<Entity, State, Id> blocked = blockedAttempt(entity);
        OutcomeAssert<Entity, State, Id> broken = OutcomeAssert.assertThat(withSteps(entity, done(entity, blocked)));
        assertFailure(assertThrows(GraphAssertionError.class, broken::hasNoAutomaticBlock),
                OUTCOME, BLOCK, "none", blocked);
    }

    @Test
    void nullSubjectFailsHasNoAutomaticBlock() {
        Outcome<Entity, State, Id> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(none).hasNoAutomaticBlock()), OUTCOME, BLOCK, "none", null);
    }

    @Test
    void theRejectionsAreCountedIndexedAndEmptyForAnyOtherReason() {
        Entity entity = new Entity("e", State.READY);
        RejectionsAssert<Entity, State, Id> rejections = OutcomeAssert.assertThat(withSteps(entity,
                done(entity, blockedAttempt(entity)))).automaticBlock().rejections();
        String context = "outcome.automaticBlock (ignored).rejections";
        assertSame(rejections, rejections.hasSize(1));
        rejections.atIndex(0).isIneligible();
        assertFailure(assertThrows(GraphAssertionError.class, () -> rejections.hasSize(2)), context, "size", 2, 1);
        assertFailure(assertThrows(GraphAssertionError.class, () -> rejections.atIndex(1)),
                context, "index 1 present", Boolean.TRUE, Boolean.FALSE);
        assertEquals("index must not be negative: -1",
                assertThrows(IllegalArgumentException.class, () -> rejections.atIndex(-1)).getMessage());
        Outcome<Entity, State, Id> ignored =
                new Outcome.Ignored<>(entity, State.READY, IgnoreReason.NO_AUTOMATIC_TRANSITION);
        RejectionsAssert<Entity, State, Id> none = OutcomeAssert.assertThat(ignored).isIgnored().rejections();
        none.hasSize(0);
        assertFailure(assertThrows(GraphAssertionError.class, none::single),
                "ignored outcome.rejections", "size", 1, 0);
    }

    // --- fixtures ------------------------------------------------------------------------------------

    /** States and identifiers of a machine that is not the test domain's. */
    enum Other { A, B, GO }

    private static Outcome.Applied<Entity, State, Id> applied(Entity entity) {
        return new Outcome.Applied<>(entity, State.NEW, Id.PREPARE, State.READY, List.of(), List.of());
    }

    private static Outcome.Applied<Entity, State, Id> withSteps(Entity entity, AutomaticStep... steps) {
        return new Outcome.Applied<>(entity, State.NEW, Id.PREPARE, State.READY, List.of(), List.of(), List.of(),
                FlowView.empty(), List.of(steps));
    }

    private static Outcome.Failed<Entity, State, Id> failed(Entity entity) {
        return new Outcome.Failed<>(entity, State.READY, Id.FINISH, State.DONE, Id.RECOVER, State.FAILED, "boom",
                Optional.empty(), List.of(), List.of());
    }

    /** An attempt whose only candidate, FINISH, was refused by its guard {@code open}. */
    private static Outcome.Ignored<Entity, State, Id> blockedAttempt(Entity entity) {
        return new Outcome.Ignored<>(entity, State.READY, IgnoreReason.NO_CANDIDATE_ADMITTED,
                List.of(new Eligibility.Ineligible<>(entity, State.READY, Id.FINISH, "not yet", RejectionKind.GUARD,
                        Optional.of("open"))));
    }

    private static AutomaticStep.Done done(Object entity, Outcome<?, ?, ?> outcome) {
        return new AutomaticStep.Done(TestDomain.KEY, entity, outcome);
    }
}
