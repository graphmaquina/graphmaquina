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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * How a call submitted through {@code async(...)} is queued: StateMachine.supplyAsync, which every generated facade's
 * {@code async} goes through, and AsyncStateMachine's execute and reevaluate. Each call is a plain task, never one of
 * the asynchronous tasks that CompletableFuture's get and join run on a waiting ForkJoinPool worker: run there, the
 * call would run inside the call that waits, find the thread marked as running a transition, and fail with
 * ReentrantTransitionException. The task completes its future the way CompletableFuture.supplyAsync completes the one
 * it returns, save that a throwable whose printing throws still fails it, with the message
 * {@code <toString threw <class name>>}, where supplyAsync leaves its future pending on JDK 21 and fails it with an
 * empty message on JDK 25. With ForkJoinPool.commonPool() as the executor and a common pool whose parallelism is below
 * two, each call runs on a new thread, as CompletableFuture.supplyAsync sends a task meant for that pool.
 *
 * <p>The first two tests run calls on the common pool while its other workers wait, so that a call queued on the
 * one free worker has no idle worker to steal it. On JDK 21 that takes a common pool of parallelism 2 or more: below
 * that, the calls run on new threads, and those tests pass without a common-pool worker. Every wait is bounded, so a
 * regression fails a test instead of hanging the build.
 */
class AsyncSubmissionTest {

    /**
     * Starts one task fewer than the common pool's parallelism, each waiting until closed, so that a pool of that
     * many workers has a single one free, and a task queued on that worker no idle worker to steal it. A task waits
     * on a latch, which the pool does not see, so it starts no spare worker for it; or, when {@code managed}, in
     * CompletableFuture's get, where the pool sees it and may start a spare worker in its place.
     */
    private static final class OneFreeWorker {

        private final CountDownLatch release = new CountDownLatch(1);
        private final CompletableFuture<Void> released = new CompletableFuture<>();
        private final List<CompletableFuture<Void>> blockers = new ArrayList<>();

