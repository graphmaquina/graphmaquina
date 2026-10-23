package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Timeouts of {@code Duration.ofNanos(Long.MAX_VALUE)} or more, which saturate to "no limit", and one just below that,
 * {@code Duration.ofNanos(Long.MAX_VALUE - 1)}, a finite budget of about 292 years whose deadline wraps past
 * {@code Long.MAX_VALUE}. The builders accept every one of them, and none throws, switches the other limit off or
 * leaves the thread marked as running a transition. Under each of them a 200 ms stage completes, and every wait is
 * timed, so that a limit cut short shows as well as one switched off.
 */
class HugeTimeoutTest {

    /**
     * How users write "forever". The first four saturate, being {@code Duration.ofNanos(Long.MAX_VALUE)} or more: the
     * exact boundary, one whose nanoseconds overflow a long, the usual "1000 years", and {@code ChronoUnit.FOREVER},
     * the largest {@code Duration}, {@code Duration.ofSeconds(Long.MAX_VALUE, 999_999_999)}, which
     * {@code @Timeout(seconds = Long.MAX_VALUE, nanos = 999_999_999)} spells. The last stays one nanosecond below
     * saturation, so it is kept as a number, and the deadline it gives wraps past Long.MAX_VALUE as soon as
     * {@code System.nanoTime()} is above 1.
     */
    private static final List<Duration> HUGE = List.of(Duration.ofNanos(Long.MAX_VALUE),
            Duration.ofMillis(Long.MAX_VALUE), Duration.ofDays(365_000), ChronoUnit.FOREVER.getDuration(),
            Duration.ofNanos(Long.MAX_VALUE - 1));

