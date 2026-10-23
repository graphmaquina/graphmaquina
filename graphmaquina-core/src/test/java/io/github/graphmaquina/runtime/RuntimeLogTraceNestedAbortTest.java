package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocMachines.registry;
import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SEND_BACK;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static io.github.graphmaquina.fixtures.LogLines.lines;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.GatewayException;
import io.github.graphmaquina.fixtures.LibraryLog;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;

/**
 * The TRACE lines of the engine's own abort of a nested step, read through {@code java.util.logging} at
 * {@code FINER} like those of {@link RuntimeLogTraceTest}. A nested action that the engine aborts, for a null outcome,
 * a null stage or a wait that ended, traces its end and then the poison of its step, at the depth of that step; a
 * nested guard whose wait ended traces that poison without a verdict; and an action that gave up after catching an
 * Error of its own cascade traces no poison for the engine's abort, which finds the call poisoned already.
 */
class RuntimeLogTraceNestedAbortTest {

    /**
     * The engine's own abort of a nested action step, for a null outcome, a null stage, a wait that timed out, a wait
     * that an interrupt ended or a wait that the thread's ForkJoinPool refused, and of a nested recovery action that
     * returned null, traces the action's THREW line and then the poison of the step, as an action that throws does: a
     * poison line between an action's start and end lines belongs to a cascade of that action, never to the action's
     * own end. The refused wait runs on the only worker of a pool that may not start a spare one.
     */
    @Test
    void anEngineAbortOfANestedActionTracesTheActionsEndBeforeThePoison() throws Exception {
        assertTheNullsAreTraced();
        assertTheWaitsThatEndedAreTraced();
        assertTheRecoveryActionThatReturnedNullIsTraced();
    }

    private static void assertTheNullsAreTraced() throws Exception {
        String abort = TransitionAbortedException.class.getName();
        assertEquals(List.of(
                "FINE call=A start Doc/DocStatus SUBMIT",
                "FINER call=A depth=0 resolved Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW kind=ACTION",
                "FINER call=A depth=0 action Submit start mode=sync",
                "FINER call=A depth=0 cascade Doc/DocStatus#p PUBLISH",
                "FINER call=A depth=1 resolved Doc/DocStatus#p REVIEW -PUBLISH-> PUBLISHED kind=ACTION",
                "FINER call=A depth=1 action Publish start mode=sync",
                "FINER call=A depth=1 action Publish THREW error=" + abort + " elapsed=<us>",
                "FINER call=A depth=1 poison error=<the abort: the action Publish returned null>",
                "FINER call=A depth=0 action Submit THREW error=" + abort + " elapsed=<us>",
                "FINE call=A failed phase=ACTION written=NONE entity=Doc/DocStatus#1 error=<the abort> elapsed=<us>"),
                nestedAbort((TransitionBuilder<Doc, DocStatus, DocTransition> publish) -> publish.action("Publish",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> null), "returned null"));
        assertEquals(List.of(
                "FINE call=A start Doc/DocStatus SUBMIT",
                "FINER call=A depth=0 resolved Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW kind=ACTION",
                "FINER call=A depth=0 action Submit start mode=sync",
                "FINER call=A depth=0 cascade Doc/DocStatus#p PUBLISH",
                "FINER call=A depth=1 resolved Doc/DocStatus#p REVIEW -PUBLISH-> PUBLISHED kind=ACTION",
                "FINER call=A depth=1 action Publish start mode=async",
                "FINER call=A depth=1 action Publish THREW error=" + abort + " elapsed=<us>",
                "FINER call=A depth=1 poison error=<the abort: the action Publish returned a null stage>",
                "FINER call=A depth=0 action Submit THREW error=" + abort + " elapsed=<us>",
                "FINE call=A failed phase=ACTION written=NONE entity=Doc/DocStatus#1 error=<the abort> elapsed=<us>"),
                nestedAbort((TransitionBuilder<Doc, DocStatus, DocTransition> publish) -> publish.asyncAction("Publish",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> null), "returned a null stage"));
    }