        OneFreeWorker(boolean managed) throws InterruptedException {
            int others = ForkJoinPool.getCommonPoolParallelism() - 1;
            CountDownLatch started = new CountDownLatch(others);
            for (int other = 0; other < others; other++) {
                blockers.add(CompletableFuture.runAsync(() -> {
                    started.countDown();
                    try {
                        if (managed) {
                            released.get(20, TimeUnit.SECONDS);
                        } else {
                            release.await(20, TimeUnit.SECONDS);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (ExecutionException | TimeoutException e) {
                        // Released by its own bound: the test has already failed.
                    }
                }, ForkJoinPool.commonPool()));
            }
            assertTrue(started.await(10, TimeUnit.SECONDS), "the common pool did not start " + others + " blockers");
        }

        void close() {
            release.countDown();
            released.complete(null);
            CompletableFuture.allOf(blockers.toArray(new CompletableFuture<?>[0])).orTimeout(20, TimeUnit.SECONDS)
                    .join();
        }
    }

    /**
     * DRAFT -SUBMIT-> REVIEW runs {@code submit} and then {@code followUp} after the commit; REVIEW -PUBLISH->
     * PUBLISHED runs nothing.
     */
    private static GraphDefinition<Doc, DocStatus, DocTransition> graph(
            TransitionAction<Doc, DocStatus, DocTransition> submit, Consumer<Doc> followUp) {
        return StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).action("Submit", submit).afterCommit("FollowUp", followUp)
                    .endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    /** The doc machine of {@link #graph} with nothing to run, on {@code executor}. */
    private static StateMachine<Doc, DocStatus, DocTransition> idle(ExecutorService executor) {
        return StateMachines.from(graph((TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ActionOutcome.ok(),
                (Doc doc) -> { })).state(Doc::status, Doc::setStatus).asyncExecutor(executor).build();
    }

    /** Runs {@code call} and tells how it ended: the outcome's status, or the exception's type. */
    private static String end(Supplier<Outcome<Doc, DocStatus, DocTransition>> call) {
        try {
            return call.get().status().name();
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    /**
     * How {@code call} ended: the outcome's status, the type of its failure's cause, or that it had not ended after
     * ten seconds.
     */
    private static String endOf(CompletableFuture<Outcome<Doc, DocStatus, DocTransition>> call) throws Exception {
        try {
            return call.get(10, TimeUnit.SECONDS).status().name();
        } catch (ExecutionException e) {
            return e.getCause().getClass().getSimpleName();
        } catch (TimeoutException e) {
            return "no end after 10 s";
        }
    }

    /** A runtime exception whose message cannot be read, so its string cannot be printed either. */
    private static final class UnreadableFailure extends RuntimeException {
        @Override
        public String getMessage() {
            throw new IllegalStateException("the message cannot be read");
        }
    }

    /** An Error whose message cannot be read, so its string cannot be printed either. */
    private static final class UnreadableError extends Error {
        @Override
        public String getMessage() {
            throw new IllegalStateException("the message cannot be read");
        }
    }

    /** A runtime exception whose string cannot be printed, because toString throws an Error. */
    private static final class FatallyUnprintableFailure extends RuntimeException {
        @Override
        public String toString() {
            throw new AssertionError("the string cannot be printed");
        }
    }

    /** What {@code future} failed with, as a dependent stage sees it, or a TimeoutException after ten seconds. */
    private static <R> Throwable failureOf(CompletableFuture<R> future) throws Exception {
        return future.handle((R value, Throwable error) -> error).get(10, TimeUnit.SECONDS);
    }

    /**
     * {@code thrown} described without printing {@code cause}, which may not be printable: its class and message,
     * whether its cause is {@code cause}, and the classes of what it suppressed.
     */
    private static String described(Throwable thrown, Throwable cause) {
        List<String> suppressed = new ArrayList<>();
        for (Throwable each : thrown.getSuppressed()) {
            suppressed.add(each.getClass().getName());
        }
        String causedBy = thrown.getCause() == cause ? "that cause"
                : thrown.getCause() == null ? "nothing" : thrown.getCause().getClass().getName();
        return thrown.getClass().getName() + " [" + thrown.getMessage() + "] caused by " + causedBy + ", suppressing "
                + suppressed;
    }

    /**
     * A single-thread executor whose thread records what escapes a task into {@code escaped}, as the thread's
     * uncaught exception handler.
     */
    private static ExecutorService single(List<Throwable> escaped) {
        return Executors.newSingleThreadExecutor((Runnable task) -> {
            Thread thread = new Thread(task, "single");
            thread.setUncaughtExceptionHandler((Thread failed, Throwable error) -> escaped.add(error));
            return thread;
        });
    }

    /**
     * A hook of a call on a common-pool worker submits two calls through async() and returns. They queue on that
     * worker, which takes the second one first; its action blocks in CompletableFuture's join on a stage that
     * completes 300 ms later. The join does not run the first call on its thread, so both calls apply, the first one
     * once the second ends or on a worker the pool starts meanwhile. Run inside the join, the first call would find
     * the thread marked as running the second one, and fail with ReentrantTransitionException.
     */
    @Test
    void aCallQueuedBehindACallThatBlocksInJoinRunsOnItsOwn() throws Exception {
        Doc queued = new Doc("queued", DRAFT);
        Doc blocking = new Doc("blocking", DRAFT);
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        List<CompletableFuture<Outcome<Doc, DocStatus, DocTransition>>> followUps = new CopyOnWriteArrayList<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(graph(
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    if (ctx.entity() == blocking) {
                        new CompletableFuture<String>().completeOnTimeout("done", 300, TimeUnit.MILLISECONDS).join();
                    }
                    return ActionOutcome.ok();
                },
                (Doc doc) -> {
                    if (doc.id().equals("first")) {
                        followUps.add(self.get().async().execute(queued, SUBMIT));
                        followUps.add(self.get().async().execute(blocking, SUBMIT));
                    }
                })).state(Doc::status, Doc::setStatus).asyncExecutor(ForkJoinPool.commonPool()).build();
        self.set(machine);
        List<String> ends = new ArrayList<>();
        OneFreeWorker pool = new OneFreeWorker(false);
        try {
            ends.add(endOf(machine.async().execute(new Doc("first", DRAFT), SUBMIT)));
            for (CompletableFuture<Outcome<Doc, DocStatus, DocTransition>> followUp : followUps) {
                ends.add(endOf(followUp));
            }
        } finally {
            pool.close();
        }
        assertEquals("[APPLIED, APPLIED, APPLIED], queued REVIEW, blocking REVIEW",
                ends + ", queued " + queued.status() + ", blocking " + blocking.status());
    }

    /**
     * An action of a call on a common-pool worker submits a call through supplyAsync, which queues it on that worker,
     * and joins it, while the pool's other workers wait in CompletableFuture's get, where the pool sees them. The
     * join does not run the submitted call on the action's thread: the pool wakes or starts another worker in its
     * place, which runs the call, and both calls apply. Run inside the join, the submitted call would fail with
     * ReentrantTransitionException. Had the other workers waited where the pool cannot see them, the join would wait
     * for one of them to free up, as the guide says.
     */
    @Test
    void aCallAnActionSubmitsAndJoinsRunsOnAnotherThread() throws Exception {
        Doc inner = new Doc("inner", DRAFT);
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        AtomicReference<String> innerEnd = new AtomicReference<>("not submitted");
        StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(graph(
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    if (ctx.entity().id().equals("outer")) {
                        Thread action = Thread.currentThread();
                        innerEnd.set(self.get().supplyAsync(() ->
                                (Thread.currentThread() == action ? "on the action's thread " : "on another thread ")
                                        + end(() -> self.get().execute(inner, SUBMIT))).join());
                    }
                    return ActionOutcome.ok();
                },
                (Doc doc) -> { })).state(Doc::status, Doc::setStatus).asyncExecutor(ForkJoinPool.commonPool()).build();
        self.set(machine);
        String outerEnd;
        OneFreeWorker pool = new OneFreeWorker(true);
        try {
            outerEnd = endOf(machine.async().execute(new Doc("outer", DRAFT), SUBMIT));
        } finally {
            pool.close();
        }
        assertEquals("outer APPLIED, inner on another thread APPLIED, inner REVIEW",
                "outer " + outerEnd + ", inner " + innerEnd.get() + ", inner " + inner.status());
    }

    /**
     * A call that throws fails its future as CompletableFuture.supplyAsync fails the one it returns: join throws a
     * CompletionException and get an ExecutionException, each caused by what the call threw, and a dependent stage
     * sees the CompletionException, whose message is the string of what the call threw.
     */
    @Test
    void aCallThatThrowsFailsItsFutureAsSupplyAsyncDoes() throws Exception {
        IllegalStateException thrown = new IllegalStateException("thrown");
        ExecutorService executor = single(new CopyOnWriteArrayList<>());
        try {
            CompletableFuture<String> future = idle(executor).supplyAsync(() -> {
                throw thrown;
            });
            assertSame(thrown, assertThrows(CompletionException.class, future::join).getCause());
            assertSame(thrown, assertThrows(ExecutionException.class, future::get).getCause());
            Throwable seen = future.handle((String value, Throwable error) -> error).get(10, TimeUnit.SECONDS);
            assertInstanceOf(CompletionException.class, seen);
            assertSame(thrown, seen.getCause());
            assertEquals("java.lang.IllegalStateException: thrown", seen.getMessage());
        } finally {
            executor.close();
        }
    }

    /**
     * A call that throws a CompletionException fails its future with that very exception, as
     * CompletableFuture.supplyAsync does: join throws it and a dependent stage sees it, never wrapped in another one,
     * and get throws an ExecutionException caused by its cause.
     */
    @Test
    void aCompletionExceptionACallThrowsIsKeptAsItIs() throws Exception {
        IllegalStateException cause = new IllegalStateException("cause");
        CompletionException thrown = new CompletionException(cause);
        ExecutorService executor = single(new CopyOnWriteArrayList<>());
        try {
            CompletableFuture<String> future = idle(executor).supplyAsync(() -> {
                throw thrown;
            });
            assertSame(thrown, assertThrows(CompletionException.class, future::join));
            assertSame(cause, assertThrows(ExecutionException.class, future::get).getCause());
            assertSame(thrown, future.handle((String value, Throwable error) -> error).get(10, TimeUnit.SECONDS));
        } finally {
            executor.close();
        }
    }

    /**
     * An Error a call throws fails its future, wrapped in a CompletionException, as CompletableFuture.supplyAsync
     * does, and never escapes the task on the executor's thread: the thread's uncaught exception handler hears
     * nothing, and the same thread runs the executor's next task.
     */
    @Test
    void anErrorACallThrowsFailsItsFutureAndStaysOffTheExecutorThread() throws Exception {
        AssertionError thrown = new AssertionError("thrown");
        List<Throwable> escaped = new CopyOnWriteArrayList<>();
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        ExecutorService executor = single(escaped);
        try {
            CompletableFuture<String> future = idle(executor).supplyAsync(() -> {
                ranOn.set(Thread.currentThread());
                throw thrown;
            });
            assertSame(thrown, assertThrows(CompletionException.class, future::join).getCause());
            Thread next = executor.submit(Thread::currentThread).get(10, TimeUnit.SECONDS);
            assertSame(ranOn.get(), next);
            assertEquals(List.of(), escaped);
        } finally {
            executor.close();
        }
    }

    /**
     * A throwable whose message cannot be read still fails the future of the call that threw it, where
     * CompletableFuture.supplyAsync leaves its future pending on JDK 21: an Error an action throws through
     * async().execute, and a runtime exception a call submitted through supplyAsync throws. Each future fails with a
     * CompletionException caused by that throwable, whose message is {@code <toString threw <class name>>}, join
     * throws that CompletionException, and nothing escapes on the executor's thread. get throws what get throws on a
     * plain CompletableFuture failed with such a CompletionException on the running JDK, as the guide says: what
     * reading the message throws on JDK 21, and an ExecutionException caused by the throwable on JDK 25.
     */
    @Test
    void aThrowableWhoseMessageCannotBeReadStillFailsItsFuture() throws Exception {
        UnreadableError error = new UnreadableError();
        UnreadableFailure failure = new UnreadableFailure();
        List<Throwable> escaped = new CopyOnWriteArrayList<>();
        ExecutorService executor = single(escaped);
        try {
            StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(graph(
                    (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        throw error;
                    },
                    (Doc doc) -> { })).state(Doc::status, Doc::setStatus).asyncExecutor(executor).build();
            CompletableFuture<Outcome<Doc, DocStatus, DocTransition>> executed =
                    machine.async().execute(new Doc("a", DRAFT), SUBMIT);
            CompletableFuture<String> supplied = machine.supplyAsync(() -> {
                throw failure;
            });
            Throwable executedFailure = failureOf(executed);
            Throwable suppliedFailure = failureOf(supplied);
            assertInstanceOf(CompletionException.class, executedFailure);
            assertSame(error, executedFailure.getCause());
            assertEquals("<toString threw java.lang.IllegalStateException>", executedFailure.getMessage());
            assertSame(executedFailure, assertThrows(CompletionException.class, executed::join));
            assertInstanceOf(CompletionException.class, suppliedFailure);
            assertSame(failure, suppliedFailure.getCause());
            assertEquals("<toString threw java.lang.IllegalStateException>", suppliedFailure.getMessage());
            assertSame(suppliedFailure, assertThrows(CompletionException.class, supplied::join));
            CompletableFuture<String> plain = new CompletableFuture<>();
            plain.completeExceptionally(
                    new CompletionException("<toString threw java.lang.IllegalStateException>", failure));
            assertEquals(described(assertThrows(Throwable.class, plain::get), failure),
                    described(assertThrows(Throwable.class, supplied::get), failure));
            executor.submit(() -> { }).get(10, TimeUnit.SECONDS);
            assertEquals(List.of(), escaped);
        } finally {
            executor.close();
        }
    }

    /**
     * A throwable whose toString throws an Error still fails the future of the call that threw it, with a
     * CompletionException caused by that throwable whose message is {@code <toString threw <class name>>}, naming
     * the Error; neither the throwable nor that Error escapes on the executor's thread.
     */
    @Test
    void aThrowableWhoseToStringThrowsAnErrorStillFailsItsFuture() throws Exception {
        FatallyUnprintableFailure thrown = new FatallyUnprintableFailure();
        List<Throwable> escaped = new CopyOnWriteArrayList<>();
        ExecutorService executor = single(escaped);
        try {
            CompletableFuture<String> future = idle(executor).supplyAsync(() -> {
                throw thrown;
            });
            Throwable seen = failureOf(future);
            assertInstanceOf(CompletionException.class, seen);
            assertSame(thrown, seen.getCause());
            assertEquals("<toString threw java.lang.AssertionError>", seen.getMessage());
            executor.submit(() -> { }).get(10, TimeUnit.SECONDS);
            assertEquals(List.of(), escaped);
        } finally {
            executor.close();
        }
    }

    /**
     * A future cancelled or completed before the executor reaches its call never runs that call, as
     * CompletableFuture.supplyAsync skips the supplier of a future already done; the future keeps how it was ended.
     */
    @Test
    void aFutureEndedBeforeItsTurnNeverRunsItsCall() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        List<String> ran = new CopyOnWriteArrayList<>();
        ExecutorService executor = single(new CopyOnWriteArrayList<>());
        try {
            StateMachine<Doc, DocStatus, DocTransition> machine = idle(executor);
            executor.execute(() -> {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            CompletableFuture<String> cancelled = machine.supplyAsync(() -> {
                ran.add("cancelled");
                return "ran";
            });
            CompletableFuture<String> completed = machine.supplyAsync(() -> {
                ran.add("completed");
                return "ran";
            });
            assertTrue(cancelled.cancel(true));
            assertTrue(completed.complete("early"));
            gate.countDown();
            executor.submit(() -> { }).get(10, TimeUnit.SECONDS);
            assertEquals(List.of(), ran);
            assertTrue(cancelled.isCancelled());
            assertEquals("early", completed.join());
        } finally {
            executor.close();
        }
    }

    /**
     * With ForkJoinPool.commonPool() as the executor and the common pool's parallelism set to 0 or to 1, a call
     * submitted through async().execute and one submitted through supplyAsync each run on a new thread, never on a
     * common-pool worker, as CompletableFuture.supplyAsync sends a task meant for that pool: with parallelism 0 the
     * pool starts no worker, and a call queued there would never run. Each setting runs in a JVM of its own, since the
     * common pool reads it once; that JVM bounds each of its waits to ten seconds.
     */
    @Test
    void aCommonPoolOfFewerThanTwoThreadsRunsEachCallOnANewThread() throws Exception {
        List<String> ends = new ArrayList<>();
        for (String parallelism : List.of("0", "1")) {
            ends.add("parallelism " + parallelism + ": " + commonPoolCalls(parallelism));
        }
        assertEquals(List.of("parallelism 0: execute APPLIED off the pool, supplyAsync off the pool",
                "parallelism 1: execute APPLIED off the pool, supplyAsync off the pool"), ends);
    }

    /**
     * What {@link CommonPoolCalls} prints in a JVM whose common pool has {@code parallelism}, followed by its exit
     * code when that is not 0.
     */
    private static String commonPoolCalls(String parallelism) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.util.concurrent.ForkJoinPool.common.parallelism=" + parallelism,
                "-cp", System.getProperty("java.class.path"), CommonPoolCalls.class.getName())
                .redirectErrorStream(true).start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                return output + " (no exit after 30 s)";
            }
            return process.exitValue() == 0 ? output : output + " (exit code " + process.exitValue() + ")";
        } finally {
            process.destroyForcibly();
        }
    }

