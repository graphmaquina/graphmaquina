package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
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
import io.github.graphmaquina.test.RejectedOutcomeAssert;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
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
        Outcome<Entity, State> result = new Outcome.Applied<>(entity, State.NEW,
                State.READY, List.of(), List.of());
        entity.setState(State.DONE);
        OutcomeAssert.assertThat(result).isApplied().movedTo(State.READY)
                .hasEntityState(Entity::state, State.DONE);
        assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(result).hasEntityState(Entity::state, State.READY));
    }

    @Test
    void rejectionByGuardIsNotForbidden() {
        Entity entity = new Entity("e", State.NEW);
        Outcome<Entity, State> rejected = new Outcome.Rejected<>(entity, State.NEW, State.READY,
                "not ready", Optional.of("first"));
        OutcomeAssert.assertThat(rejected).isRejected().isRejectedByGuardNamed("first");
        assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(rejected).isRejected().isForbidden());
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
        Outcome<Entity, State> outcome = applied(entity);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isFailed()), "outcome", "kind", "Failed", "Applied");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isRejected()), "outcome", "kind", "Rejected", "Applied");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isIgnored()), "outcome", "kind", "Ignored", "Applied");
    }

    @Test
    void failedSatisfiesNoOtherVariant() {
        Entity entity = new Entity("e", State.NEW);
        Outcome<Entity, State> outcome = failed(entity, Optional.empty());
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isApplied()), "outcome", "kind", "Applied", "Failed");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isRejected()), "outcome", "kind", "Rejected", "Failed");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isIgnored()), "outcome", "kind", "Ignored", "Failed");
    }

    @Test
    void rejectedSatisfiesNoOtherVariant() {
        Entity entity = new Entity("e", State.NEW);
        Outcome<Entity, State> outcome = rejected(entity, Optional.empty());
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isApplied()), "outcome", "kind", "Applied", "Rejected");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isFailed()), "outcome", "kind", "Failed", "Rejected");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isIgnored()), "outcome", "kind", "Ignored", "Rejected");
    }

    @Test
    void ignoredSatisfiesNoOtherVariant() {
        Entity entity = new Entity("e", State.NEW);
        Outcome<Entity, State> outcome = ignored(entity);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isApplied()), "outcome", "kind", "Applied", "Ignored");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isFailed()), "outcome", "kind", "Failed", "Ignored");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isRejected()), "outcome", "kind", "Rejected", "Ignored");
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

    @Test
    void requestedTargetIsTheAttemptedTransitionOnEveryVariant() {
        Entity entity = new Entity("e", State.NEW);
        OutcomeAssert.assertThat(applied(entity)).requestedTarget(State.READY);
        OutcomeAssert.assertThat(failed(entity, Optional.empty())).requestedTarget(State.DONE);
        OutcomeAssert.assertThat(rejected(entity, Optional.empty())).requestedTarget(State.READY);
        OutcomeAssert.assertThat(ignored(entity)).requestedTarget(State.READY);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(failed(entity, Optional.empty())).requestedTarget(State.FAILED)),
                "outcome", "requested target", State.FAILED, State.DONE);
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
    void ignoredKeepsTheOriginEvenWhenAnotherTargetWasRequested() {
        Entity entity = new Entity("e", State.NEW);
        IgnoredOutcomeAssert<Entity, State> view = OutcomeAssert.assertThat(ignored(entity)).isIgnored();
        assertSame(view, view.requestedTarget(State.READY).hasRecordedState(State.NEW).from(State.NEW));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasRecordedState(State.READY)),
                "ignored outcome", "recorded state", State.READY, State.NEW);
    }

    @Test
    void entityStateIsReadOncePerAssertionAndNotAtViewCreation() {
        Entity entity = new Entity("e", State.NEW);
        OutcomeAssert<Entity, State> view = OutcomeAssert.assertThat(applied(entity));
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
        Outcome<Entity, State> none = null;
        assertThrows(NullPointerException.class, () -> OutcomeAssert.assertThat(none).from(null));
        assertThrows(NullPointerException.class, () -> OutcomeAssert.assertThat(none).requestedTarget(null));
        assertThrows(NullPointerException.class, () -> OutcomeAssert.assertThat(none).hasRecordedState(null));
        assertThrows(NullPointerException.class, () -> OutcomeAssert.assertThat(none).hasEntitySameAs(null));
    }

    @Test
    void entitySelectionIsByReferenceNotByEquality() {
        Twin entity = new Twin("e");
        Twin sameValue = new Twin("e");
        assertEquals(entity, sameValue);
        assertNotSame(entity, sameValue);
        Outcome<Twin, State> outcome = new Outcome.Applied<>(entity, State.NEW, State.READY, List.of(), List.of());
        OutcomeAssert.assertThat(outcome).hasEntitySameAs(entity);
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).hasEntitySameAs(sameValue));
        assertEquals("outcome", error.context());
        assertEquals("entity", error.property());
        assertSame(sameValue, error.expected());
        assertSame(entity, error.actual());
    }

    // --- applied -----------------------------------------------------------------------------------

    @Test
    void appliedMovedToReadsTheCommittedDestination() {
        Entity entity = new Entity("e", State.NEW);
        AppliedOutcomeAssert<Entity, State> view = OutcomeAssert.assertThat(applied(entity)).isApplied();
        assertSame(view, view.movedTo(State.READY));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.movedTo(State.DONE)),
                "applied outcome", "moved to", State.DONE, State.READY);
    }

    // --- failed ------------------------------------------------------------------------------------

    @Test
    void failedMovedToIsTheFailureRouteNotTheRequestedTarget() {
        Entity entity = new Entity("e", State.NEW);
        FailedOutcomeAssert<Entity, State> view =
                OutcomeAssert.assertThat(failed(entity, Optional.empty())).isFailed();
        assertNotEquals(State.DONE, State.FAILED);
        assertSame(view, view.requestedTarget(State.DONE).movedTo(State.FAILED));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.movedTo(State.DONE)),
                "failed outcome", "moved to", State.DONE, State.FAILED);
    }

    @Test
    void failedReasonIsComparedByValue() {
        Entity entity = new Entity("e", State.NEW);
        Outcome<Entity, State> outcome = new Outcome.Failed<>(entity, State.NEW, State.DONE, State.FAILED,
                new StringBuilder("boom").toString(), Optional.empty(), List.of(), List.of());
        OutcomeAssert.assertThat(outcome).isFailed().hasReason("boom");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).isFailed().hasReason("other")),
                "failed outcome", "reason", "other", "boom");
    }

    @Test
    void causeIsMatchedByTypeIncludingSubclasses() {
        Entity entity = new Entity("e", State.NEW);
        UncheckedIOException cause = new UncheckedIOException(new IOException("io"));
        FailedOutcomeAssert<Entity, State> view =
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
        FailedOutcomeAssert<Entity, State> view =
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
        FailedOutcomeAssert<Entity, State> view =
                OutcomeAssert.assertThat(failed(entity, Optional.of(cause))).isFailed();
        assertSame(view, view.hasCauseSameAs(cause));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasCauseSameAs(sameValue)),
                "failed outcome", "cause", sameValue, cause);
    }

    @Test
    void anAbsentCauseSatisfiesOnlyHasNoCause() {
        Entity entity = new Entity("e", State.NEW);
        IllegalStateException absent = new IllegalStateException("never happened");
        FailedOutcomeAssert<Entity, State> view =
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
    void aForbiddenCellNamesNoGuard() {
        Entity entity = new Entity("e", State.NEW);
        RejectedOutcomeAssert<Entity, State> view =
                OutcomeAssert.assertThat(rejected(entity, Optional.empty())).isRejected();
        assertSame(view, view.isForbidden());
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.isRejectedByGuardNamed("first")),
                "rejected outcome", "guard", Optional.of("first"), Optional.empty());
    }

    @Test
    void aGuardRejectionNamesTheGuardThatRejected() {
        Entity entity = new Entity("e", State.NEW);
        RejectedOutcomeAssert<Entity, State> view =
                OutcomeAssert.assertThat(rejected(entity, Optional.of("first"))).isRejected();
        assertSame(view, view.isRejectedByGuardNamed("first"));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.isRejectedByGuardNamed("second")),
                "rejected outcome", "guard", Optional.of("second"), Optional.of("first"));
        assertFailure(assertThrows(GraphAssertionError.class, view::isForbidden),
                "rejected outcome", "guard", Optional.empty(), Optional.of("first"));
    }

    @Test
    void rejectedReasonIsComparedByValue() {
        Entity entity = new Entity("e", State.NEW);
        Outcome<Entity, State> outcome = new Outcome.Rejected<>(entity, State.NEW, State.READY,
                new StringBuilder("not ready").toString(), Optional.empty());
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
    void nullSubjectFailsFrom() {
        Outcome<Entity, State> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(none).from(State.NEW)), "outcome", "from", State.NEW, null);
    }

    @Test
    void nullSubjectFailsRequestedTarget() {
        Outcome<Entity, State> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(none).requestedTarget(State.NEW)),
                "outcome", "requested target", State.NEW, null);
    }

    @Test
    void nullSubjectFailsHasRecordedState() {
        Outcome<Entity, State> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(none).hasRecordedState(State.NEW)),
                "outcome", "recorded state", State.NEW, null);
    }

    @Test
    void nullSubjectFailsHasEntityState() {
        Outcome<Entity, State> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(none).hasEntityState(Entity::state, State.NEW)),
                "outcome", "entity state", State.NEW, null);
    }

    @Test
    void nullSubjectFailsHasEntitySameAs() {
        Outcome<Entity, State> none = null;
        Entity entity = new Entity("e", State.NEW);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(none).hasEntitySameAs(entity)), "outcome", "entity", entity, null);
    }

    @Test
    void nullSubjectFailsIsApplied() {
        Outcome<Entity, State> none = null;
        assertFailure(assertThrows(GraphAssertionError.class, () -> OutcomeAssert.assertThat(none).isApplied()),
                "outcome", "kind", "Applied", null);
    }

    @Test
    void nullSubjectFailsIsFailed() {
        Outcome<Entity, State> none = null;
        assertFailure(assertThrows(GraphAssertionError.class, () -> OutcomeAssert.assertThat(none).isFailed()),
                "outcome", "kind", "Failed", null);
    }

    @Test
    void nullSubjectFailsIsRejected() {
        Outcome<Entity, State> none = null;
        assertFailure(assertThrows(GraphAssertionError.class, () -> OutcomeAssert.assertThat(none).isRejected()),
                "outcome", "kind", "Rejected", null);
    }

    @Test
    void nullSubjectFailsIsIgnored() {
        Outcome<Entity, State> none = null;
        assertFailure(assertThrows(GraphAssertionError.class, () -> OutcomeAssert.assertThat(none).isIgnored()),
                "outcome", "kind", "Ignored", null);
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
     * of DRAFT -> REVIEW opens its own transition back to DRAFT, so the applied outcome records REVIEW
     * while the entity is read at DRAFT. Nothing here imports core's own test fixtures.
     */
    @Test
    void aHookThatOpensANewRootTransactionLeavesTheEntityBehindTheOutcome() {
        List<StateMachine<Paper, Docs>> holder = new ArrayList<>();
        GraphDefinition<Paper, Docs> definition = StateGraph.define(PAPERS)
                .initial(Docs.DRAFT).terminal(Docs.ARCHIVED).allowsCycle(Docs.REVIEW)
                .from(Docs.DRAFT)
                    .to(Docs.REVIEW).move()
                        .afterCommit("onReview", (Paper paper) -> holder.get(0).transitionTo(paper, Docs.DRAFT))
                    .stay().forbidOthers("draft only reviews")
                .from(Docs.REVIEW).to(Docs.DRAFT).move().to(Docs.ARCHIVED).move()
                    .stay().forbidOthers("review returns or archives")
                .from(Docs.ARCHIVED).stay().forbidOthers("archived is final")
                .build();
        StateMachine<Paper, Docs> machine = StateMachines.from(definition)
                .state(Paper::status, Paper::setStatus).build();
        holder.add(machine);

        Paper paper = new Paper(Docs.DRAFT);
        Outcome<Paper, Docs> outcome = machine.transitionTo(paper, Docs.REVIEW);

        OutcomeAssert.assertThat(outcome).isApplied()
                .movedTo(Docs.REVIEW)
                .from(Docs.DRAFT)
                .requestedTarget(Docs.REVIEW)
                .hasRecordedState(Docs.REVIEW)
                .hasEntitySameAs(paper)
                .hasEntityState(Paper::status, Docs.DRAFT);
        EntityStateAssert.assertThat(paper, Paper::status).isIn(Docs.DRAFT);
        assertThrows(GraphAssertionError.class,
                () -> OutcomeAssert.assertThat(outcome).hasEntityState(Paper::status, Docs.REVIEW));
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private static final MachineKey<Paper, Docs> PAPERS = MachineKey.of(Paper.class, Docs.class);

    /** States of {@link Paper}. */
    enum Docs { DRAFT, REVIEW, ARCHIVED }

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

    private static Outcome.Applied<Entity, State> applied(Entity entity) {
        return new Outcome.Applied<>(entity, State.NEW, State.READY, List.of(), List.of());
    }

    private static Outcome.Failed<Entity, State> failed(Entity entity, Optional<Throwable> cause) {
        return new Outcome.Failed<>(entity, State.NEW, State.DONE, State.FAILED, "boom", cause,
                List.of(), List.of());
    }

    private static Outcome.Rejected<Entity, State> rejected(Entity entity, Optional<String> guard) {
        return new Outcome.Rejected<>(entity, State.NEW, State.READY, "not ready", guard);
    }

    private static Outcome.Ignored<Entity, State> ignored(Entity entity) {
        return new Outcome.Ignored<>(entity, State.NEW, State.READY);
    }

    private static void assertFailure(
            GraphAssertionError error, String context, String property, Object expected, Object actual) {
        assertEquals(context, error.context());
        assertEquals(property, error.property());
        assertEquals(expected, error.expected());
        assertEquals(actual, error.actual());
    }
}