    private static void assertTheWaitsThatEndedAreTraced() throws Exception {
        String abort = TransitionAbortedException.class.getName();
        List<String> waited = List.of(
                "FINE call=A start Doc/DocStatus SUBMIT",
                "FINER call=A depth=0 resolved Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW kind=ACTION",
                "FINER call=A depth=0 action Submit start mode=sync",
                "FINER call=A depth=0 cascade Doc/DocStatus#p PUBLISH",
                "FINER call=A depth=1 resolved Doc/DocStatus#p REVIEW -PUBLISH-> PUBLISHED kind=ACTION",
                "FINER call=A depth=1 action Publish start mode=async",
                "FINER call=A depth=1 action Publish awaiting budget=<us>",
                "FINER call=A depth=1 action Publish THREW error=" + abort + " elapsed=<us>",
                "FINER call=A depth=1 poison error=<the abort: %s>",
                "FINER call=A depth=0 action Submit THREW error=" + abort + " elapsed=<us>",
                "FINE call=A failed phase=ACTION written=NONE entity=Doc/DocStatus#1 error=<the abort> elapsed=<us>");
        assertEquals(waited.stream().map((String line) -> line.formatted("waiting for the action Publish timed out"))
                        .toList(),
                nestedAbort((TransitionBuilder<Doc, DocStatus, DocTransition> publish) -> publish
                        .asyncAction("Publish", (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                                new CompletableFuture<ActionOutcome>())
                        .timeout(Duration.ofMillis(20)), "timed out"));
        try {
            assertEquals(waited.stream().map((String line) -> line.formatted(
                            "interrupted while waiting for the action Publish")).toList(),
                    nestedAbort((TransitionBuilder<Doc, DocStatus, DocTransition> publish) -> publish
                            .asyncAction("Publish", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                                Thread.currentThread().interrupt();
                                return new CompletableFuture<ActionOutcome>();
                            }), "interrupted"));
        } finally {
            Thread.interrupted();
        }
        try (ForkJoinPool pool = capped()) {
            assertEquals(waited.stream().map((String line) -> line.formatted(
                            "waiting for the action Publish was refused by the thread's ForkJoinPool")).toList(),
                    nestedAbort((TransitionBuilder<Doc, DocStatus, DocTransition> publish) -> publish
                            .asyncAction("Publish", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> late()),
                            UnaryOperator.identity(), Duration.ofSeconds(5), Optional.of(pool), "was refused"));
        }
    }

