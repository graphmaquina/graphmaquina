package consumer;

import static consumer.GraphAssertionErrors.assertFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.error.WatchChainExceededException;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;
import io.github.graphmaquina.test.AbortAssert;
import io.github.graphmaquina.test.CascadeAbortAssert;
import io.github.graphmaquina.test.CommitFailureAssert;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.InvalidGraphAssert;
import io.github.graphmaquina.test.TransitionFailureAssert;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.junit.jupiter.api.Test;

/**
 * The failure views over the failures that a real graph definition and a real engine raise, where
 * {@link FailureAssertionsTest} builds the exceptions by hand: an invalid graph, a watch chain that exceeds its
 * limit, a setter that throws in the commit, a collected cascade, a hook that throws after the commit and an action
 * that fails without a route. A mismatch is read off the fields of the {@link GraphAssertionError}.
 */
class EngineFailureAssertionsTest {

    // --- the abort ---------------------------------------------------------------------------------

    /**
     * The engine aborts a chain of watch reactions that exceeds {@code maxWatchChain} before it reads any
     * identifier, in valid use, so this is the abort {@code atUnread} is for.
     */
    @Test
    void aWatchChainThatExceedsItsLimitAbortsBeforeAnyIdentifierWasRead() {
        try (MachineRegistry registry = pingPong(1)) {
            Doc doc = new Doc("d1", Phase.DRAFT);
            Outcome<Doc, Phase, Step> root = registry.of(DOCS).execute(doc, Step.SUBMIT);
            Reaction.Done first = (Reaction.Done) ((Outcome.Applied<?, ?, ?>) root).reactions().get(0);
            Outcome.Applied<?, ?, ?> nested = (Outcome.Applied<?, ?, ?>) first.outcome();
            RuntimeException cause = ((Reaction.Aborted) nested.reactions().get(0)).cause();
            assertInstanceOf(WatchChainExceededException.class, cause);
            AbortAssert view = TransitionFailureAssert.assertThat(cause).isAbort();
            assertSame(view, view.atUnread(DOCS, doc, Phase.DRAFT));
            view.hasNoAction().hasCascadePathExactly(List.of());
            for (Step step : Step.values()) {
                assertThrows(GraphAssertionError.class, () -> view.atUnresolved(DOCS, doc, Phase.DRAFT, step));
            }
        }
    }

    // --- the DSL -----------------------------------------------------------------------------------

