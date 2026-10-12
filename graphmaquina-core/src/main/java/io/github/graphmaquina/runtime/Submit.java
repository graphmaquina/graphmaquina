package io.github.graphmaquina.runtime;

import io.github.graphmaquina.internal.Texts;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Submits a call to a machine's executor as a plain Runnable that completes a new future the way
 * CompletableFuture.supplyAsync completes the one it returns, save for a throwable that cannot be printed: a future
 * already done when the task runs, cancelled or completed meanwhile, skips the call, and anything the call throws, an
 * Error included, fails the future, as it is when it is a CompletionException and wrapped in a new one otherwise, so
 * that nothing escapes on the executor's thread. The new CompletionException's message is the string of what the call
 * threw, as supplyAsync's is, {@code <toString returned null>} when that string is null, where supplyAsync's message
 * is null, or {@code <toString threw <class name>>} when printing it throws; supplyAsync reads that string to
 * construct its CompletionException, so a throwable whose printing throws leaves its future pending.
 * supplyAsync submits a CompletableFuture.AsynchronousCompletionTask instead, which CompletableFuture's get and join
 * run on a ForkJoinPool worker that waits while the task is queued on it: the call would run inside the callback that
 * waits, on a thread marked as running a transition, and fail with ReentrantTransitionException.
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
                    thrown instanceof CompletionException ? thrown : new CompletionException(shown(thrown), thrown));
        }
    }

    /**
     * The message of the CompletionException that wraps {@code thrown}: its string, which CompletionException's
     * one-argument constructor would read, printed as {@link Texts#shown} prints a value, and
     * {@code <toString threw <class name>>}, naming the Error, when printing it throws an Error, so that no failure to
     * print it keeps the future from failing.
     */
    private static String shown(Throwable thrown) {
        try {
            return Texts.shown(thrown);
        } catch (Error fatal) {
            return "<toString threw " + fatal.getClass().getName() + ">";
        }
    }
}