    private static void assertTheRecoveryActionThatReturnedNullIsTraced() throws Exception {
        String abort = TransitionAbortedException.class.getName();
        assertEquals(List.of(
                "FINE call=A start Doc/DocStatus SUBMIT",
                "FINER call=A depth=0 resolved Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW kind=ACTION",
                "FINER call=A depth=0 action Submit start mode=sync",
                "FINER call=A depth=0 cascade Doc/DocStatus#p PUBLISH",
                "FINER call=A depth=1 resolved Doc/DocStatus#p REVIEW -PUBLISH-> PUBLISHED kind=ACTION",
                "FINER call=A depth=1 action Publish start mode=sync",
                "FINER call=A depth=1 action Publish THREW error=" + GatewayException.class.getName()
                        + " elapsed=<us>",
                "FINER call=A depth=1 route PUBLISH -> SEND_BACK rollback-to=1 reason=\"GatewayException: down\"",
                "FINER call=A depth=1 action SendBack start mode=sync",
                "FINER call=A depth=1 action SendBack THREW error=" + abort + " elapsed=<us>",
                "FINER call=A depth=1 poison error=<the abort: the action SendBack returned null>",
                "FINER call=A depth=0 action Submit THREW error=" + abort + " elapsed=<us>",
                "FINE call=A failed phase=ACTION written=NONE entity=Doc/DocStatus#1 error=<the abort> elapsed=<us>"),
                nestedAbort((TransitionBuilder<Doc, DocStatus, DocTransition> publish) -> publish
                        .action("Publish", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw new GatewayException("down");
                        })
                        .failsOn(GatewayException.class).onFailure(SEND_BACK), "SendBack returned null"));
    }

    /**
     * A nested async guard whose wait times out, is ended by an interrupt or is refused by the thread's ForkJoinPool
     * logs no verdict: the poison of its step follows the step's resolved line, at the step's depth, and the call
     * fails in that abort. A guard's wait is bounded by the call's deadline, here an asyncTimeout of 20 ms.
     */
    @Test
    void aNestedAsyncGuardWhoseWaitEndsTracesItsPoisonWithoutAVerdict() throws Exception {
        List<String> guarded = List.of(
                "FINE call=A start Doc/DocStatus SUBMIT",
                "FINER call=A depth=0 resolved Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW kind=ACTION",
                "FINER call=A depth=0 action Submit start mode=sync",
                "FINER call=A depth=0 cascade Doc/DocStatus#p PUBLISH",
                "FINER call=A depth=1 resolved Doc/DocStatus#p REVIEW -PUBLISH-> PUBLISHED kind=MOVE",
                "FINER call=A depth=1 poison error=<the abort: %s>",
                "FINER call=A depth=0 action Submit THREW error=" + TransitionAbortedException.class.getName()
                        + " elapsed=<us>",
                "FINE call=A failed phase=ACTION written=NONE entity=Doc/DocStatus#1 error=<the abort> elapsed=<us>");
        assertEquals(guarded.stream().map((String line) -> line.formatted("waiting for the guard Ready timed out"))
                        .toList(),
                nestedAbort((TransitionBuilder<Doc, DocStatus, DocTransition> publish) -> publish
                                .asyncGuard("Ready",
                                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> lateVerdict()),
                        UnaryOperator.identity(), Duration.ofMillis(20), Optional.empty(), "timed out"));
        try {
            assertEquals(guarded.stream().map((String line) -> line.formatted(
                            "interrupted while waiting for the guard Ready")).toList(),
                    nestedAbort((TransitionBuilder<Doc, DocStatus, DocTransition> publish) -> publish
                            .asyncGuard("Ready", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                                Thread.currentThread().interrupt();
                                return lateVerdict();
                            }), "interrupted"));
        } finally {
            Thread.interrupted();
        }
        try (ForkJoinPool pool = capped()) {
            assertEquals(guarded.stream().map((String line) -> line.formatted(
                            "waiting for the guard Ready was refused by the thread's ForkJoinPool")).toList(),
                    nestedAbort((TransitionBuilder<Doc, DocStatus, DocTransition> publish) -> publish
                                    .asyncGuard("Ready",
                                            (GuardContext<Doc, DocStatus, DocTransition> ctx) -> lateVerdict()),
                            UnaryOperator.identity(), Duration.ofSeconds(5), Optional.of(pool), "was refused"));
        }
    }

    /**
     * A nested action that caught an Error, here a StackOverflowError, from its own cascade, and then returned a null
     * outcome or a null stage, ends the call in the abort that the caught Error left on its step, recorded before the
     * engine's own abort: that later abort finds the call poisoned, so it logs no poison line, and the cascaded
     * action that threw the Error logs no end.
     */
    @Test
    void aNestedActionThatCaughtAnErrorAndGaveUpTracesNoPoisonForTheEnginesAbort() throws Exception {
        String abort = TransitionAbortedException.class.getName();
        String lost = "REVIEW -PUBLISH-> PUBLISHED: the action caught an Error that ended one of its cascades: the call"
                + " cannot commit";
        UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> overflowing =
                (TransitionBuilder<Doc, DocStatus, DocTransition> archive) -> archive.action("Archive",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw new StackOverflowError();
                        });
        List<String> gaveUp = List.of(
                "FINE call=A start Doc/DocStatus SUBMIT",
                "FINER call=A depth=0 resolved Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW kind=ACTION",
                "FINER call=A depth=0 action Submit start mode=sync",
                "FINER call=A depth=0 cascade Doc/DocStatus#p PUBLISH",
                "FINER call=A depth=1 resolved Doc/DocStatus#p REVIEW -PUBLISH-> PUBLISHED kind=ACTION",
                "FINER call=A depth=1 action Publish start mode=%s",
                "FINER call=A depth=1 cascade Doc/DocStatus#q ARCHIVE",
                "FINER call=A depth=2 resolved Doc/DocStatus#q PUBLISHED -ARCHIVE-> ARCHIVED kind=ACTION",
                "FINER call=A depth=2 action Archive start mode=sync",
                "FINER call=A depth=1 action Publish THREW error=" + abort + " elapsed=<us>",
                "FINER call=A depth=0 action Submit THREW error=" + abort + " elapsed=<us>",
                "FINE call=A failed phase=ACTION written=NONE entity=Doc/DocStatus#1 error=<the abort> elapsed=<us>");
        assertEquals(gaveUp.stream().map((String line) -> line.formatted("sync")).toList(),
                nestedAbort((TransitionBuilder<Doc, DocStatus, DocTransition> publish) -> publish.action("Publish",
                                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                                    caughtFromCascade(ctx);
                                    return null;
                                }),
                        overflowing, Duration.ofSeconds(5), Optional.empty(), lost));
        assertEquals(gaveUp.stream().map((String line) -> line.formatted("async")).toList(),
                nestedAbort((TransitionBuilder<Doc, DocStatus, DocTransition> publish) -> publish.asyncAction(
                                "Publish", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                                    caughtFromCascade(ctx);
                                    return null;
                                }),
                        overflowing, Duration.ofSeconds(5), Optional.empty(), lost));
    }

    /** Cascades ARCHIVE into a publication and catches the StackOverflowError that its action throws. */
    private static void caughtFromCascade(TransitionContext<Doc, DocStatus, DocTransition> ctx) {
        try {
            ctx.cascade(DOCS, new Doc("q", PUBLISHED), ARCHIVE);
        } catch (StackOverflowError caught) {
            // The action goes on, and gives up.
        }
    }

    /** A pool of one worker that may not start a spare one: it refuses to let that worker block. */
    private static ForkJoinPool capped() {
        return new ForkJoinPool(1, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, false, 0, 1, 1, null, 60,
                TimeUnit.SECONDS);
    }

    /** A stage that completes only after five seconds, so that a regression cannot hang the build. */
    private static CompletableFuture<ActionOutcome> late() {
        return new CompletableFuture<ActionOutcome>().completeOnTimeout(ActionOutcome.ok(), 5, TimeUnit.SECONDS);
    }

    /** {@link #late()} for a guard. */
    private static CompletableFuture<GuardResult> lateVerdict() {
        return new CompletableFuture<GuardResult>().completeOnTimeout(GuardResult.pass(), 5, TimeUnit.SECONDS);
    }

    /** {@link #nestedAbort(UnaryOperator, UnaryOperator, Duration, Optional, String)} with a plain ARCHIVE. */
    private static List<String> nestedAbort(UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> publish,
                                            String expected) throws Exception {
        return nestedAbort(publish, UnaryOperator.identity(), Duration.ofSeconds(5), Optional.empty(), expected);
    }

    /**
     * The lines of a call whose action SUBMIT cascades PUBLISH, shaped by {@code publish}, into a doc in REVIEW, which
     * the engine aborts with a message that contains {@code expected}: the abort's text is replaced by
     * {@code <the abort>} in the failed line and by {@code <the abort: message>} in the poison line, where
     * {@code message} is what follows the abort's edge. SEND_BACK, REVIEW to DRAFT, runs an action that returns null;
     * ARCHIVE moves a publication to ARCHIVED, shaped by {@code archive}. The machine's asyncTimeout is
     * {@code asyncTimeout}, and the call runs on a worker of {@code pool} when there is one.
     */
    private static List<String> nestedAbort(UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> publish,
                                            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> archive,
                                            Duration asyncTimeout, Optional<ForkJoinPool> pool, String expected)
            throws Exception {
        GraphBuilder<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT)
                .terminal(ARCHIVED).allowsCycle(DRAFT)
                .statePolicy(DRAFT, "a draft is submitted")
                .statePolicy(REVIEW, "a review is published or sent back")
                .statePolicy(PUBLISHED, "a publication is archived");
        graph = archive.apply(graph.transition(ARCHIVE, PUBLISHED, ARCHIVED)).endTransition()
                .transition(SUBMIT, DRAFT, REVIEW)
                    .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        ctx.cascade(DOCS, new Doc("p", REVIEW), PUBLISH);
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT)
                    .action("SendBack", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> null)
                    .endTransition();
        graph = publish.apply(graph.transition(PUBLISH, REVIEW, PUBLISHED)).endTransition();
        try (MachineRegistry registry = registry(graph.build(), asyncTimeout);
             LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            Supplier<TransitionAbortedException> call = () -> assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT));
            TransitionAbortedException error = pool.isPresent()
                    ? CompletableFuture.supplyAsync(call, pool.get()).get(5, TimeUnit.SECONDS) : call.get();
            assertTrue(error.getMessage().contains(expected), error.getMessage());
            String text = error.toString();
            String message = error.getMessage();
            String said = message.substring(message.indexOf(": ") + 2, message.lastIndexOf(" [route"));
            return lines(log).stream().map((String line) -> line.replace("poison error=" + text,
                    "poison error=<the abort: " + said + ">").replace("error=" + text, "error=<the abort>")).toList();
        }
    }
}
