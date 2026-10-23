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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The engine's wait for an asynchronous stage on a worker of a ForkJoinPool. Called there, CompletableFuture's get
 * and join may first run other queued asynchronous tasks on the waiting thread, which is the transaction's own: it
 * carries the reentrancy marker, owns the contexts and enforces the deadline. The engine's wait never runs another
 * task, and the pool can still start a spare worker while it blocks; the time that takes counts against the
 * deadline. A wait that an interrupt or a spent budget has already ended never asks the pool for a spare worker, and
 * a pool's refusal to let its worker block ends the call in an abort.
 *
 * <p>The first three tests run a root on a common-pool worker while the pool's other workers wait on a latch, so
 * that a task the root queues on its own worker has no idle worker to steal it: a wait that runs queued tasks would
 * run it inside the root. On JDK 21 that takes a common pool of parallelism 2 or more: below that, CompletableFuture
 * sends a task meant for the common pool to a new thread, and those tests pass without a common-pool worker. The
 * other tests use pools of their own. Every wait is bounded, so a regression fails a test instead of hanging the
 * build.
 */
class ForkJoinWaitTest {

    /**
     * Starts one task fewer than the common pool's parallelism, each waiting on a latch until closed, so that a pool
     * of that many workers has a single one free, and a task queued on that worker no idle worker to steal it. The
     * pool does not see a latch wait, so it starts no spare worker for it.
     */
    private static final class OneFreeWorker implements AutoCloseable {

        private final CountDownLatch release = new CountDownLatch(1);
        private final List<CompletableFuture<Void>> blockers = new ArrayList<>();

        OneFreeWorker() throws InterruptedException {
            int others = ForkJoinPool.getCommonPoolParallelism() - 1;
            CountDownLatch started = new CountDownLatch(others);
            for (int other = 0; other < others; other++) {
                blockers.add(CompletableFuture.runAsync(() -> {
                    started.countDown();
                    hold(release);
                }, ForkJoinPool.commonPool()));
            }
            assertTrue(started.await(10, TimeUnit.SECONDS), "the common pool did not start " + others + " blockers");
        }

        /** Runs {@code call} on the common pool, where only the free worker can take it. */
        <R> CompletableFuture<R> run(Supplier<R> call) {
            return CompletableFuture.supplyAsync(call, ForkJoinPool.commonPool());
        }

        @Override
        public void close() {
            release.countDown();
            CompletableFuture.allOf(blockers.toArray(new CompletableFuture<?>[0]))
                    .orTimeout(10, TimeUnit.SECONDS).join();
        }
    }

    /** How a call ended, as the outcome's status or the exception's type and its cause's, and how long it took. */
    private record End(String how, long millis) {

        /** How it ended, with the time it took appended only when that time reaches {@code max} ms. */
        String within(long max) {
            return millis < max ? how : how + " after " + millis + " ms";
        }
    }

