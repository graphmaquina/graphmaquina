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

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.WatchCondition;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.Sneaky;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Checked exceptions that callbacks throw without declaring them, as Kotlin, Groovy and Lombok's
 * {@code @SneakyThrows} compile to. Each one takes the route an unchecked exception from the same callback takes.
 * An {@code Error} is never routed: thrown by a callback it propagates unchanged, while one that completes a stage
 * is the cause of a technical abort.
 */
class CheckedExceptionTest {

    /** DRAFT -SUBMIT-> REVIEW, shaped by {@code submit}; DRAFT -DISCARD-> ARCHIVED is the route a failure can take. */
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

    private static StateMachine<Doc, DocStatus, DocTransition> standalone(GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        return StateMachines.from(graph).state(Doc::status, Doc::setStatus).asyncTimeout(Duration.ofSeconds(5)).build();
    }

    /**
     * Docs that watch docs. An observer in PUBLISHED, found through {@code ownerOf}, reacts to a doc that moves when
     * {@code rule} holds, and archives itself through {@code archive}.
     */
    private static MachineRegistry watching(Function<Doc, Optional<Doc>> ownerOf,
                                            WatchCondition<Doc, Doc, DocStatus, DocTransition> rule,
                                            TransitionAction<Doc, DocStatus, DocTransition> archive) {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).action("Archive", archive).endTransition()
                .watch(PUBLISHED, DOCS, (Doc doc) -> List.of(doc), ownerOf)
                    .when("rule", rule).execute(ARCHIVE)
                    .endWatch()
                .build();
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .build();
    }

    private static List<ActionStatus> statuses(List<ActionRecord> journal) {
        return journal.stream().map(ActionRecord::status).toList();
    }

    /**
     * Runs the calls one after the other on a fresh thread, as a pooled thread would, so that a marker a regression
     * leaks stays there. Each call ends as its outcome's type, or as the very throwable it threw.
     */
    private static List<Object> onOneThread(List<Supplier<Outcome<Doc, DocStatus, DocTransition>>> calls) throws Exception {
        try (ExecutorService thread = Executors.newSingleThreadExecutor()) {
            return thread.submit(() -> {
                List<Object> ends = new ArrayList<>();
                for (Supplier<Outcome<Doc, DocStatus, DocTransition>> call : calls) {
                    try {
                        ends.add(call.get().getClass().getSimpleName());
                    } catch (Throwable thrown) {
                        ends.add(thrown);
                    }
                }
                return ends;
            }).get();
        }
    }

    @Test
    void aCheckedExceptionListedInFailsOnTakesTheFailureRoute() {
        IOException upload = new IOException("upload failed");
        StateMachine<Doc, DocStatus, DocTransition> machine = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw Sneaky.raise(upload);
                        })
                        .failsOn(IOException.class).onFailure(DISCARD)));
        Doc doc = new Doc("1", DRAFT);
        Outcome.Failed<?, ?, ?> failed = assertInstanceOf(Outcome.Failed.class, machine.execute(doc, SUBMIT));
        assertSame(upload, failed.cause().orElseThrow());
        assertEquals("IOException: upload failed", failed.reason());
        assertEquals(ARCHIVED, doc.status());
    }

    @Test
    void aCheckedExceptionNotListedIsATechnicalAbortCarryingIt() {
        IOException upload = new IOException("upload failed");
        StateMachine<Doc, DocStatus, DocTransition> machine = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw Sneaky.raise(upload);
                        })));
        Doc doc = new Doc("1", DRAFT);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class, () -> machine.execute(doc, SUBMIT));
        assertSame(upload, error.getCause());
        assertEquals(List.of(ActionStatus.THREW), statuses(error.journal()));
        assertEquals(DRAFT, doc.status());
    }

    /** Thrown by process() itself, before any stage exists, it is classified like a stage that failed with it. */
    @Test
    void anAsynchronousActionThatThrowsACheckedExceptionTakesTheFailureRoute() {
        IOException upload = new IOException("upload failed");
        StateMachine<Doc, DocStatus, DocTransition> machine = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .asyncAction("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw Sneaky.raise(upload);
                        })
                        .failsOn(IOException.class).onFailure(DISCARD)));
        Doc doc = new Doc("1", DRAFT);
        Outcome.Failed<?, ?, ?> failed = assertInstanceOf(Outcome.Failed.class, machine.execute(doc, SUBMIT));
        assertSame(upload, failed.cause().orElseThrow());
        assertEquals(ARCHIVED, doc.status());
    }

    @Test
    void aGuardThatThrowsACheckedExceptionIsATechnicalAbort() {
        IOException quota = new IOException("quota service down");
        StateMachine<Doc, DocStatus, DocTransition> sync = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("CanSubmit", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw Sneaky.raise(quota);
                        })));
        StateMachine<Doc, DocStatus, DocTransition> async = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .asyncGuard("CanSubmit", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw Sneaky.raise(quota);
                        })));
        for (StateMachine<Doc, DocStatus, DocTransition> machine : List.of(sync, async)) {
            Doc doc = new Doc("1", DRAFT);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> machine.execute(doc, SUBMIT));
            assertSame(quota, error.getCause());
            assertEquals(DRAFT, doc.status());
        }
    }

    @Test
    void aHookThatThrowsACheckedExceptionIsAnAfterCommitException() {
        IOException mail = new IOException("mail server down");
        StateMachine<Doc, DocStatus, DocTransition> machine = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .afterCommit("Notify", (Doc doc) -> {
                            throw Sneaky.raise(mail);
                        })));
        Doc doc = new Doc("1", DRAFT);
        AfterCommitException error = assertThrows(AfterCommitException.class, () -> machine.execute(doc, SUBMIT));
        assertSame(mail, error.getCause());
        assertEquals("Notify", error.step());
        assertEquals(REVIEW, doc.status());
    }

    @Test
    void aListenerThatThrowsACheckedExceptionIsAnAfterCommitException() {
        IOException audit = new IOException("audit log down");
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(docs(UnaryOperator.<TransitionBuilder<Doc, DocStatus, DocTransition>>identity()))
                        .state(Doc::status, Doc::setStatus).identity(Doc::id))
                .listener((MachineKey<?, ?, ?> key, Outcome<?, ?, ?> outcome) -> {
                    throw Sneaky.raise(audit);
                })
                .build()) {
            Doc doc = new Doc("1", DRAFT);
            AfterCommitException error = assertThrows(AfterCommitException.class,
                    () -> registry.of(DOCS).execute(doc, SUBMIT));
            assertSame(audit, error.getCause());
            assertEquals("listener", error.step());
            assertEquals(REVIEW, doc.status());
        }
    }

    @Test
    void aSetterThatThrowsACheckedExceptionIsACommitFailure() {
        IOException flush = new IOException("flush failed");
        StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(
                        docs(UnaryOperator.<TransitionBuilder<Doc, DocStatus, DocTransition>>identity()))
                .state(Doc::status, (Doc doc, DocStatus status) -> {
                    throw Sneaky.raise(flush);
                })
                .build();
        Doc doc = new Doc("1", DRAFT);
        CommitFailedException error = assertThrows(CommitFailedException.class, () -> machine.execute(doc, SUBMIT));
        assertSame(flush, error.getCause());
        assertEquals(List.of(), error.applied());
        assertEquals(List.of(doc), error.pending());
        assertEquals(DRAFT, doc.status());
    }

    /** The call whose commit triggered the reaction returns normally: the reaction reports the failure. */
    @Test
    void aReactionWhoseActionThrowsACheckedExceptionIsAnAbortedReaction() {
        IOException approval = new IOException("approval service down");
        Doc observer = new Doc("observer", PUBLISHED);
        Doc watched = new Doc("watched", DRAFT);
        try (MachineRegistry registry = watching(
                (Doc moved) -> moved == watched ? Optional.of(observer) : Optional.<Doc>empty(),
                (Doc doc, Collection<? extends Doc> peers, PeerView<Doc, DocStatus, DocTransition> view) -> true,
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    throw Sneaky.raise(approval);
                })) {
            Outcome.Applied<?, ?, ?> applied = assertInstanceOf(Outcome.Applied.class,
                    registry.of(DOCS).execute(watched, SUBMIT));
            Reaction.Aborted aborted = assertInstanceOf(Reaction.Aborted.class, applied.reactions().get(0));
            TransitionAbortedException cause = assertInstanceOf(TransitionAbortedException.class, aborted.cause());
            assertSame(approval, cause.getCause());
            assertEquals(REVIEW, watched.status());
            assertEquals(PUBLISHED, observer.status());
        }
    }

    /**
     * A watch condition and an owner lookup run outside any transaction, so no TransitionAbortedException wraps what
     * they throw. A checked exception still ends as an aborted reaction, wrapped in UndeclaredThrowableException
     * because {@code Reaction.Aborted} carries a RuntimeException.
     */
    @Test
    void aWatchConditionOrOwnerLookupThatThrowsACheckedExceptionIsAnAbortedReaction() {
        IOException lookup = new IOException("directory down");
        Doc observer = new Doc("observer", PUBLISHED);
        Doc watched = new Doc("watched", DRAFT);
        TransitionAction<Doc, DocStatus, DocTransition> archive =
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ActionOutcome.ok();
        try (MachineRegistry condition = watching(
                (Doc moved) -> moved == watched ? Optional.of(observer) : Optional.<Doc>empty(),
                (Doc doc, Collection<? extends Doc> peers, PeerView<Doc, DocStatus, DocTransition> view) -> {
                    throw Sneaky.raise(lookup);
                }, archive);
             MachineRegistry owner = watching(
                (Doc moved) -> {
                    throw Sneaky.raise(lookup);
                },
                (Doc doc, Collection<? extends Doc> peers, PeerView<Doc, DocStatus, DocTransition> view) -> true,
                archive)) {
            for (MachineRegistry registry : List.of(condition, owner)) {
                watched.setStatus(DRAFT);
                Outcome.Applied<?, ?, ?> applied = assertInstanceOf(Outcome.Applied.class,
                        registry.of(DOCS).execute(watched, SUBMIT));
                Reaction.Aborted aborted = assertInstanceOf(Reaction.Aborted.class, applied.reactions().get(0));
                UndeclaredThrowableException cause = assertInstanceOf(UndeclaredThrowableException.class, aborted.cause());
                assertSame(lookup, cause.getUndeclaredThrowable());
                assertEquals(REVIEW, watched.status());
                assertEquals(PUBLISHED, observer.status());
            }
        }
    }

    /**
     * Pins the policy rather than a bug: an Error from a root callback is never routed nor wrapped, and the thread
     * that ran the root can run another one.
     */
    @Test
    void anErrorFromARootActionPropagatesUnchanged() {
        AssertionError broken = new AssertionError("invariant broken");
        StateMachine<Doc, DocStatus, DocTransition> machine = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            if (ctx.entity().id().equals("1")) {
                                throw broken;
                            }
                            return ActionOutcome.ok();
                        })
                        .failsOn(IllegalStateException.class).onFailure(DISCARD)));
        Doc doc = new Doc("1", DRAFT);
        assertSame(broken, assertThrows(AssertionError.class, () -> machine.execute(doc, SUBMIT)));
        assertEquals(DRAFT, doc.status());
        assertInstanceOf(Outcome.Applied.class, machine.execute(new Doc("2", DRAFT), SUBMIT));
    }

    /**
     * Pins the policy at hook depth: a hook runs after phase 2 and after the marker was released, so its Error
     * propagates unchanged, its own hop stays written, and the same thread runs the next transition.
     */
    @Test
    void anErrorFromAHookPropagatesUnchangedAfterTheCommit() throws Exception {
        AssertionError broken = new AssertionError("ledger invariant broken");
        Doc doc = new Doc("1", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> machine = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .afterCommit("Notify", (Doc committed) -> {
                            if (committed == doc) {
                                throw broken;
                            }
                        })));
        List<Object> ends = onOneThread(List.<Supplier<Outcome<Doc, DocStatus, DocTransition>>>of(
                () -> machine.execute(doc, SUBMIT), () -> machine.execute(new Doc("2", DRAFT), SUBMIT)));
        assertSame(broken, ends.get(0));
        assertEquals("Applied", ends.get(1));
        assertEquals(REVIEW, doc.status());
    }

    /** Pins the policy at listener depth: the same as for a hook, the listener being the last post-commit step. */
    @Test
    void anErrorFromTheListenerPropagatesUnchangedAfterTheCommit() throws Exception {
        AssertionError broken = new AssertionError("audit invariant broken");
        Doc doc = new Doc("1", DRAFT);
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(docs(UnaryOperator.<TransitionBuilder<Doc, DocStatus, DocTransition>>identity()))
                        .state(Doc::status, Doc::setStatus).identity(Doc::id))
                .listener((MachineKey<?, ?, ?> key, Outcome<?, ?, ?> outcome) -> {
                    if (outcome.entity() == doc) {
                        throw broken;
                    }
                })
                .build()) {
            StateMachine<Doc, DocStatus, DocTransition> docs = registry.of(DOCS);
            List<Object> ends = onOneThread(List.<Supplier<Outcome<Doc, DocStatus, DocTransition>>>of(
                    () -> docs.execute(doc, SUBMIT), () -> docs.execute(new Doc("2", DRAFT), SUBMIT)));
            assertSame(broken, ends.get(0));
            assertEquals("Applied", ends.get(1));
            assertEquals(REVIEW, doc.status());
        }
    }

    /**
     * Pins the policy at watch-condition depth: the reaction does not catch an Error, so it propagates unchanged out
     * of the call whose commit triggered it. That commit stays, the reaction writes nothing, and the same thread runs
     * the next transition.
     */
    @Test
    void anErrorFromAWatchConditionPropagatesUnchangedAndTheReactionWritesNothing() throws Exception {
        AssertionError broken = new AssertionError("rule invariant broken");
        Doc observer = new Doc("observer", PUBLISHED);
        Doc watched = new Doc("watched", DRAFT);
        try (MachineRegistry registry = watching(
                (Doc moved) -> moved == watched ? Optional.of(observer) : Optional.<Doc>empty(),
                (Doc doc, Collection<? extends Doc> peers, PeerView<Doc, DocStatus, DocTransition> view) -> {
                    throw broken;
                },
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ActionOutcome.ok())) {
            StateMachine<Doc, DocStatus, DocTransition> docs = registry.of(DOCS);
            List<Object> ends = onOneThread(List.<Supplier<Outcome<Doc, DocStatus, DocTransition>>>of(
                    () -> docs.execute(watched, SUBMIT), () -> docs.execute(new Doc("other", DRAFT), SUBMIT)));
            assertSame(broken, ends.get(0));
            assertEquals("Applied", ends.get(1));
            assertEquals(REVIEW, watched.status());
            assertEquals(PUBLISHED, observer.status());
        }
    }

    /**
     * Pins the documented asymmetry: the runtime learns of an Error that completes a stage only through the stage,
     * so it reports it as the cause of a technical abort.
     */
    @Test
    void anErrorThatCompletesAStageIsATechnicalAbort() {
        AssertionError broken = new AssertionError("invariant broken");
        StateMachine<Doc, DocStatus, DocTransition> machine = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .asyncAction("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                                CompletableFuture.failedFuture(broken))));
        Doc doc = new Doc("1", DRAFT);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class, () -> machine.execute(doc, SUBMIT));
        assertSame(broken, error.getCause());
        assertEquals(DRAFT, doc.status());
    }
}
