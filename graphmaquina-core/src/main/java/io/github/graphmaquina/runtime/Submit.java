package io.github.graphmaquina.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Submits a call to a machine's executor as a plain Runnable that completes a new future the way
 * CompletableFuture.supplyAsync completes the one it returns: a future already done when the task runs, cancelled or
 * completed meanwhile, skips the call, and anything the call throws, an Error included, fails the future, as it is
 * when it is a CompletionException and wrapped in a new one otherwise, so that nothing escapes on the executor's
 * thread. supplyAsync submits a CompletableFuture.AsynchronousCompletionTask instead, which CompletableFuture's get
 * and join run on a ForkJoinPool worker that waits while the task is queued on it: the call would run inside the
 * callback that waits, on a thread marked as running a transition, and fail with ReentrantTransitionException.
 */
final class Submit {

    /** Runs each task on a new thread, as CompletableFuture does when the common pool's parallelism is below two. */
    private static final Executor THREAD_PER_TASK = (Runnable task) -> new Thread(task).start();

    private Submit() { }

    /**
     * The executor that runs what a machine submits to {@code executor}: a new thread per call when it is
     * ForkJoinPool.commonPool() and the common pool's parallelism is below two, as CompletableFuture.supplyAsync does
     * with that pool, and {@code executor} itself otherwise. A common pool whose parallelism is set to 0 starts no
     * worker, so a plain task queued there would never run.
     */
    static Executor screen(Executor executor) {
        if (executor == ForkJoinPool.commonPool() && ForkJoinPool.getCommonPoolParallelism() <= 1) {
            return THREAD_PER_TASK;
        }
        return executor;
    }

    /** A submission the executor refuses returns a failed future. */
    static <R> CompletableFuture<R> supply(Executor executor, Supplier<R> call) {
        CompletableFuture<R> future = new CompletableFuture<>();
        try {
            executor.execute(() -> run(future, call));
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
        return future;
    }

    private static <R> void run(CompletableFuture<R> future, Supplier<R> call) {
        if (future.isDone()) {
            return;
        }
        try {
            future.complete(call.get());
        } catch (Throwable thrown) {
            future.completeExceptionally(
                    thrown instanceof CompletionException ? thrown : new CompletionException(thrown));
        }
    }
}