    private static final TransitionAction<Doc, DocStatus, DocTransition> OK =
            (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ActionOutcome.ok();

    private static final AsyncTransitionAction<Doc, DocStatus, DocTransition> DONE =
            (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                    CompletableFuture.completedFuture(ActionOutcome.ok());

    /** How one call ended, as the outcome's status or the exception's type and its cause's, and how long it took. */
    private record End(String how, long millis) {

        /** How it ended, with the time it took appended only when that time falls outside {@code [min, max)} ms. */
        String within(long min, long max) {
            return min <= millis && millis < max ? how : how + " after " + millis + " ms";
        }
    }

    /**
     * DRAFT -SUBMIT-> REVIEW runs {@code submit}; REVIEW -PUBLISH-> PUBLISHED waits for {@code publish}, under
     * {@code timeout} when there is one.
     */
    private static GraphDefinition<Doc, DocStatus, DocTransition> docs(
            TransitionAction<Doc, DocStatus, DocTransition> submit,
            AsyncTransitionAction<Doc, DocStatus, DocTransition> publish, Optional<Duration> timeout) {
        TransitionBuilder<Doc, DocStatus, DocTransition> publishing = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).action("Submit", submit).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).asyncAction("Publish", publish);
        if (timeout.isPresent()) {
            publishing = publishing.timeout(timeout.get());
        }
        return publishing.endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    private static StateMachine<Doc, DocStatus, DocTransition> machine(
            GraphDefinition<Doc, DocStatus, DocTransition> graph, Duration asyncTimeout) {
        return StateMachines.from(graph).state(Doc::status, Doc::setStatus).asyncTimeout(asyncTimeout).build();
    }

    /** A stage that completes after 200 ms: a wait with no limit sees it complete, a cut-short one times out first. */
    private static CompletableFuture<ActionOutcome> soon() {
        return new CompletableFuture<ActionOutcome>().completeOnTimeout(ActionOutcome.ok(), 200, TimeUnit.MILLISECONDS);
    }

    /** A stage nobody completes in time. It completes itself after five seconds, so a regression cannot hang the build. */
    private static CompletableFuture<ActionOutcome> late() {
        return new CompletableFuture<ActionOutcome>().completeOnTimeout(ActionOutcome.ok(), 5, TimeUnit.SECONDS);
    }

    /** Runs {@code call} on {@code thread}, so that a marker the call leaks stays there instead of reaching other tests. */
    private static End end(ExecutorService thread, Supplier<Outcome<Doc, DocStatus, DocTransition>> call)
            throws Exception {
        return thread.submit(() -> {
            long start = System.nanoTime();
            String how;
            try {
                how = call.get().status().name();
            } catch (RuntimeException e) {
                how = e.getClass().getSimpleName()
                        + (e.getCause() == null ? "" : " caused by " + e.getCause().getClass().getSimpleName());
            }
            return new End(how, (System.nanoTime() - start) / 1_000_000L);
        }).get();
    }

    /**
     * With a huge asyncTimeout, a synchronous root applies, an asynchronous one waits the 200 ms its stage takes and
     * applies, and the thread that ran them can still run a transition of another machine afterwards.
     */
    @Test
    void aHugeAsyncTimeoutMeansNoLimitAndLeavesTheThreadUsable() throws Exception {
        List<String> ends = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (Duration huge : HUGE) {
            StateMachine<Doc, DocStatus, DocTransition> forever = machine(docs(OK,
                    (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> soon(), Optional.empty()), huge);
            StateMachine<Doc, DocStatus, DocTransition> other =
                    machine(docs(OK, DONE, Optional.empty()), Duration.ofSeconds(5));
            Doc doc = new Doc("1", DRAFT);
            try (ExecutorService thread = Executors.newSingleThreadExecutor()) {
                ends.add(huge + ": " + end(thread, () -> forever.execute(doc, SUBMIT)).how() + ", "
                        + end(thread, () -> forever.execute(doc, PUBLISH)).within(150, 3_000) + ", "
                        + end(thread, () -> other.execute(new Doc("2", DRAFT), SUBMIT)).how());
            }
            expected.add(huge + ": APPLIED, APPLIED, APPLIED");
        }
        assertEquals(expected, ends);
    }

    /** With a huge budget on both sides, neither one limits: the 200 ms stage completes and the edge applies. */
    @Test
    void hugeBudgetsOnBothSidesMeanNoLimit() throws Exception {
        List<String> ends = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (Duration huge : HUGE) {
            StateMachine<Doc, DocStatus, DocTransition> machine = machine(docs(OK,
                    (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> soon(), Optional.of(huge)), huge);
            Doc doc = new Doc("1", REVIEW);
            try (ExecutorService thread = Executors.newSingleThreadExecutor()) {
                ends.add(huge + ": " + end(thread, () -> machine.execute(doc, PUBLISH)).within(150, 3_000) + ", "
                        + doc.status());
            }
            expected.add(huge + ": APPLIED, PUBLISHED");
        }
        assertEquals(expected, ends);
    }

    /**
     * A huge edge timeout neither throws after the action started nor changes the root's 300 ms deadline: the wait
     * ends in a timeout abort between 250 ms and 3 s after the call began, not at the stage's own five seconds, and
     * nothing is written.
     */
    @Test
    void aHugeEdgeTimeoutLeavesTheRootDeadlineInCharge() throws Exception {
        List<String> ends = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (Duration huge : HUGE) {
            AtomicInteger started = new AtomicInteger();
            StateMachine<Doc, DocStatus, DocTransition> machine = machine(docs(OK,
                    (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        started.incrementAndGet();
                        return late();
                    }, Optional.of(huge)), Duration.ofMillis(300));
            Doc doc = new Doc("1", REVIEW);
            try (ExecutorService thread = Executors.newSingleThreadExecutor()) {
                ends.add(huge + ": " + end(thread, () -> machine.execute(doc, PUBLISH)).within(250, 3_000)
                        + ", started " + started.get() + ", " + doc.status());
            }
            expected.add(huge + ": TransitionAbortedException caused by TimeoutException, started 1, REVIEW");
        }
        assertEquals(expected, ends);
    }

    /** A huge root deadline neither switches off nor shortens a 200 ms edge timeout. */
    @Test
    void aHugeAsyncTimeoutLeavesTheEdgeTimeoutInCharge() throws Exception {
        List<String> ends = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (Duration huge : HUGE) {
            StateMachine<Doc, DocStatus, DocTransition> machine = machine(docs(OK,
                    (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> late(),
                    Optional.of(Duration.ofMillis(200))),
                    huge);
            Doc doc = new Doc("1", REVIEW);
            try (ExecutorService thread = Executors.newSingleThreadExecutor()) {
                ends.add(huge + ": " + end(thread, () -> machine.execute(doc, PUBLISH)).within(150, 3_000) + ", "
                        + doc.status());
            }
            expected.add(huge + ": TransitionAbortedException caused by TimeoutException, REVIEW");
        }
        assertEquals(expected, ends);
    }

    /** The same holds one level down: a cascaded peer's huge edge timeout waits under the root's 300 ms deadline. */
    @Test
    void aHugeEdgeTimeoutInsideACascadeLeavesTheRootDeadlineInCharge() throws Exception {
        List<String> ends = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (Duration huge : HUGE) {
            Doc peer = new Doc("2", REVIEW);
            StateMachine<Doc, DocStatus, DocTransition> machine = machine(docs(
                    (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        ctx.cascade(DOCS, peer, PUBLISH);
                        return ActionOutcome.ok();
                    },
                    (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> late(), Optional.of(huge)),
                    Duration.ofMillis(300));
            Doc doc = new Doc("1", DRAFT);
            try (ExecutorService thread = Executors.newSingleThreadExecutor()) {
                ends.add(huge + ": " + end(thread, () -> machine.execute(doc, SUBMIT)).within(250, 3_000) + ", "
                        + doc.status() + ", " + peer.status());
            }
            expected.add(huge + ": TransitionAbortedException caused by TimeoutException, DRAFT, REVIEW");
        }
        assertEquals(expected, ends);
    }
}