    /** Waits for {@code latch} for at most ten seconds, so that a failed test cannot leave a worker blocked. */
    private static void hold(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Runs {@code call} on the calling thread and tells how it ended. */
    private static End end(Supplier<Outcome<Doc, DocStatus, DocTransition>> call) {
        long start = System.nanoTime();
        String how;
        try {
            how = call.get().status().name();
        } catch (RuntimeException e) {
            how = e.getClass().getSimpleName()
                    + (e.getCause() == null ? "" : " caused by " + e.getCause().getClass().getSimpleName());
        }
        return new End(how, (System.nanoTime() - start) / 1_000_000L);
    }

    /** How {@code call} ended within {@code max} ms, or that it had not ended after five seconds. */
    private static String endOf(CompletableFuture<End> call, long max) throws Exception {
        try {
            return call.get(5, TimeUnit.SECONDS).within(max);
        } catch (TimeoutException e) {
            return "no end after 5 s";
        }
    }

    /** A stage that completes after 100 ms. */
    private static CompletableFuture<ActionOutcome> soon() {
        return new CompletableFuture<ActionOutcome>().completeOnTimeout(ActionOutcome.ok(), 100, TimeUnit.MILLISECONDS);
    }

    /**
     * A stage nobody completes in time. It completes itself after five seconds, so a regression cannot hang the build.
     */
    private static CompletableFuture<ActionOutcome> late() {
        return new CompletableFuture<ActionOutcome>().completeOnTimeout(ActionOutcome.ok(), 5, TimeUnit.SECONDS);
    }

    /**
     * A pool of one worker that may not start a spare one: its maximum size is its parallelism, and it has no
     * saturate predicate.
     */
    private static ForkJoinPool capped() {
        return new ForkJoinPool(1, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, false, 0, 1, 1, null, 60,
                TimeUnit.SECONDS);
    }

    /** {@link #machine(AsyncTransitionAction, Optional, Duration)} where each root may wait ten seconds in all. */
    private static StateMachine<Doc, DocStatus, DocTransition> machine(
            AsyncTransitionAction<Doc, DocStatus, DocTransition> submit, Optional<Duration> timeout) {
        return machine(submit, timeout, Duration.ofSeconds(10));
    }

    /**
     * DRAFT -SUBMIT-> REVIEW waits for {@code submit}, under {@code timeout} when there is one; REVIEW -PUBLISH->
     * PUBLISHED runs nothing. Each root may wait {@code asyncTimeout} in all.
     */
    private static StateMachine<Doc, DocStatus, DocTransition> machine(
            AsyncTransitionAction<Doc, DocStatus, DocTransition> submit, Optional<Duration> timeout,
            Duration asyncTimeout) {
        TransitionBuilder<Doc, DocStatus, DocTransition> submitting = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).asyncAction("Submit", submit);
        if (timeout.isPresent()) {
            submitting = submitting.timeout(timeout.get());
        }
        GraphDefinition<Doc, DocStatus, DocTransition> graph = submitting.endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        return StateMachines.from(graph).state(Doc::status, Doc::setStatus).asyncTimeout(asyncTimeout).build();
    }