    /**
     * Runs a call through async().execute and one through supplyAsync on a machine whose executor is the common pool,
     * and prints how each ended and whether it ran on a common-pool worker.
     */
    public static final class CommonPoolCalls {

        public static void main(String[] args) throws InterruptedException, ExecutionException {
            AtomicReference<Thread> actionRanOn = new AtomicReference<>();
            StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(graph(
                    (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        actionRanOn.set(Thread.currentThread());
                        return ActionOutcome.ok();
                    },
                    (Doc doc) -> { })).state(Doc::status, Doc::setStatus).asyncExecutor(ForkJoinPool.commonPool())
                    .build();
            String executed;
            try {
                executed = machine.async().execute(new Doc("a", DRAFT), SUBMIT).get(10, TimeUnit.SECONDS).status()
                        .name() + " " + where(actionRanOn.get() instanceof ForkJoinWorkerThread);
            } catch (TimeoutException e) {
                executed = "no end after 10 s";
            }
            String supplied;
            try {
                supplied = where(machine.supplyAsync(() -> Thread.currentThread() instanceof ForkJoinWorkerThread)
                        .get(10, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                supplied = "no end after 10 s";
            }
            System.out.println("execute " + executed + ", supplyAsync " + supplied);
        }

        private static String where(boolean worker) {
            return worker ? "on a common-pool worker" : "off the pool";
        }
    }
}
