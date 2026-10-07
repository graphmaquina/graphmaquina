package io.github.graphmaquina.runtime;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The engine's only waiting primitive. Joins a foreign stage to a future of its own: it never invokes
 * orTimeout, completeOnTimeout or cancel on anything belonging to the user. The thread blocks on a latch that the
 * stage's completion counts down, never in CompletableFuture's get or join: called on a ForkJoinPool worker, those
 * may first run other queued asynchronous tasks on the waiting thread, which is the transaction's own, so such a task
 * would run with the thread marked as running a transition and the contexts usable, and could hold the wait past its
 * deadline. The waiting thread never runs another task. The latch is awaited through ForkJoinPool.managedBlock, as
 * CompletableFuture's own waits are, so a pool whose worker blocks here can start a spare worker. As in
 * CompletableFuture's own blocker, the deadline and the interrupt are read again before the thread blocks: the time
 * the pool takes to start a spare worker counts against the deadline, and a wait that an interrupt or a spent budget
 * has already ended never asks the pool for one. A pool that refuses to let its worker block, as a ForkJoinPool does
 * with RejectedExecutionException when it cannot replace the blocked worker, ends the wait with Rejected, which the
 * engine reports as an abort, as it does Timeout and Interrupted. As with CompletableFuture's get, a stage complete
 * when the wait begins is taken as it is, even on an interrupted thread; otherwise, once the wait ends, an interrupt
 * comes first, then completion, then the deadline. A wait that an interrupt ends leaves the thread interrupted. Once
 * complete, the stage's outcome is read with get, which then returns or throws at once.
 * A deadline of Long.MAX_VALUE means no limit. Any other deadline is only ever subtracted from, so one that wrapped
 * past Long.MAX_VALUE when its budget was added still leaves the exact time remaining. Any exceptional
 * completion surfaces as an ActionFailure, a library exception and an Error included: the engine learns of that
 * Error only through the stage, so it reports it as the cause of an abort, while an Error thrown as itself on the
 * calling thread propagates unchanged.
 */
final class Await {

    /** At or above this budget, its nanoseconds no longer fit below Long.MAX_VALUE: the budget means no limit. */
    private static final Duration UNBOUNDED = Duration.ofNanos(Long.MAX_VALUE);

    /** The wait exceeded its deadline; the user's stage is left running. */
    static final class Timeout extends RuntimeException {
        Timeout(TimeoutException cause) {
            super(cause);
        }
    }

    /** The thread was interrupted before or during its wait for a stage that was not complete when the wait began. */
    static final class Interrupted extends RuntimeException {
        Interrupted(InterruptedException cause) {
            super(cause);
        }
    }

    /**
     * The ForkJoinPool of the waiting worker refused to let it block, because it could not replace the blocked
     * worker; the user's stage is left running.
     */
    static final class Rejected extends RuntimeException {
        Rejected(RejectedExecutionException cause) {
            super(cause);
        }
    }

    private Await() { }

    /**
     * A budget in nanoseconds that saturates instead of overflowing: {@code Duration.ofNanos(Long.MAX_VALUE)} or more
     * is Long.MAX_VALUE, which means no limit. It never throws for a positive budget, the only kind its callers pass,
     * so "1000 years" or {@code ChronoUnit.FOREVER} work as the "forever" they are written to mean.
     */
    static long budgetNanos(Duration budget) {
        return budget.compareTo(UNBOUNDED) >= 0 ? Long.MAX_VALUE : budget.toNanos();
    }

    static <T> T await(CompletionStage<T> stage, long deadlineNanos) {
        CompletableFuture<T> owned = new CompletableFuture<>();
        CountDownLatch completed = new CountDownLatch(1);
        stage.whenComplete((T value, Throwable error) -> {
            if (error != null) {
                owned.completeExceptionally(error);
            } else {
                owned.complete(value);
            }
            completed.countDown();
        });
        try {
            if (!owned.isDone()) {
                block(completed, deadlineNanos);
                if (!owned.isDone()) {
                    throw new Timeout(new TimeoutException());
                }
            }
            return owned.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Interrupted(e);
        } catch (CancellationException e) {
            throw new ActionFailure(e);
        } catch (ExecutionException e) {
            throw new ActionFailure(e);
        }
    }

    /** The time left before {@code deadlineNanos}, Long.MAX_VALUE when it sets no limit; zero or less once passed. */
    private static long left(long deadlineNanos) {
        return deadlineNanos == Long.MAX_VALUE ? Long.MAX_VALUE : deadlineNanos - System.nanoTime();
    }

    /**
     * Waits until {@code completed} is counted down, the deadline passes or the thread is interrupted. Unless the
     * deadline or an interrupt has already ended the wait, it is a managed block that, as CompletableFuture's own
     * blocker does, reads the deadline and the interrupt again before it blocks, and measures what is left of the
     * deadline only once the pool has compensated; a pool that refuses to compensate makes it throw Rejected. Last,
     * it checks for an interrupt before completion, and throws InterruptedException on an interrupted thread.
     */
    private static void block(CountDownLatch completed, long deadlineNanos) throws InterruptedException {
        if (left(deadlineNanos) > 0L && !Thread.currentThread().isInterrupted()) {
            try {
                ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker() {
                    @Override
                    public boolean block() throws InterruptedException {
                        completed.await(left(deadlineNanos), TimeUnit.NANOSECONDS);
                        return true;
                    }

                    @Override
                    public boolean isReleasable() {
                        return completed.getCount() == 0L || left(deadlineNanos) <= 0L
                                || Thread.currentThread().isInterrupted();
                    }
                });
            } catch (RejectedExecutionException e) {
                throw new Rejected(e);
            }
        }
        completed.await(0L, TimeUnit.NANOSECONDS);
    }
}