    @Test
    void anInvalidGraphIsReadByItsCodesAndTheirMultiplicity() {
        InvalidGraphException unreachable = assertThrows(InvalidGraphException.class,
                () -> StateGraph.define(LABELS).initial(LabelStatus.BLANK)
                        .terminal(LabelStatus.BLANK).terminal(LabelStatus.PRINTED).terminal(LabelStatus.VOID)
                        .build());
        InvalidGraphAssert view = InvalidGraphAssert.assertThat(unreachable);
        assertSame(view, view.hasCodesExactly("SG203", "SG203"));
        assertSame(view, view.hasProblemCount(2));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasCodesExactly("SG203")),
                "invalid graph", "codes", List.of("SG203"), List.of("SG203", "SG203"));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasProblemCount(1)),
                "invalid graph", "problem count", 1, 2);
    }

    /**
     * The route a consumer without JUnit has: {@code captureFailure} hands back a {@link Throwable}, which
     * binds to the failure view, so the DSL view is reached by narrowing rather than by a cast.
     */
    @Test
    void anInvalidGraphCapturedAsAThrowableIsReachedByNarrowing() {
        Throwable failure = TransitionFailureAssert.captureFailure(
                () -> StateGraph.define(LABELS).terminal(LabelStatus.BLANK)
                        .terminal(LabelStatus.PRINTED).terminal(LabelStatus.VOID).build());
        TransitionFailureAssert.assertThat(failure).isInvalidGraph().hasCodesExactly("SG201");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionFailureAssert.assertThat(failure).isInvalidGraph().hasCodesExactly("SG203")),
                "invalid graph", "codes", List.of("SG203"), List.of("SG201"));
    }

    @Test
    void everyProblemIsReportedWithItsOwnCodeInOrder() {
        InvalidGraphException several = assertThrows(InvalidGraphException.class,
                () -> StateGraph.define(LABELS).initial(LabelStatus.BLANK)
                        .terminal(LabelStatus.PRINTED).terminal(LabelStatus.VOID).build());
        InvalidGraphAssert.assertThat(several)
                .hasCodesExactly("SG202", "SG203", "SG203", "SG701").hasProblemCount(4);
        assertFailure(assertThrows(GraphAssertionError.class, () -> InvalidGraphAssert.assertThat(several)
                        .hasCodesExactly("SG203", "SG202", "SG203", "SG701")),
                "invalid graph", "codes", List.of("SG203", "SG202", "SG203", "SG701"),
                List.of("SG202", "SG203", "SG203", "SG701"));
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> InvalidGraphAssert.assertThat(several).hasCodesExactly("SG202", "SG203", "SG701")),
                "invalid graph", "codes", List.of("SG202", "SG203", "SG701"),
                List.of("SG202", "SG203", "SG203", "SG701"));
    }

    // --- against the real engine -------------------------------------------------------------------

    @Test
    void theFailedEntityMayHaveBeenMutatedBeforeItsSetterThrew() {
        Entity entity = new Entity("e", State.NEW);
        IllegalStateException setterFailure = new IllegalStateException("setter wrote then failed");
        GraphDefinition<Entity, State, Id> graph = StateGraph.define(TestDomain.KEY).initial(State.NEW)
                .terminal(State.DONE).terminal(State.FAILED)
                .statePolicy(State.NEW, "x").statePolicy(State.READY, "x")
                .transition(Id.PREPARE, State.NEW, State.READY).endTransition()
                .transition(Id.RECOVER, State.NEW, State.FAILED).endTransition()
                .transition(Id.FINISH, State.READY, State.DONE).endTransition()
                .build();

        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Entity::state, (subject, state) -> {
                    subject.setState(state);
                    throw setterFailure;
                }).identity(Entity::id)).build()) {
            Throwable failure = TransitionFailureAssert.captureFailure(
                    () -> registry.of(TestDomain.KEY).execute(entity, Id.PREPARE));
            TransitionFailureAssert.assertThat(failure).isCommitFailure()
                    .hasAppliedExactly().hasFailedEntity(entity).hasPendingExactly().hasCauseSameAs(setterFailure);
            assertEquals(State.READY, entity.state());
        }
    }

    /**
     * Phase 2 writes the parcel and then throws on the label's setter. Nothing is undone, so the parcel is
     * already sent while the label never moved: the view reports exactly that split, and the expectation a
     * clean rollback would satisfy fails on the very same exception.
     */
    @Test
    void aSetterThatThrowsLeavesTheEarlierWriteApplied() {
        Parcel parcel = new Parcel("p1", ParcelStatus.HELD);
        Label label = new Label("l1", LabelStatus.BLANK);
        IllegalStateException boom = new IllegalStateException("the label store is down");
        label.failOnWrite(boom);
        send = (TransitionContext<Parcel, ParcelStatus, Delivery> ctx) -> {
            ctx.cascade(LABELS, label, Printing.PRINT);
            return ActionOutcome.ok();
        };

        try (MachineRegistry registry = registry()) {
            Throwable failure = TransitionFailureAssert.captureFailure(
                    () -> registry.of(PARCELS).execute(parcel, Delivery.SEND));
            CommitFailureAssert view = TransitionFailureAssert.assertThat(failure).isCommitFailure();
            assertSame(view,
                    view.hasAppliedExactly(parcel).hasFailedEntity(label).hasPendingExactly().hasCauseSameAs(boom));
            assertEquals(ParcelStatus.SENT, parcel.status());
            assertEquals(LabelStatus.BLANK, label.status());
            assertThrows(GraphAssertionError.class, view::hasAppliedExactly);
        }
    }

    /**
     * A COLLECT cascade tries every label, so both refused ones are collected instead of the first aborting
     * the loop. The aggregate is an abort too, and its journal is the parent's: no edge leaves VOID under
     * PRINT, so each element aborts before resolving anything and reports no destination at all.
     */
    @Test
    void aCollectedCascadeAggregatesOneErrorPerForbiddenElement() {
        Parcel parcel = new Parcel("p1", ParcelStatus.HELD);
        Label first = new Label("l1", LabelStatus.VOID);
        Label second = new Label("l2", LabelStatus.VOID);
        send = (TransitionContext<Parcel, ParcelStatus, Delivery> ctx) -> {
            ctx.cascadeAll(LABELS, List.of(first, second), Printing.PRINT, CascadePolicy.COLLECT);
            return ActionOutcome.ok();
        };

        try (MachineRegistry registry = registry()) {
            Throwable failure = TransitionFailureAssert.captureFailure(
                    () -> registry.of(PARCELS).execute(parcel, Delivery.SEND));
            CascadeAbortAssert view = TransitionFailureAssert.assertThat(failure).isCascadeAbort();
            assertSame(view, view.hasErrorCount(2).hasNoCause());
            view.error(0).atUnresolved(LABELS, first, LabelStatus.VOID, Printing.PRINT).hasNoAction()
                    .hasCascadePathExactly(List.of("Parcel/ParcelStatus#p1"));
            view.error(1).atUnresolved(LABELS, second, LabelStatus.VOID, Printing.PRINT).hasNoAction();
            view.journal().hasActionsExactly("Send").hasStatusesExactly(ActionStatus.THREW);
            TransitionFailureAssert.assertThat(failure).isAbort()
                    .at(PARCELS, parcel, ParcelStatus.HELD, Delivery.SEND, ParcelStatus.SENT).hasNoAction();
            assertEquals(ParcelStatus.HELD, parcel.status());
            assertEquals(LabelStatus.VOID, first.status());
            assertEquals(LabelStatus.VOID, second.status());
        }
    }

    /**
     * The hook runs once the state is already written, so the entity has moved even though the call threw.
     * That is what keeps this failure apart from an abort, which leaves the entity exactly where it was.
     */
    @Test
    void aHookThatThrowsArrivesWithTheEntityAlreadyMoved() {
        Parcel parcel = new Parcel("p1", ParcelStatus.HELD);
        IllegalStateException boom = new IllegalStateException("audit down");
        send = (TransitionContext<Parcel, ParcelStatus, Delivery> ctx) -> ActionOutcome.ok();
        audit = (Parcel sent) -> { throw boom; };

        try (MachineRegistry registry = registry()) {
            Throwable failure = TransitionFailureAssert.captureFailure(
                    () -> registry.of(PARCELS).execute(parcel, Delivery.SEND));
            TransitionFailureAssert.assertThat(failure).isAfterCommit()
                    .at(PARCELS, parcel, ParcelStatus.HELD, Delivery.SEND, ParcelStatus.SENT)
                    .hasHookNamed("audit").hasCauseSameAs(boom);
            assertEquals(ParcelStatus.SENT, parcel.status());
            assertThrows(GraphAssertionError.class,
                    () -> TransitionFailureAssert.assertThat(failure).isAbort());
        }
    }

    /** An action that fails without an onFailure route aborts: nothing moved, and the journal says so. */
    @Test
    void anActionThatFailsWithoutARouteAbortsAndLeavesEverythingWhereItWas() {
        Parcel parcel = new Parcel("p1", ParcelStatus.HELD);
        send = (TransitionContext<Parcel, ParcelStatus, Delivery> ctx) -> ActionOutcome.failed("change of mind");

        try (MachineRegistry registry = registry()) {
            Throwable failure = TransitionFailureAssert.captureFailure(
                    () -> registry.of(PARCELS).execute(parcel, Delivery.SEND));
            TransitionFailureAssert.assertThat(failure).isAbort()
                    .at(PARCELS, parcel, ParcelStatus.HELD, Delivery.SEND, ParcelStatus.SENT)
                    .hasActionNamed("Send").hasCascadePathExactly(List.of("Parcel/ParcelStatus#p1"))
                    .journal().hasActionsExactly("Send").hasStatusesExactly(ActionStatus.FAILED);
            assertEquals(ParcelStatus.HELD, parcel.status());
        }
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private static final MachineKey<Parcel, ParcelStatus, Delivery> PARCELS =
            MachineKey.of(Parcel.class, ParcelStatus.class, Delivery.class);
    private static final MachineKey<Label, LabelStatus, Printing> LABELS =
            MachineKey.of(Label.class, LabelStatus.class, Printing.class);

    /** States of {@link Parcel}, the parent of the engine cases. */
    enum ParcelStatus { HELD, SENT, LOST }

    /** Transition identifiers of the parcel machine. */
    enum Delivery { SEND, LOSE }

    /** States of {@link Label}, the peer the parent cascades to. */
    enum LabelStatus { BLANK, PRINTED, VOID }

    /** Transition identifiers of the label machine. */
    enum Printing { PRINT, VOID }

    /** Parent entity of the engine cases, written by the machine and read back by the assertions. */
    @Getter(AccessLevel.PACKAGE)
    @AllArgsConstructor(access = AccessLevel.PACKAGE)
    static final class Parcel {

        private final String id;
        private ParcelStatus status;

        void setStatus(ParcelStatus status) { this.status = status; }
    }

    /** Peer entity of the engine cases; its setter can be instrumented to fail phase 2. */
    static final class Label {

        @Getter(AccessLevel.PACKAGE)
        private final String id;
        @Getter(AccessLevel.PACKAGE)
        private LabelStatus status;
        private RuntimeException onWrite;

        Label(String id, LabelStatus status) {
            this.id = id;
            this.status = status;
        }

        void failOnWrite(RuntimeException failure) { this.onWrite = failure; }

        void setStatus(LabelStatus status) {
            if (onWrite != null) {
                throw onWrite;
            }
            this.status = status;
        }
    }

    private TransitionAction<Parcel, ParcelStatus, Delivery> send =
            (TransitionContext<Parcel, ParcelStatus, Delivery> ctx) -> ActionOutcome.ok();
    private Consumer<Parcel> audit = (Parcel parcel) -> { };

    private MachineRegistry registry() {
        GraphDefinition<Parcel, ParcelStatus, Delivery> parcels = StateGraph.define(PARCELS)
                .initial(ParcelStatus.HELD).terminal(ParcelStatus.SENT).terminal(ParcelStatus.LOST)
                .statePolicy(ParcelStatus.HELD, "a held parcel is either sent or lost")
                .transition(Delivery.SEND, ParcelStatus.HELD, ParcelStatus.SENT)
                    .action("Send", (TransitionContext<Parcel, ParcelStatus, Delivery> ctx) -> send.process(ctx))
                    .afterCommit("audit", (Parcel parcel) -> audit.accept(parcel))
                    .endTransition()
                .transition(Delivery.LOSE, ParcelStatus.HELD, ParcelStatus.LOST).endTransition()
                .build();
        GraphDefinition<Label, LabelStatus, Printing> labels = StateGraph.define(LABELS)
                .initial(LabelStatus.BLANK).terminal(LabelStatus.PRINTED).terminal(LabelStatus.VOID)
                .statePolicy(LabelStatus.BLANK, "a blank label either prints or is voided")
                .transition(Printing.PRINT, LabelStatus.BLANK, LabelStatus.PRINTED).endTransition()
                .transition(Printing.VOID, LabelStatus.BLANK, LabelStatus.VOID).endTransition()
                .build();
        return MachineRegistry.builder()
                .add(StateMachines.from(parcels).state(Parcel::status, Parcel::setStatus).identity(Parcel::id))
                .add(StateMachines.from(labels).state(Label::status, Label::setStatus).identity(Label::id))
                .build();
    }

    private static final MachineKey<Doc, Phase, Step> DOCS = MachineKey.of(Doc.class, Phase.class, Step.class);

    /**
     * States of {@link Doc}; {@code DRAFT} and {@code REVIEW} each declare a watch that sends the document to
     * the other.
     */
    enum Phase { DRAFT, REVIEW, PUBLISHED, ARCHIVED }

    /** Transition identifiers of the document machine. */
    enum Step { SUBMIT, SEND_BACK, PUBLISH, ARCHIVE }

    /** Entity of the ping-pong machine. */
    @Getter(AccessLevel.PACKAGE)
    @AllArgsConstructor(access = AccessLevel.PACKAGE)
    static final class Doc {

        private final String id;
        private Phase phase;

        void setPhase(Phase phase) { this.phase = phase; }
    }

    /**
     * A document in DRAFT or REVIEW watches itself and always executes the edge to the other one, so every
     * commit triggers a reaction that commits again, and {@code maxWatchChain} is the only thing that ends it.
     */
    private static MachineRegistry pingPong(int maxWatchChain) {
        GraphDefinition<Doc, Phase, Step> graph = StateGraph.define(DOCS)
                .initial(Phase.DRAFT).terminal(Phase.ARCHIVED).allowsCycle(Phase.REVIEW)
                .statePolicy(Phase.DRAFT, "x").statePolicy(Phase.REVIEW, "x").statePolicy(Phase.PUBLISHED, "x")
                .transition(Step.SUBMIT, Phase.DRAFT, Phase.REVIEW).endTransition()
                .transition(Step.SEND_BACK, Phase.REVIEW, Phase.DRAFT).endTransition()
                .transition(Step.PUBLISH, Phase.REVIEW, Phase.PUBLISHED).endTransition()
                .transition(Step.ARCHIVE, Phase.PUBLISHED, Phase.ARCHIVED).endTransition()
                .watch(Phase.DRAFT, DOCS, (Doc doc) -> List.of(doc), (Doc doc) -> Optional.of(doc))
                    .when("always", (Doc doc, Collection<? extends Doc> peers, PeerView<Doc, Phase, Step> view) -> true)
                        .execute(Step.SUBMIT)
                    .endWatch()
                .watch(Phase.REVIEW, DOCS, (Doc doc) -> List.of(doc), (Doc doc) -> Optional.of(doc))
                    .when("always", (Doc doc, Collection<? extends Doc> peers, PeerView<Doc, Phase, Step> view) -> true)
                        .execute(Step.SEND_BACK)
                    .endWatch()
                .build();
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Doc::phase, Doc::setPhase).identity(Doc::id))
                .maxWatchChain(maxWatchChain)
                .build();
    }
}
