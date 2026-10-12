package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UninitializedStateException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Failures before the entity's state was read, while a reevaluation evaluates its watch, and before the commit's first
 * setter. Each one ends the call in a failure of the call, an abort in RESOLVE or a commit failure with nothing
 * written, which carries what was thrown as its cause, never in what was thrown.
 */
class ResolveFailureTest {

    private static GraphDefinition<Doc, DocStatus, DocTransition> plain() {
        return StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    /** A doc in PUBLISHED watches itself and archives itself when its rule holds; {@code failing} picks what throws. */
    private static StateMachine<Doc, DocStatus, DocTransition> watching(String failing, RuntimeException boom) {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(PUBLISHED, DOCS, (Doc doc) -> {
                    if (failing.equals("peersOf")) {
                        throw boom;
                    }
                    return List.of(doc);
                }, (Doc moved) -> Optional.<Doc>empty())
                    .when("rule", (Doc doc, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> {
                        if (failing.equals("condition")) {
                            throw boom;
                        }
                        return false;
                    }).execute(ARCHIVE)
                    .endWatch()
                .build();
        return StateMachines.from(graph).state((Doc doc) -> {
            if (failing.equals("getter")) {
                throw boom;
            }
            return doc.status();
        }, Doc::setStatus).build();
    }

    @Test
    void anIdentityThatThrowsOrReturnsNullAbortsTheCallBeforeItsStateIsRead() {
        IllegalStateException boom = new IllegalStateException("no id");
        AtomicBoolean nullIdentity = new AtomicBoolean();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(plain()).state(Doc::status, Doc::setStatus).identity((Doc doc) -> {
                    if (nullIdentity.get()) {
                        return null;
                    }
                    throw boom;
                }))
                .build()) {
            Doc doc = new Doc("1", DRAFT);
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(doc, SUBMIT));
            assertSame(boom, thrown.getCause());
            assertEquals(CallPhase.RESOLVE, thrown.phase());
            assertEquals(Optional.empty(), thrown.from());
            assertEquals(Optional.of(SUBMIT), thrown.transition());
            assertEquals(Optional.empty(), thrown.to());
            assertSame(doc, thrown.call().entity());
            assertEquals("transition aborted in Doc/DocStatus ? -SUBMIT-> ?: reading the entity threw " + boom,
                    thrown.getMessage());
            nullIdentity.set(true);
            TransitionAbortedException absent = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(doc, SUBMIT));
            NullPointerException cause = assertInstanceOf(NullPointerException.class, absent.getCause());
            assertEquals("identity of Doc/DocStatus returned null", cause.getMessage());
            assertEquals(DRAFT, doc.status());
        }
    }

    @Test
    void aGetterThatThrowsOrANullStateAbortsTheCallBeforeItsStateIsRead() {
        IllegalStateException boom = new IllegalStateException("row locked");
        StateMachine<Doc, DocStatus, DocTransition> throwing = StateMachines.from(plain())
                .state((Doc doc) -> {
                    throw boom;
                }, Doc::setStatus)
                .build();
        TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                () -> throwing.execute(new Doc("1", DRAFT), SUBMIT));
        assertSame(boom, thrown.getCause());
        assertEquals(CallPhase.RESOLVE, thrown.phase());
        assertEquals(Optional.empty(), thrown.from());
        StateMachine<Doc, DocStatus, DocTransition> machine =
                StateMachines.from(plain()).state(Doc::status, Doc::setStatus).build();
        TransitionAbortedException uninitialized = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(new Doc("2", null), SUBMIT));
        assertInstanceOf(UninitializedStateException.class, uninitialized.getCause());
        assertEquals(CallPhase.RESOLVE, uninitialized.phase());
    }

    @Test
    void aReevaluationThatCannotReadItsStateOrEvaluateItsWatchAbortsInResolve() {
        IllegalStateException boom = new IllegalStateException("index down");
        List<String> ends = new ArrayList<>();
        for (String failing : List.of("getter", "peersOf", "condition")) {
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> watching(failing, boom).reevaluate(new Doc("1", PUBLISHED)));
            assertSame(boom, error.getCause());
            assertEquals(Optional.empty(), error.call().transition());
            ends.add(failing + ": " + error.phase() + ", from " + error.from() + ", " + error.statesWritten());
        }
        assertEquals(List.of(
                "getter: RESOLVE, from Optional.empty, NONE",
                "peersOf: RESOLVE, from Optional[PUBLISHED], NONE",
                "condition: RESOLVE, from Optional[PUBLISHED], NONE"), ends);
    }

    /** A condition that joins another machine's failing call aborts the reevaluation in RESOLVE. */
    @Test
    void aConditionThatJoinsAFailingCallAbortsTheReevaluationInResolve() {
        IllegalStateException boom = new IllegalStateException("the other call failed");
        GraphDefinition<Doc, DocStatus, DocTransition> failingSubmit = StateGraph.define(DOCS).initial(DRAFT)
                .terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        throw boom;
                    })
                    .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        StateMachine<Doc, DocStatus, DocTransition> other =
                StateMachines.from(failingSubmit).state(Doc::status, Doc::setStatus).build();
        Doc elsewhere = new Doc("other", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(PUBLISHED, DOCS, (Doc doc) -> List.of(doc), (Doc moved) -> Optional.<Doc>empty())
                    .when("joins", (Doc doc, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> {
                        other.async().execute(elsewhere, SUBMIT).join();
                        return true;
                    }).execute(ARCHIVE)
                    .endWatch()
                .build();
        StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(graph)
                .state(Doc::status, Doc::setStatus).asyncTimeout(Duration.ofSeconds(5)).build();
        Doc doc = new Doc("1", PUBLISHED);
        TransitionAbortedException error =
                assertThrows(TransitionAbortedException.class, () -> machine.reevaluate(doc));
        assertEquals(CallPhase.RESOLVE, error.phase());
        assertSame(doc, error.call().entity());
        TransitionAbortedException cause = assertInstanceOf(TransitionAbortedException.class, error.getCause());
        assertSame(elsewhere, cause.call().entity());
        assertEquals(PUBLISHED, doc.status());
    }

    @Test
    void aGetterThatThrowsBeforeTheFirstSetterWritesNothingAndListsEveryEntityAsPending() {
        IllegalStateException boom = new IllegalStateException("row locked");
        AtomicInteger reads = new AtomicInteger();
        StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(plain()).state((Doc doc) -> {
            if (reads.incrementAndGet() == 2) {
                throw boom;
            }
            return doc.status();
        }, Doc::setStatus).build();
        Doc doc = new Doc("1", DRAFT);
        CommitFailedException error = assertThrows(CommitFailedException.class, () -> machine.execute(doc, SUBMIT));
        assertSame(boom, error.getCause());
        assertEquals(CallPhase.COMMIT, error.phase());
        assertEquals(StatesWritten.NONE, error.statesWritten());
        assertEquals(List.of(doc), error.pending());
        assertEquals("phase 2 failed: preparing the commit threw " + boom + "; applied 0, pending 1",
                error.getMessage());
        assertEquals(DRAFT, doc.status());
    }

    /** An identity whose hashCode throws once {@code armed}: the grouping of the log is where it is first called. */
    private static final class Key {

        private final String name;
        private final AtomicBoolean armed;
        private final RuntimeException boom;

        Key(String name, AtomicBoolean armed, RuntimeException boom) {
            this.name = name;
            this.armed = armed;
            this.boom = boom;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key that && that.name.equals(name);
        }

        @Override
        public int hashCode() {
            if (armed.get()) {
                throw boom;
            }
            return name.hashCode();
        }
    }

    /**
     * A root whose action cascades PUBLISH to {@code a}, then ARCHIVE to {@code a} again, then PUBLISH to {@code b}:
     * three entities, one of them logged twice, and {@code armed} set when the last cascade returned, so that what
     * fails does so only once the commit starts to prepare.
     */
    private static GraphDefinition<Doc, DocStatus, DocTransition> threeEntities(Doc a, Doc b, AtomicBoolean armed) {
        return StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        ctx.cascade(DOCS, a, PUBLISH);
                        ctx.cascade(DOCS, a, ARCHIVE);
                        ctx.cascade(DOCS, b, PUBLISH);
                        armed.set(true);
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    /**
     * The grouping of the log fails before the first setter: the entities that were planned are pending, each once,
     * by reference and in order of first appearance, although the identities cannot be hashed any more.
     */
    @Test
    void aGroupingThatFailsListsEveryPlannedEntityOnceInOrderOfFirstAppearance() {
        IllegalStateException boom = new IllegalStateException("hashCode failed");
        AtomicBoolean armed = new AtomicBoolean();
        Doc root = new Doc("root", DRAFT);
        Doc a = new Doc("a", REVIEW);
        Doc b = new Doc("b", REVIEW);
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(threeEntities(a, b, armed)).state(Doc::status, Doc::setStatus)
                        .identity((Doc doc) -> new Key(doc.id(), armed, boom)))
                .build()) {
            CommitFailedException error = assertThrows(CommitFailedException.class,
                    () -> registry.of(DOCS).execute(root, SUBMIT));
            assertSame(boom, error.getCause());
            assertEquals(CallPhase.COMMIT, error.phase());
            assertEquals(StatesWritten.NONE, error.statesWritten());
            assertEquals(Optional.empty(), error.failedEntity());
            assertEquals(3, error.pending().size());
            assertSame(root, error.pending().get(0));
            assertSame(a, error.pending().get(1));
            assertSame(b, error.pending().get(2));
        }
        assertEquals(List.of(DRAFT, REVIEW, REVIEW), List.of(root.status(), a.status(), b.status()));
    }

    /** The same three entities when the state read of the check fails instead: pending, by reference, in order. */
    @Test
    void aCheckThatFailsListsEveryPlannedEntityOnceInOrderOfFirstAppearance() {
        IllegalStateException boom = new IllegalStateException("row locked");
        AtomicBoolean armed = new AtomicBoolean();
        Doc root = new Doc("root", DRAFT);
        Doc a = new Doc("a", REVIEW);
        Doc b = new Doc("b", REVIEW);
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(threeEntities(a, b, armed)).state((Doc doc) -> {
                    if (armed.get()) {
                        throw boom;
                    }
                    return doc.status();
                }, Doc::setStatus).identity(Doc::id))
                .build()) {
            CommitFailedException error = assertThrows(CommitFailedException.class,
                    () -> registry.of(DOCS).execute(root, SUBMIT));
            assertSame(boom, error.getCause());
            assertEquals(CallPhase.COMMIT, error.phase());
            assertEquals(StatesWritten.NONE, error.statesWritten());
            assertEquals(Optional.empty(), error.failedEntity());
            assertEquals(3, error.pending().size());
            assertSame(root, error.pending().get(0));
            assertSame(a, error.pending().get(1));
            assertSame(b, error.pending().get(2));
        }
        assertEquals(List.of(DRAFT, REVIEW, REVIEW), List.of(root.status(), a.status(), b.status()));
    }

    /** Stages of a {@link Ticket}. */
    enum Stage { OPEN, WORKING, CLOSED }

    /** Transitions of the stage of a {@link Ticket}. */
    enum StageStep { START, CLOSE }

    /** Reviews of a {@link Ticket}. */
    enum Review { PENDING, DONE }

    /** The one transition of the review of a {@link Ticket}. */
    enum ReviewStep { APPROVE }

    /** An entity two machines govern, one its stage and one its review, whose equals and hashCode throw. */
    private static final class Ticket {

        private final String id;
        private Stage stage = Stage.OPEN;
        private Review review = Review.PENDING;

        Ticket(String id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object other) {
            throw new UnsupportedOperationException("a ticket is never compared");
        }

        @Override
        public int hashCode() {
            throw new UnsupportedOperationException("a ticket is never hashed");
        }

        @Override
        public String toString() {
            return "Ticket#" + id;
        }
    }

    /**
     * The state read of the check fails for entities that two machines govern, whose own equals and hashCode throw:
     * the planned entities are told apart by machine and by reference, so a ticket moved by both machines is pending
     * once for each, a ticket moved twice by one machine once for it, and none of them is compared or hashed.
     */
    @Test
    void aCheckThatFailsListsEachEntityOnceForEachMachineThatMovesItWithoutComparingIt() {
        IllegalStateException boom = new IllegalStateException("row locked");
        AtomicBoolean armed = new AtomicBoolean();
        MachineKey<Ticket, Stage, StageStep> stages = MachineKey.of(Ticket.class, Stage.class, StageStep.class);
        MachineKey<Ticket, Review, ReviewStep> reviews = MachineKey.of(Ticket.class, Review.class, ReviewStep.class);
        Ticket root = new Ticket("t1");
        Ticket peer = new Ticket("t2");
        GraphDefinition<Ticket, Stage, StageStep> stageGraph = StateGraph.define(stages).initial(Stage.OPEN)
                .terminal(Stage.CLOSED).statePolicy(Stage.OPEN, "x").statePolicy(Stage.WORKING, "x")
                .transition(StageStep.START, Stage.OPEN, Stage.WORKING)
                    .action("Start", (TransitionContext<Ticket, Stage, StageStep> ctx) -> {
                        if (ctx.entity() == root) {
                            ctx.cascade(stages, peer, StageStep.START);
                            ctx.cascade(reviews, root, ReviewStep.APPROVE);
                            ctx.cascade(stages, peer, StageStep.CLOSE);
                            ctx.cascade(reviews, peer, ReviewStep.APPROVE);
                            armed.set(true);
                        }
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(StageStep.CLOSE, Stage.WORKING, Stage.CLOSED).endTransition()
                .build();
        GraphDefinition<Ticket, Review, ReviewStep> reviewGraph = StateGraph.define(reviews).initial(Review.PENDING)
                .terminal(Review.DONE).statePolicy(Review.PENDING, "x")
                .transition(ReviewStep.APPROVE, Review.PENDING, Review.DONE).endTransition()
                .build();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(stageGraph).state((Ticket ticket) -> {
                    if (armed.get()) {
                        throw boom;
                    }
                    return ticket.stage;
                }, (Ticket ticket, Stage stage) -> ticket.stage = stage).identity((Ticket ticket) -> ticket.id))
                .add(StateMachines.from(reviewGraph)
                        .state((Ticket ticket) -> ticket.review,
                                (Ticket ticket, Review review) -> ticket.review = review)
                        .identity((Ticket ticket) -> ticket.id))
                .build()) {
            CommitFailedException error = assertThrows(CommitFailedException.class,
                    () -> registry.of(stages).execute(root, StageStep.START));
            assertSame(boom, error.getCause());
            assertEquals(CallPhase.COMMIT, error.phase());
            assertEquals(StatesWritten.NONE, error.statesWritten());
            assertEquals(4, error.pending().size());
            assertSame(root, error.pending().get(0));
            assertSame(peer, error.pending().get(1));
            assertSame(root, error.pending().get(2));
            assertSame(peer, error.pending().get(3));
        }
        assertEquals(List.of(Stage.OPEN, Stage.OPEN), List.of(root.stage, peer.stage));
        assertEquals(List.of(Review.PENDING, Review.PENDING), List.of(root.review, peer.review));
    }

    /** A passing guard arms the grouping callback without adding an action to the journal. */
    private static GraphDefinition<Doc, DocStatus, DocTransition> guardArmed(Runnable arm, AtomicInteger hooks) {
        return StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .guard("Arm", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                        arm.run();
                        return GuardResult.pass();
                    })
                    .afterCommit("Audit", (Doc doc) -> hooks.incrementAndGet())
                    .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    /** Action-free grouping preserves the thrown object and the pending root before any setter or hook. */
    @Test
    void aGuardArmedGroupingHashFailureWritesNothingAndRetainsItsCause() {
        IllegalStateException boom = new IllegalStateException("grouping hash failed");
        AtomicBoolean armed = new AtomicBoolean();
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger hooks = new AtomicInteger();
        Doc root = new Doc("root", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(guardArmed(() -> armed.set(true), hooks))
                .state(Doc::status, (Doc doc, DocStatus state) -> {
                    writes.incrementAndGet();
                    doc.setStatus(state);
                })
                .identity((Doc doc) -> new Key(doc.id(), armed, boom))
                .build();
        CommitFailedException error = assertThrows(CommitFailedException.class, () -> machine.execute(root, SUBMIT));
        assertSame(boom, error.getCause());
        assertEquals(CallPhase.COMMIT, error.phase());
        assertEquals(StatesWritten.NONE, error.statesWritten());
        assertEquals(List.of(), error.journal());
        assertEquals(List.of(), error.applied());
        assertEquals(Optional.empty(), error.failedEntity());
        assertEquals(1, error.pending().size());
        assertSame(root, error.pending().get(0));
        assertSame(root, error.call().entity());
        assertEquals(DRAFT, root.status());
        assertEquals(0, writes.get());
        assertEquals(0, hooks.get());
    }

    /** A stable identity whose armed one-shot hash callback can throw, mutate state or use a saved context. */
    private static final class HashCallbackKey {

        private final String name;
        private Runnable onHash = () -> { };

        HashCallbackKey(String name) {
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof HashCallbackKey that && that.name.equals(name);
        }

        @Override
        public int hashCode() {
            Runnable callback = onHash;
            onHash = () -> { };
            callback.run();
            return name.hashCode();
        }
    }

    /** An Error armed by a passing guard escapes grouping unchanged before the first write. */
    @Test
    void aGuardArmedGroupingHashErrorPropagatesBeforeAnyWrite() {
        AssertionError boom = new AssertionError("grouping hash broken");
        HashCallbackKey identity = new HashCallbackKey("root");
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger hooks = new AtomicInteger();
        Doc root = new Doc("root", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(guardArmed(() -> {
            identity.onHash = () -> { throw boom; };
        }, hooks)).state(Doc::status, (Doc doc, DocStatus state) -> {
            writes.incrementAndGet();
            doc.setStatus(state);
        }).identity((Doc doc) -> identity).build();
        assertSame(boom, assertThrows(AssertionError.class, () -> machine.execute(root, SUBMIT)));
        assertEquals(DRAFT, root.status());
        assertEquals(0, writes.get());
        assertEquals(0, hooks.get());
    }

    /** The grouping hash runs before the conflict read, so its domain mutation is refused before setters. */
    @Test
    void aGuardArmedGroupingHashStateMutationIsSeenByTheConflictCheck() {
        HashCallbackKey identity = new HashCallbackKey("root");
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger hooks = new AtomicInteger();
        Doc root = new Doc("root", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(guardArmed(() -> {
            identity.onHash = () -> root.setStatus(PUBLISHED);
        }, hooks)).state(Doc::status, (Doc doc, DocStatus state) -> {
            writes.incrementAndGet();
            doc.setStatus(state);
        }).identity((Doc doc) -> identity).build();
        CommitFailedException error = assertThrows(CommitFailedException.class, () -> machine.execute(root, SUBMIT));
        assertEquals(CallPhase.COMMIT, error.phase());
        assertEquals(StatesWritten.NONE, error.statesWritten());
        assertEquals(Optional.empty(), Optional.ofNullable(error.getCause()));
        assertTrue(error.getMessage().contains("expected DRAFT but is PUBLISHED"), error.getMessage());
        assertEquals(List.of(), error.journal());
        assertEquals(List.of(), error.applied());
        assertEquals(Optional.empty(), error.failedEntity());
        assertEquals(1, error.pending().size());
        assertSame(root, error.pending().get(0));
        assertEquals(PUBLISHED, root.status());
        assertEquals(0, writes.get());
        assertEquals(0, hooks.get());
    }

    /** Grouping refuses a retained context's cascade before it can add a peer to the planned changes. */
    @Test
    void anActionArmedGroupingHashCascadeIsRefusedBeforeItStagesThePeer() {
        HashCallbackKey identity = new HashCallbackKey("root");
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger hooks = new AtomicInteger();
        Doc root = new Doc("root", DRAFT);
        Doc peer = new Doc("peer", REVIEW);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        identity.onHash = () -> ctx.cascade(DOCS, peer, PUBLISH);
                        return ActionOutcome.ok();
                    })
                    .afterCommit("Audit", (Doc doc) -> hooks.incrementAndGet())
                    .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED)
                    .afterCommit("Audit", (Doc doc) -> hooks.incrementAndGet()).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Doc::status, (Doc doc, DocStatus state) -> {
                    writes.incrementAndGet();
                    doc.setStatus(state);
                }).identity((Doc doc) -> doc == root ? identity : doc.id()))
                .build()) {
            CommitFailedException error = assertThrows(CommitFailedException.class,
                    () -> registry.of(DOCS).execute(root, SUBMIT));
            assertInstanceOf(IllegalStateException.class, error.getCause());
            assertEquals(CallPhase.COMMIT, error.phase());
            assertEquals(StatesWritten.NONE, error.statesWritten());
            assertEquals(List.of(), error.applied());
            assertEquals(Optional.empty(), error.failedEntity());
            assertEquals(1, error.pending().size());
            assertSame(root, error.pending().get(0));
            assertEquals(1, error.journal().size());
            assertEquals("Submit", error.journal().get(0).action());
            assertEquals(ActionStatus.OK, error.journal().get(0).status());
        }
        assertEquals(DRAFT, root.status());
        assertEquals(REVIEW, peer.status());
        assertEquals(0, writes.get());
        assertEquals(0, hooks.get());
    }
}