    /**
     * The root's action starts an independent root on another document with CompletableFuture.supplyAsync, which
     * queues it on the root's own worker, and returns a stage that completes 100 ms later. The first root's wait does
     * not run the second root, which applies. Run inside that wait, it would find the thread marked as running a
     * transition, and fail with ReentrantTransitionException.
     */
    @Test
    void anIndependentRootQueuedOnTheWaitingWorkerRunsOnceTheWaitIsOver() throws Exception {
        Doc b = new Doc("b", REVIEW);
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        AtomicReference<CompletableFuture<End>> second = new AtomicReference<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    second.set(CompletableFuture.supplyAsync(() -> end(() -> self.get().execute(b, PUBLISH)),
                            ForkJoinPool.commonPool()));
                    return soon();
                }, Optional.empty());
        self.set(machine);
        Doc a = new Doc("a", DRAFT);
        String first;
        try (OneFreeWorker pool = new OneFreeWorker()) {
            first = endOf(pool.run(() -> end(() -> machine.execute(a, SUBMIT))), 3_000);
        }
        String then = second.get().get(10, TimeUnit.SECONDS).how();
        assertEquals("a: APPLIED, b: APPLIED, a REVIEW, b PUBLISHED",
                "a: " + first + ", b: " + then + ", a " + a.status() + ", b " + b.status());
    }

    /**
     * The root's action queues on its own worker a task that blocks until the test releases it, and returns a stage
     * that completes only after five seconds, under an edge timeout of 100 ms. The call ends in a timeout abort in
     * less than three seconds, whatever the task does. Run inside the wait, the task would hold the root past its
     * timeout.
     */
    @Test
    void aTaskQueuedOnTheWaitingWorkerDoesNotDelayTheTimeout() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        AtomicReference<CompletableFuture<Void>> task = new AtomicReference<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    task.set(CompletableFuture.runAsync(() -> hold(held), ForkJoinPool.commonPool()));
                    return late();
                }, Optional.of(Duration.ofMillis(100)));
        Doc a = new Doc("a", DRAFT);
        String end;
        try (OneFreeWorker pool = new OneFreeWorker()) {
            try {
                end = endOf(pool.run(() -> end(() -> machine.execute(a, SUBMIT))), 3_000);
            } finally {
                held.countDown();
            }
            task.get().get(10, TimeUnit.SECONDS);
        }
        assertEquals("TransitionAbortedException caused by TimeoutException, a DRAFT", end + ", a " + a.status());
    }

    /**
     * The root's action queues a task on its own worker that cascades through the action's context, and returns a
     * stage that completes 100 ms later. The task cannot run on the thread that owns the context while the root is
     * running, so the context refuses it: with StaleContextException once the root is over, or with
     * WrongThreadException should another thread run the task meanwhile. Either way the peer stays where it was. Run
     * inside the wait, the task would pass the thread check, and the root would commit the peer's step with its own.
     */
    @Test
    void aContextUsedFromATaskQueuedOnTheWaitingWorkerIsRefused() throws Exception {
        Doc peer = new Doc("peer", REVIEW);
        AtomicReference<CompletableFuture<String>> task = new AtomicReference<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    task.set(CompletableFuture.supplyAsync(() -> {
                        try {
                            ctx.cascade(DOCS, peer, PUBLISH);
                            return "cascade ran";
                        } catch (RuntimeException e) {
                            return e.getClass().getSimpleName();
                        }
                    }, ForkJoinPool.commonPool()));
                    return soon();
                }, Optional.empty());
        Doc a = new Doc("a", DRAFT);
        String end;
        try (OneFreeWorker pool = new OneFreeWorker()) {
            end = endOf(pool.run(() -> end(() -> machine.execute(a, SUBMIT))), 3_000);
        }
        String fromTask = task.get().get(10, TimeUnit.SECONDS);
        String refusal = List.of("StaleContextException", "WrongThreadException").contains(fromTask)
                ? "refused" : fromTask;
        assertEquals("a: APPLIED, task refused, a REVIEW, peer REVIEW",
                "a: " + end + ", task " + refusal + ", a " + a.status() + ", peer " + peer.status());
    }

    /**
     * A root runs on the only worker of a pool of its own, and its action's stage is a task it queues on that pool,
     * where no other worker exists to take it. The engine blocks through ForkJoinPool.managedBlock, as
     * CompletableFuture's get does, so the pool starts a spare worker that runs the task, and the root applies in
     * less than three seconds instead of timing out after ten.
     */
    @Test
    void aWaitingWorkerLetsItsPoolStartASpareWorker() throws Exception {
        Doc a = new Doc("a", DRAFT);
        String end;
        try (ForkJoinPool pool = new ForkJoinPool(1)) {
            StateMachine<Doc, DocStatus, DocTransition> machine = machine(
                    (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                            CompletableFuture.supplyAsync(ActionOutcome::ok, pool), Optional.empty());
            end = endOf(CompletableFuture.supplyAsync(() -> end(() -> machine.execute(a, SUBMIT)), pool), 3_000);
        }
        assertEquals("APPLIED, a REVIEW", end + ", a " + a.status());
    }

    /**
     * A root runs on the only worker of a pool that may not start a spare one, and its action interrupts its own
     * thread before it returns a stage that is not complete. The interrupt ends the wait before the pool is asked for
     * a spare worker, which it would refuse with RejectedExecutionException: the call ends in the abort of an
     * interrupted wait, and the thread is still interrupted afterwards.
     */
    @Test
    void anInterruptedWaitOnAPoolThatCannotStartASpareWorkerAborts() throws Exception {
        Doc a = new Doc("a", DRAFT);
        String end;
        try (ForkJoinPool pool = capped()) {
            StateMachine<Doc, DocStatus, DocTransition> machine = machine(
                    (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        Thread.currentThread().interrupt();
                        return late();
                    }, Optional.empty());
            end = CompletableFuture.supplyAsync(() -> end(() -> machine.execute(a, SUBMIT)).how()
                    + ", interrupted " + Thread.interrupted(), pool).get(5, TimeUnit.SECONDS);
        }
        assertEquals("TransitionAbortedException caused by InterruptedException, interrupted true, a DRAFT",
                end + ", a " + a.status());
    }

    /**
     * A root runs on the only worker of a pool that may not start a spare one, and waits, with time left and no
     * interrupt, for a stage that is not complete. The pool refuses to let its only worker block, with
     * RejectedExecutionException, and the call ends in an abort caused by that refusal, as a timeout or an interrupt
     * ends in one, instead of letting the refusal escape as it is.
     */
    @Test
    void aWaitThatThePoolRefusesToLetBlockAborts() throws Exception {
        Doc a = new Doc("a", DRAFT);
        String end;
        try (ForkJoinPool pool = capped()) {
            StateMachine<Doc, DocStatus, DocTransition> machine = machine(
                    (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> late(), Optional.empty());
            end = endOf(CompletableFuture.supplyAsync(() -> end(() -> machine.execute(a, SUBMIT)), pool), 3_000);
        }
        assertEquals("TransitionAbortedException caused by RejectedExecutionException, a DRAFT",
                end + ", a " + a.status());
    }

    /**
     * A root runs on the only worker of a pool that may not start a spare one, and its action takes 50 ms, longer
     * than the root's whole budget of 20 ms, before it returns a stage that is not complete. A wait whose budget is
     * already spent never asks the pool for a spare worker, so the call ends in a timeout abort, not in the pool's
     * refusal.
     */
    @Test
    void aSpentBudgetNeverAsksThePoolForASpareWorker() throws Exception {
        Doc a = new Doc("a", DRAFT);
        String end;
        try (ForkJoinPool pool = capped()) {
            StateMachine<Doc, DocStatus, DocTransition> machine = machine(
                    (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        pause(50);
                        return late();
                    }, Optional.empty(), Duration.ofMillis(20));
            end = endOf(CompletableFuture.supplyAsync(() -> end(() -> machine.execute(a, SUBMIT)), pool), 3_000);
        }
        assertEquals("TransitionAbortedException caused by TimeoutException, a DRAFT", end + ", a " + a.status());
    }

    /**
     * A root runs on the only worker of a pool that takes a second to start each spare worker, and waits for a
     * stage that is not complete in time, under an edge timeout of one second. The pool starts a spare worker as the
     * wait begins, and the second that takes counts against the timeout: the call ends in a timeout abort in less
     * than 1.6 seconds, not a full second after the spare worker started.
     */
    @Test
    void theTimeAPoolTakesToStartASpareWorkerCountsAgainstTheTimeout() throws Exception {
        AtomicInteger created = new AtomicInteger();
        ForkJoinPool.ForkJoinWorkerThreadFactory slowSpares = (ForkJoinPool owner) -> {
            if (created.getAndIncrement() > 0) {
                pause(1_000);
            }
            return ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(owner);
        };
        Doc a = new Doc("a", DRAFT);
        String end;
        try (ForkJoinPool pool = new ForkJoinPool(1, slowSpares, null, false)) {
            StateMachine<Doc, DocStatus, DocTransition> machine = machine(
                    (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> late(),
                    Optional.of(Duration.ofSeconds(1)));
            end = endOf(CompletableFuture.supplyAsync(() -> end(() -> machine.execute(a, SUBMIT)), pool), 1_600);
        }
        assertEquals("TransitionAbortedException caused by TimeoutException, a DRAFT", end + ", a " + a.status());
    }
}
