package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SEND_BACK;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.CascadeCycleException;
import io.github.graphmaquina.error.CascadeDepthExceededException;
import io.github.graphmaquina.error.CascadeException;
import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UnhandledFailureException;
import io.github.graphmaquina.error.WatchChainExceededException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Each kind of call failure says, as values, which call failed, where it was, how far it got in writing states and
 * which actions ran: an abort before the commit wrote nothing, a commit failure wrote none or part of the states, and
 * a failing hook found them all written and names the hop it ran for. Every row of the classification that this task
 * makes true runs through the real engine here: the guard and the action of the call's own edge, a cascade's cycle,
 * depth limit and COLLECT aggregate, and a chain of watch reactions over its limit.
 */
class CallFailureTest {

    private static GraphDefinition<Doc, DocStatus, DocTransition> docs(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit) {
        return submit.apply(StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                        .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                        .transition(SUBMIT, DRAFT, REVIEW))
                .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    private static StateMachine<Doc, DocStatus, DocTransition> standalone(
            GraphDefinition<Doc, DocStatus, DocTransition> graph, BiConsumer<Doc, DocStatus> setter) {
        return StateMachines.from(graph).state(Doc::status, setter).build();
    }

    private static List<String> actionsOf(List<ActionRecord> journal) {
        List<String> actions = new ArrayList<>();
        for (ActionRecord record : journal) {
            actions.add(record.action() + " " + record.status());
        }
        return actions;
    }

    /** DRAFT -SUBMIT-> REVIEW runs {@code submit}; REVIEW -PUBLISH-> PUBLISHED runs {@code publish}. */
    private static GraphDefinition<Doc, DocStatus, DocTransition> cascading(
            TransitionAction<Doc, DocStatus, DocTransition> submit,
            TransitionAction<Doc, DocStatus, DocTransition> publish) {
        return StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).action("Submit", submit).endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).action("Publish", publish).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    @Test
    void anAbortNamesItsCallItsPhaseAndTheActionsThatRan() {
        IllegalStateException boom = new IllegalStateException("boom");
        StateMachine<Doc, DocStatus, DocTransition> guarded = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("CanSubmit", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw boom;
                        })), Doc::setStatus);
        Doc first = new Doc("1", DRAFT);
        TransitionAbortedException guard = assertThrows(TransitionAbortedException.class,
                () -> guarded.execute(first, SUBMIT));
        assertEquals(DOCS, guard.call().key());
        assertSame(first, guard.call().entity());
        assertEquals(Optional.of(SUBMIT), guard.call().transition());
        assertFalse(guard.call().reaction());
        assertEquals(CallPhase.GUARD, guard.phase());
        assertEquals(StatesWritten.NONE, guard.statesWritten());
        assertEquals(List.of(), guard.journal());

        StateMachine<Doc, DocStatus, DocTransition> acting = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw boom;
                        })), Doc::setStatus);
        TransitionAbortedException action = assertThrows(TransitionAbortedException.class,
                () -> acting.execute(new Doc("2", DRAFT), SUBMIT));
        assertEquals(CallPhase.ACTION, action.phase());
        assertEquals(StatesWritten.NONE, action.statesWritten());
        assertEquals(List.of("Submit THREW"), actionsOf(action.journal()));
    }

    /**
     * A guard of the call's own edge that returns null, or whose stage outlives the call's deadline, aborts in
     * GUARD.
     */
    @Test
    void aGuardThatReturnsNullOrTimesOutAbortsTheCallInGuard() {
        StateMachine<Doc, DocStatus, DocTransition> returningNull = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("CanSubmit", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> null)),
                Doc::setStatus);
        Doc first = new Doc("1", DRAFT);
        TransitionAbortedException nullVerdict = assertThrows(TransitionAbortedException.class,
                () -> returningNull.execute(first, SUBMIT));
        assertEquals(CallPhase.GUARD, nullVerdict.phase());
        assertEquals(StatesWritten.NONE, nullVerdict.statesWritten());
        assertTrue(nullVerdict.getMessage().contains("the guard CanSubmit returned null"), nullVerdict.getMessage());
        assertEquals(DRAFT, first.status());

        StateMachine<Doc, DocStatus, DocTransition> neverAnswering = StateMachines.from(docs(
                        (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                                .asyncGuard("CanSubmit", (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                                        new CompletableFuture<GuardResult>())))
                .state(Doc::status, Doc::setStatus).asyncTimeout(Duration.ofMillis(100)).build();
        Doc second = new Doc("2", DRAFT);
        TransitionAbortedException timedOut = assertThrows(TransitionAbortedException.class,
                () -> neverAnswering.execute(second, SUBMIT));
        assertEquals(CallPhase.GUARD, timedOut.phase());
        assertEquals(StatesWritten.NONE, timedOut.statesWritten());
        assertInstanceOf(TimeoutException.class, timedOut.getCause());
        assertEquals(DRAFT, second.status());
    }

    /**
     * The call's own action that returns null, or whose stage outlives its edge's timeout, aborts in ACTION, and the
     * journal keeps that action: it ran.
     */
    @Test
    void anActionThatReturnsNullOrTimesOutAbortsTheCallInActionAndIsJournaled() {
        StateMachine<Doc, DocStatus, DocTransition> returningNull = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> null)),
                Doc::setStatus);
        Doc first = new Doc("1", DRAFT);
        TransitionAbortedException nullOutcome = assertThrows(TransitionAbortedException.class,
                () -> returningNull.execute(first, SUBMIT));
        assertEquals(CallPhase.ACTION, nullOutcome.phase());
        assertEquals(StatesWritten.NONE, nullOutcome.statesWritten());
        assertEquals(List.of("Submit THREW"), actionsOf(nullOutcome.journal()));
        assertTrue(nullOutcome.getMessage().contains("the action Submit returned null"), nullOutcome.getMessage());
        assertEquals(DRAFT, first.status());

        StateMachine<Doc, DocStatus, DocTransition> neverFinishing = StateMachines.from(docs(
                        (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                                .asyncAction("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                                        new CompletableFuture<ActionOutcome>())
                                .timeout(Duration.ofMillis(100))))
                .state(Doc::status, Doc::setStatus).asyncTimeout(Duration.ofSeconds(30)).build();
        Doc second = new Doc("2", DRAFT);
        TransitionAbortedException timedOut = assertThrows(TransitionAbortedException.class,
                () -> neverFinishing.execute(second, SUBMIT));
        assertEquals(CallPhase.ACTION, timedOut.phase());
        assertEquals(StatesWritten.NONE, timedOut.statesWritten());
        assertEquals(List.of("Submit THREW"), actionsOf(timedOut.journal()));
        assertInstanceOf(TimeoutException.class, timedOut.getCause());
        assertEquals(DRAFT, second.status());
    }

    /** A cascade cycle, the depth limit and COLLECT's aggregate fail the call in ACTION, as the subtypes they are. */
    @Test
    void aCascadeCycleTheDepthLimitAndACollectedAggregateFailTheCallInAction() {
        TransitionAction<Doc, DocStatus, DocTransition> ok =
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ActionOutcome.ok();

        Doc looping = new Doc("looping", DRAFT);
        CascadeCycleException cycle = assertThrows(CascadeCycleException.class,
                () -> standalone(cascading((TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    ctx.cascade(DOCS, ctx.entity(), PUBLISH);
                    return ActionOutcome.ok();
                }, ok), Doc::setStatus).execute(looping, SUBMIT));

        Doc deep = new Doc("deep", DRAFT);
        Doc second = new Doc("second", REVIEW);
        Doc third = new Doc("third", REVIEW);
        CascadeDepthExceededException depth;
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(cascading((TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    ctx.cascade(DOCS, second, PUBLISH);
                    return ActionOutcome.ok();
                }, (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    if (ctx.entity() == second) {
                        ctx.cascade(DOCS, third, PUBLISH);
                    }
                    return ActionOutcome.ok();
                })).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .maxCascadeDepth(1)
                .build()) {
            depth = assertThrows(CascadeDepthExceededException.class, () -> registry.of(DOCS).execute(deep, SUBMIT));
        }
        assertEquals(1, depth.maxDepth());

        Doc collecting = new Doc("collecting", DRAFT);
        Doc ready = new Doc("ready", REVIEW);
        Doc notReady = new Doc("not ready", DRAFT);
        CascadeException collected = assertThrows(CascadeException.class,
                () -> standalone(cascading((TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    ctx.cascadeAll(DOCS, List.of(ready, notReady), PUBLISH, CascadePolicy.COLLECT);
                    return ActionOutcome.ok();
                }, ok), Doc::setStatus).execute(collecting, SUBMIT));
        assertEquals(1, collected.errors().size());

        List<String> ends = new ArrayList<>();
        for (CallFailedException failure : List.<CallFailedException>of(cycle, depth, collected)) {
            ends.add(failure.getClass().getSimpleName() + " " + failure.phase() + " " + failure.statesWritten());
        }
        assertEquals(List.of("CascadeCycleException ACTION NONE", "CascadeDepthExceededException ACTION NONE",
                "CascadeException ACTION NONE"), ends);
        assertSame(looping, cycle.call().entity());
        assertSame(deep, depth.call().entity());
        assertSame(collecting, collected.call().entity());
        assertEquals(List.of(DRAFT, DRAFT, REVIEW, REVIEW, DRAFT, REVIEW, DRAFT),
                List.of(looping.status(), deep.status(), second.status(), third.status(), collecting.status(),
                        ready.status(), notReady.status()));
    }

    /**
     * A chain of watch reactions over its limit aborts the reaction one step past it, in RESOLVE and before it wrote
     * anything; the abort reaches the application only inside {@code Reaction.Aborted}.
     */
    @Test
    void aWatchChainOverItsLimitAbortsTheReactionInResolve() {
        GraphDefinition<Doc, DocStatus, DocTransition> pingPong = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(DRAFT, DOCS, (Doc doc) -> List.of(doc), (Doc moved) -> Optional.of(moved))
                    .when("always", (Doc doc, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> true).execute(SUBMIT)
                    .endWatch()
                .watch(REVIEW, DOCS, (Doc doc) -> List.of(doc), (Doc moved) -> Optional.of(moved))
                    .when("always", (Doc doc, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> true).execute(SEND_BACK)
                    .endWatch()
                .build();
        Doc doc = new Doc("d1", DRAFT);
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(pingPong).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .maxWatchChain(1)
                .build()) {
            Outcome.Applied<?, ?, ?> root = assertInstanceOf(Outcome.Applied.class,
                    registry.of(DOCS).execute(doc, SUBMIT));
            Reaction.Done first = assertInstanceOf(Reaction.Done.class, root.reactions().get(0));
            Outcome.Applied<?, ?, ?> sentBack = assertInstanceOf(Outcome.Applied.class, first.outcome());
            Reaction.Aborted second = assertInstanceOf(Reaction.Aborted.class, sentBack.reactions().get(0));
            WatchChainExceededException error = assertInstanceOf(WatchChainExceededException.class, second.cause());
            assertEquals(CallPhase.RESOLVE, error.phase());
            assertEquals(StatesWritten.NONE, error.statesWritten());
            assertSame(doc, error.call().entity());
            assertTrue(error.call().reaction());
            assertEquals(Optional.empty(), error.call().transition());
            assertEquals(1, error.maxChain());
        }
        assertEquals(DRAFT, doc.status());
    }

    @Test
    void anActionThatFailsWithoutARouteIsAnAbortInAction() {
        StateMachine<Doc, DocStatus, DocTransition> machine = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                                ActionOutcome.failed("no content"))), Doc::setStatus);
        UnhandledFailureException error = assertThrows(UnhandledFailureException.class,
                () -> machine.execute(new Doc("1", DRAFT), SUBMIT));
        assertEquals(CallPhase.ACTION, error.phase());
        assertEquals(StatesWritten.NONE, error.statesWritten());
        assertEquals(List.of("Submit FAILED"), actionsOf(error.journal()));
    }

    /** A guard of a cascaded peer fails the call in ACTION, the call's phase; the details describe the peer's step. */
    @Test
    void aGuardOfACascadedPeerFailsTheCallInAction() {
        IllegalStateException boom = new IllegalStateException("peer guard down");
        Doc peer = new Doc("peer", REVIEW);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        ctx.cascade(DOCS, peer, PUBLISH);
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED)
                    .guard("CanPublish", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                        throw boom;
                    })
                    .endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        Doc doc = new Doc("root", DRAFT);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> standalone(graph, Doc::setStatus).execute(doc, SUBMIT));
        assertEquals(CallPhase.ACTION, error.phase());
        assertSame(boom, error.getCause());
        assertSame(peer, error.details().entity());
        assertSame(doc, error.call().entity());
        assertEquals(List.of("Submit THREW"), actionsOf(error.journal()));
    }

    /** The action ran and the pre-check then refused the commit: nothing was written, but the journal remembers it. */
    @Test
    void aPreCheckConflictWritesNothingAndKeepsTheActionsThatRan() {
        StateMachine<Doc, DocStatus, DocTransition> machine = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.entity().setStatus(PUBLISHED);
                            return ActionOutcome.ok();
                        })), Doc::setStatus);
        Doc doc = new Doc("1", DRAFT);
        CommitFailedException error = assertThrows(CommitFailedException.class, () -> machine.execute(doc, SUBMIT));
        assertEquals(CallPhase.COMMIT, error.phase());
        assertEquals(StatesWritten.NONE, error.statesWritten());
        assertEquals(List.of(), error.applied());
        assertEquals(Optional.empty(), error.failedEntity());
        assertEquals(List.of(doc), error.pending());
        assertEquals(List.of("Submit OK"), actionsOf(error.journal()));
        assertSame(doc, error.call().entity());
    }

    @Test
    void aSetterThatThrowsLeavesTheStatesPartiallyWrittenAndNamesItsEntity() {
        IllegalStateException boom = new IllegalStateException("flush failed");
        StateMachine<Doc, DocStatus, DocTransition> machine = standalone(
                docs(UnaryOperator.<TransitionBuilder<Doc, DocStatus, DocTransition>>identity()),
                (Doc doc, DocStatus status) -> {
                    throw boom;
                });
        Doc doc = new Doc("1", DRAFT);
        CommitFailedException error = assertThrows(CommitFailedException.class, () -> machine.execute(doc, SUBMIT));
        assertEquals(StatesWritten.PARTIAL, error.statesWritten());
        assertEquals(List.of(), error.applied());
        assertEquals(Optional.of(doc), error.failedEntity());
        assertEquals(List.of(), error.pending());
        assertSame(boom, error.getCause());
        assertEquals("phase 2 failed: a setter threw " + boom + "; applied 0, failed 1, pending 0", error.getMessage());
    }

    /** The root's hook ran, the cascaded peer's hook threw: the failure names that hop and both committed hops. */
    @Test
    void aFailingHookNamesItsHopAndEveryHopTheCallCommitted() {
        IllegalStateException boom = new IllegalStateException("ledger down");
        Doc peer = new Doc("peer", REVIEW);
        List<String> hooks = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        ctx.cascade(DOCS, peer, PUBLISH);
                        return ActionOutcome.ok();
                    })
                    .afterCommit("Submitted", (Doc doc) -> hooks.add("submitted " + doc.id()))
                    .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED)
                    .afterCommit("Published", (Doc doc) -> {
                        throw boom;
                    })
                    .endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        Doc doc = new Doc("root", DRAFT);
        AfterCommitException error = assertThrows(AfterCommitException.class,
                () -> standalone(graph, Doc::setStatus).execute(doc, SUBMIT));
        assertEquals(CallPhase.HOOK, error.phase());
        assertEquals(StatesWritten.ALL, error.statesWritten());
        assertEquals("Published", error.hook());
        assertEquals(new TransitionRecord<>(DOCS, peer, REVIEW, PUBLISH, PUBLISHED, TransitionKind.MOVE,
                Optional.empty(), 1), error.hop());
        assertEquals(List.of(
                new TransitionRecord<>(DOCS, doc, DRAFT, SUBMIT, REVIEW, TransitionKind.ACTION,
                        Optional.of("Submit"), 0),
                new TransitionRecord<>(DOCS, peer, REVIEW, PUBLISH, PUBLISHED, TransitionKind.MOVE,
                        Optional.empty(), 1)),
                error.records());
        assertEquals(List.of("Submit OK"), actionsOf(error.journal()));
        assertEquals(List.of("submitted root"), hooks);
        assertEquals("the states of Doc/DocStatus SUBMIT are already written, but the hook 'Published' failed",
                error.getMessage());
    }

    @Test
    void theFailureOfAnObservedCallCarriesTheVeryCallItsObserversSaw() {
        List<MachineCall> started = new ArrayList<>();
        List<CallFailure> failures = new ArrayList<>();
        MachineObserver observer = new MachineObserver() {
            @Override
            public void onStarted(MachineCall call) {
                started.add(call);
            }

            @Override
            public void onFailed(MachineCall call, CallFailure failure, Duration elapsed) {
                failures.add(failure);
            }
        };
        IllegalStateException boom = new IllegalStateException("boom");
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                                .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                                    throw boom;
                                })))
                        .state(Doc::status, Doc::setStatus).identity(Doc::id))
                .observer(observer)
                .build()) {
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT));
            assertSame(started.get(0), error.call());
            assertSame(error, failures.get(0).error());
            assertEquals(error.phase(), failures.get(0).phase());
            assertEquals(error.statesWritten(), failures.get(0).statesWritten());
        }
    }

    /** Review Focus 4: the call was a reevaluation, whichever edge its watch selected and ran. */
    @Test
    void aReevaluationThatFailsInItsSelectedEdgeIsStillAReevaluation() {
        IllegalStateException boom = new IllegalStateException("archive refused");
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED)
                    .action("Archive", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        throw boom;
                    })
                    .endTransition()
                .watch(PUBLISHED, DOCS, (Doc doc) -> List.of(doc), (Doc moved) -> Optional.<Doc>empty())
                    .when("always", (Doc doc, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> true).execute(ARCHIVE)
                    .endWatch()
                .build();
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> standalone(graph, Doc::setStatus).reevaluate(new Doc("1", PUBLISHED)));
        assertEquals(Optional.empty(), error.call().transition());
        assertFalse(error.call().reaction());
        assertEquals(Optional.of(ARCHIVE), error.details().transition());
        assertEquals(CallPhase.ACTION, error.phase());
    }
}
