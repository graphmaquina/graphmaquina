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
import static io.github.graphmaquina.fixtures.Keys.ITEMS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatePolicy;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.SubmachineSpec;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.AmbiguousIdentityException;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.PoisonedUnitOfWorkException;
import io.github.graphmaquina.error.ReentrantTransitionException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UninitializedStateException;
import io.github.graphmaquina.error.UnknownPeerMachineException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.Item;
import io.github.graphmaquina.fixtures.ItemStatus;
import io.github.graphmaquina.fixtures.ItemTransition;
import io.github.graphmaquina.graph.DirectedGraph;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * What a call throws when a guard, an action or a hook lets an exception through. A failure of another call is the
 * cause of this call's own failure, never this call's failure itself. A failure of this call ends it with the failure
 * that poisoned it. What fails a cascade without being a failure of the call reaches the action as an abort of the call
 * that carries it.
 */
class PropagationTest {

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
            GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        return StateMachines.from(graph).state(Doc::status, Doc::setStatus).asyncTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Another machine whose SUBMIT fails in three ways: its action throws, its setter throws, its hook throws. */
    private static Map<String, StateMachine<Doc, DocStatus, DocTransition>> failingCalls() {
        IllegalStateException boom = new IllegalStateException("the other call failed");
        Map<String, StateMachine<Doc, DocStatus, DocTransition>> others = new LinkedHashMap<>();
        others.put("abort", standalone(docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    throw boom;
                }))));
        UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> plain = UnaryOperator.identity();
        others.put("commit", StateMachines.from(docs(plain))
                .state(Doc::status, (Doc doc, DocStatus status) -> {
                    throw boom;
                })
                .build());
        others.put("hook", standalone(docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .afterCommit("Notify", (Doc doc) -> {
                    throw boom;
                }))));
        return others;
    }

    @Test
    void aFailureOfAnotherCallIsTheCauseOfThisCallsAbort() {
        List<String> ends = new ArrayList<>();
        for (Map.Entry<String, StateMachine<Doc, DocStatus, DocTransition>> other : failingCalls().entrySet()) {
            Map<String, GraphDefinition<Doc, DocStatus, DocTransition>> joins = new LinkedHashMap<>();
            joins.put("action joining it", docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                    .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        other.getValue().async().execute(new Doc("other", DRAFT), SUBMIT).join();
                        return ActionOutcome.ok();
                    })));
            joins.put("async action completing with it", docs(
                    (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                            .asyncAction("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                                    other.getValue().async().execute(new Doc("other", DRAFT), SUBMIT)
                                            .thenApply((Outcome<Doc, DocStatus, DocTransition> outcome) ->
                                                    ActionOutcome.ok()))));
            joins.put("async guard completing with it", docs(
                    (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                            .asyncGuard("CanSubmit", (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                                    other.getValue().async().execute(new Doc("other", DRAFT), SUBMIT)
                                            .thenApply((Outcome<Doc, DocStatus, DocTransition> outcome) ->
                                                    GuardResult.pass()))));
            joins.put("guard joining it", docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                    .guard("CanSubmit", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                        other.getValue().async().execute(new Doc("other", DRAFT), SUBMIT).join();
                        return GuardResult.pass();
                    })));
            for (Map.Entry<String, GraphDefinition<Doc, DocStatus, DocTransition>> join : joins.entrySet()) {
                Doc doc = new Doc("1", DRAFT);
                TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                        () -> standalone(join.getValue()).execute(doc, SUBMIT));
                CallFailedException cause = assertInstanceOf(CallFailedException.class, error.getCause());
                assertSame(doc, error.call().entity());
                assertNotSame(error.call(), cause.call());
                ends.add(other.getKey() + ", " + join.getKey() + ": " + error.phase() + " caused by "
                        + cause.getClass().getSimpleName() + ", " + doc.status());
            }
        }
        assertEquals(List.of(
                "abort, action joining it: ACTION caused by TransitionAbortedException, DRAFT",
                "abort, async action completing with it: ACTION caused by TransitionAbortedException, DRAFT",
                "abort, async guard completing with it: GUARD caused by TransitionAbortedException, DRAFT",
                "abort, guard joining it: GUARD caused by TransitionAbortedException, DRAFT",
                "commit, action joining it: ACTION caused by CommitFailedException, DRAFT",
                "commit, async action completing with it: ACTION caused by CommitFailedException, DRAFT",
                "commit, async guard completing with it: GUARD caused by CommitFailedException, DRAFT",
                "commit, guard joining it: GUARD caused by CommitFailedException, DRAFT",
                "hook, action joining it: ACTION caused by AfterCommitException, DRAFT",
                "hook, async action completing with it: ACTION caused by AfterCommitException, DRAFT",
                "hook, async guard completing with it: GUARD caused by AfterCommitException, DRAFT",
                "hook, guard joining it: GUARD caused by AfterCommitException, DRAFT"), ends);
    }

    @Test
    void aFailedCascadeThatTheActionLetsThroughEndsTheCallAsItIs() {
        Doc peer = new Doc("peer", DRAFT);
        AtomicReference<RuntimeException> seen = new AtomicReference<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            try {
                                ctx.cascade(DOCS, peer, PUBLISH);
                            } catch (RuntimeException failure) {
                                seen.set(failure);
                                throw failure;
                            }
                            return ActionOutcome.ok();
                        })));
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(new Doc("1", DRAFT), SUBMIT));
        assertSame(seen.get(), error);
        assertEquals(CallPhase.ACTION, error.phase());
    }

    /** Unchanged: an action that replaces a failed cascade with an exception of its own aborts with that cause. */
    @Test
    void anActionThatReplacesAFailedCascadeWithItsOwnExceptionAbortsWithThatCause() {
        IllegalStateException own = new IllegalStateException("could not reserve");
        Doc peer = new Doc("peer", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> machine = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            try {
                                ctx.cascade(DOCS, peer, PUBLISH);
                            } catch (TransitionAbortedException failure) {
                                throw own;
                            }
                            return ActionOutcome.ok();
                        })));
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(new Doc("1", DRAFT), SUBMIT));
        assertSame(own, error.getCause());
    }

    @Test
    void aPoisonedUnitOfWorkExceptionNeverReachesTheCaller() {
        Doc peer = new Doc("peer", DRAFT);
        AtomicReference<RuntimeException> failed = new AtomicReference<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            try {
                                ctx.cascade(DOCS, peer, PUBLISH);
                            } catch (TransitionAbortedException failure) {
                                failed.set(failure);
                            }
                            ctx.peer(DOCS);
                            return ActionOutcome.ok();
                        })));
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(new Doc("1", DRAFT), SUBMIT));
        assertSame(failed.get(), error);
        assertFalse(error instanceof PoisonedUnitOfWorkException);
    }

    /** D10: a cascade refused for a reason that is not a failure of the call reaches the action as an abort of it. */
    @Test
    void aCascadeToAnUnknownMachineReachesTheActionAsAnAbortThatCarriesTheRefusal() {
        AtomicReference<RuntimeException> caught = new AtomicReference<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            try {
                                ctx.cascade(ITEMS, new Item("i1", ItemStatus.PACKING, Optional.empty()),
                                        ItemTransition.PACK);
                            } catch (RuntimeException failure) {
                                caught.set(failure);
                            }
                            return ActionOutcome.ok();
                        })));
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(new Doc("1", DRAFT), SUBMIT));
        assertSame(caught.get(), error);
        assertInstanceOf(UnknownPeerMachineException.class, error.getCause());
        assertEquals(CallPhase.ACTION, error.phase());
        assertTrue(error.getMessage().contains("the cascade to Item/ItemStatus failed: "), error.getMessage());
    }

    /** COLLECT collects the aborts of the call; an ambiguous identity is not one, so it stops the cascade at once. */
    @Test
    void collectStopsAtOnceOnAFailureThatIsNotAnAbortOfTheCall() {
        Doc first = new Doc("2", REVIEW);
        Doc twin = new Doc("2", REVIEW);
        Doc last = new Doc("3", REVIEW);
        List<String> published = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        ctx.cascadeAll(DOCS, List.of(first, twin, last), PUBLISH, CascadePolicy.COLLECT);
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED)
                    .action("Publish", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        published.add(ctx.entity().id());
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .build()) {
            Doc doc = new Doc("1", DRAFT);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(doc, SUBMIT));
            assertInstanceOf(AmbiguousIdentityException.class, error.getCause());
            assertEquals(CallPhase.ACTION, error.phase());
            assertEquals(List.of("2"), published);
            assertEquals(List.of(DRAFT, REVIEW, REVIEW, REVIEW),
                    List.of(doc.status(), first.status(), twin.status(), last.status()));
        }
    }

    /**
     * A peer whose state is null fails its cascade without being a failure of the call: the action receives an abort
     * of the call that carries the UninitializedStateException, the call ends in it in ACTION, and COLLECT stops at
     * once.
     */
    @Test
    void aNullPeerStateReachesTheActionAsAnAbortThatCarriesItAndStopsCollectAtOnce() {
        Doc unset = new Doc("unset", null);
        AtomicReference<RuntimeException> caught = new AtomicReference<>();
        StateMachine<Doc, DocStatus, DocTransition> single = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            try {
                                ctx.cascade(DOCS, unset, PUBLISH);
                            } catch (RuntimeException failure) {
                                caught.set(failure);
                                throw failure;
                            }
                            return ActionOutcome.ok();
                        })));
        Doc doc = new Doc("1", DRAFT);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> single.execute(doc, SUBMIT));
        assertSame(caught.get(), error);
        assertEquals(CallPhase.ACTION, error.phase());
        assertEquals(StatesWritten.NONE, error.statesWritten());
        assertInstanceOf(UninitializedStateException.class, error.getCause());
        assertTrue(error.getMessage().contains("the cascade to Doc/DocStatus failed: "), error.getMessage());
        assertEquals(DRAFT, doc.status());

        Doc last = new Doc("last", REVIEW);
        List<String> published = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> collecting = StateGraph.define(DOCS).initial(DRAFT)
                .terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        ctx.cascadeAll(DOCS, List.of(unset, last), PUBLISH, CascadePolicy.COLLECT);
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED)
                    .action("Publish", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        published.add(ctx.entity().id());
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        Doc root = new Doc("2", DRAFT);
        TransitionAbortedException collected = assertThrows(TransitionAbortedException.class,
                () -> standalone(collecting).execute(root, SUBMIT));
        assertEquals(CallPhase.ACTION, collected.phase());
        assertInstanceOf(UninitializedStateException.class, collected.getCause());
        assertEquals(List.of(), published);
        assertEquals(List.of(DRAFT, REVIEW), List.of(root.status(), last.status()));
    }

    /** A hook that joins a failing call of another machine fails as a hook of this call. */
    @Test
    void aHookThatJoinsAFailingCallFailsAsAHookOfThisCall() {
        IllegalStateException boom = new IllegalStateException("the other call failed");
        StateMachine<Doc, DocStatus, DocTransition> other = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw boom;
                        })));
        Doc elsewhere = new Doc("other", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> machine = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .afterCommit("Notify", (Doc doc) -> other.execute(elsewhere, SUBMIT))));
        Doc doc = new Doc("1", DRAFT);
        AfterCommitException error = assertThrows(AfterCommitException.class, () -> machine.execute(doc, SUBMIT));
        assertSame(doc, error.call().entity());
        TransitionAbortedException cause = assertInstanceOf(TransitionAbortedException.class, error.getCause());
        assertSame(elsewhere, cause.call().entity());
        assertSame(boom, cause.getCause());
        assertEquals(REVIEW, doc.status());
        assertEquals(DRAFT, elsewhere.status());
    }

    /** A recovery action that lets a failure of this call through ends the call with it, and its entry threw. */
    @Test
    void aRecoveryActionThatLetsAPeersAbortThroughEndsTheCallWithItAndThrew() {
        Doc peer = new Doc("peer", REVIEW);
        AtomicReference<RuntimeException> rethrown = new AtomicReference<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT)
                .terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                            ActionOutcome.failed("declined"))
                    .onFailure(DISCARD).endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED)
                    .action("Discard", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        try {
                            ctx.cascade(DOCS, peer, PUBLISH);
                        } catch (RuntimeException failure) {
                            rethrown.set(failure);
                            throw failure;
                        }
                        return ActionOutcome.ok();
                    }).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED)
                    .action("Publish", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        throw new IllegalStateException("could not publish");
                    }).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> standalone(graph).execute(new Doc("1", DRAFT), SUBMIT));
        assertSame(rethrown.get(), error);
        List<ActionStatus> discard = error.journal().stream()
                .filter((ActionRecord record) -> record.action().equals("Discard"))
                .map(ActionRecord::status).toList();
        assertEquals(List.of(ActionStatus.THREW), discard);
    }

    /** COLLECT stops at once on a nested failure that is not an abort of the call, as it does on a direct one. */
    @Test
    void collectStopsAtOnceOnANestedFailureThatIsNotAnAbortOfTheCall() {
        Doc unset = new Doc("unset", null);
        Doc first = new Doc("p1", REVIEW);
        Doc second = new Doc("p2", REVIEW);
        List<String> ran = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT)
                .terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        ctx.cascadeAll(DOCS, List.of(first, second), PUBLISH, CascadePolicy.COLLECT);
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED)
                    .action("Publish", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        if (ctx.entity().id().equals("p1")) {
                            ctx.cascade(DOCS, unset, PUBLISH);
                        }
                        ran.add(ctx.entity().id());
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> standalone(graph).execute(new Doc("1", DRAFT), SUBMIT));
        assertInstanceOf(UninitializedStateException.class, error.getCause());
        assertTrue(error.getMessage().contains("the cascade to Doc/DocStatus failed: "), error.getMessage());
        assertEquals(CallPhase.ACTION, error.phase());
        assertEquals(StatesWritten.NONE, error.statesWritten());
        assertEquals(List.of(), ran);
        assertEquals(REVIEW, second.status());
    }

    /** The failure that poisoned the call is what ends it, even when a nested action replaced it with its own. */
    @Test
    void theFailureThatPoisonedTheCallEndsItEvenWhenANestedActionReplacedIt() {
        Doc first = new Doc("p1", REVIEW);
        Doc second = new Doc("p2", PUBLISHED);
        AtomicReference<RuntimeException> caught = new AtomicReference<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT)
                .terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        ctx.cascade(DOCS, first, PUBLISH);
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED)
                    .action("Publish", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        try {
                            ctx.cascade(DOCS, second, ARCHIVE);
                        } catch (TransitionAbortedException failure) {
                            caught.set(failure);
                            throw new IllegalArgumentException("replaced");
                        }
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED)
                    .action("Archive", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        throw new IllegalStateException("could not archive");
                    })
                    .endTransition()
                .build();
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> standalone(graph).execute(new Doc("1", DRAFT), SUBMIT));
        assertSame(caught.get(), error);
        assertInstanceOf(IllegalStateException.class, error.getCause());
    }

    /**
     * The engine, not only the DSL, keeps a library exception out of failsOn: an edge built by hand that lists
     * RuntimeException still aborts the call with the ReentrantTransitionException as cause.
     */
    @Test
    void aLibraryExceptionNeverTakesAFailureRouteEvenWhenAHandBuiltEdgeListsItsSupertype() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> machine = new AtomicReference<>();
        GraphDefinition<Doc, DocStatus, DocTransition> dsl = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            machine.get().execute(new Doc("other", DRAFT), SUBMIT);
                            return ActionOutcome.ok();
                        })
                        .onFailure(DISCARD));
        machine.set(standalone(new ListingRuntimeException<>(dsl)));
        Doc doc = new Doc("1", DRAFT);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> machine.get().execute(doc, SUBMIT));
        assertEquals(CallPhase.ACTION, error.phase());
        assertInstanceOf(ReentrantTransitionException.class, error.getCause());
        assertEquals(List.of(ActionStatus.THREW), error.journal().stream().map(ActionRecord::status).toList());
        assertEquals(DRAFT, doc.status());
    }

    /** A definition whose edges are the delegate's, except that every action's failsOn is RuntimeException. */
    private static final class ListingRuntimeException<E, S extends Enum<S>, T extends Enum<T>>
            implements GraphDefinition<E, S, T> {

        private final GraphDefinition<E, S, T> delegate;

        private ListingRuntimeException(GraphDefinition<E, S, T> delegate) {
            this.delegate = delegate;
        }

        private TransitionSpec<E, S, T> rebuilt(TransitionSpec<E, S, T> spec) {
            if (spec.action().isEmpty()) {
                return spec;
            }
            return new TransitionSpec<>(spec.from(), spec.transition(), spec.to(), spec.action(), spec.guards(),
                    spec.onFailure(), List.of(RuntimeException.class), spec.timeout(), spec.afterCommit());
        }

        @Override
        public MachineKey<E, S, T> key() {
            return delegate.key();
        }

        @Override
        public S initial() {
            return delegate.initial();
        }

        @Override
        public Set<S> terminals() {
            return delegate.terminals();
        }

        @Override
        public DirectedGraph<S, T> graph() {
            return delegate.graph();
        }

        @Override
        public List<TransitionSpec<E, S, T>> transitions() {
            return delegate.transitions().stream().map(this::rebuilt).toList();
        }

        @Override
        public List<TransitionSpec<E, S, T>> outgoingFrom(S state) {
            return delegate.outgoingFrom(state).stream().map(this::rebuilt).toList();
        }

        @Override
        public Optional<TransitionSpec<E, S, T>> findTransition(S from, T transition) {
            return delegate.findTransition(from, transition).map(this::rebuilt);
        }

        @Override
        public StatePolicy<S, T> policy(S state) {
            return delegate.policy(state);
        }

        @Override
        public Optional<WatchSpec<E, S, T, ?, ?, ?>> watch(S state) {
            return delegate.watch(state);
        }

        @Override
        public Optional<SubmachineSpec<E, S, T, ?, ?, ?>> submachine(S state) {
            return delegate.submachine(state);
        }

        @Override
        public boolean hasAsync() {
            return delegate.hasAsync();
        }
    }
}
