package io.github.graphmaquina.runtime;

import io.github.graphmaquina.internal.Interrupts;
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
 * that nothing escapes on the executor's thread; an InterruptedException it throws interrupts that thread again, which
 * supplyAsync's task does not do. The new CompletionException's message is the string of what the call threw, as
 * supplyAsync's is, {@code <toString returned null>} when that string is null, where supplyAsync's message is null, or
 * {@code <toString threw <class name>>} when printing it throws; supplyAsync reads that string to construct its
 * CompletionException, so a throwable whose printing throws leaves its future pending on JDK 21, while on JDK 25 it
 * fails that future with an empty message and adds what printing threw as a suppressed exception. supplyAsync submits a
 * CompletableFuture.AsynchronousCompletionTask instead, which CompletableFuture's get and join run on a ForkJoinPool
 * worker that waits while the task is queued on it: the call would run inside the callback that waits, on a thread
 * marked as running a transition, and fail with ReentrantTransitionException.
 * A task submitted to a machine's default executor marks its thread with that executor for as long as it runs, the
 * completion of its future and the dependents that completion runs included, so that {@link #runsTaskOf} tells
 * MachineRegistry.close() not to close that executor from its own task, which would wait for itself forever. A task of
 * an executor the application supplied marks nothing.
 */
final class Submit {

    /**
     * Runs each task on a new thread, as CompletableFuture does on JDK 21 when the common pool's parallelism is below
     * two.
     */
    private static final Executor THREAD_PER_TASK = (Runnable task) -> new Thread(task).start();

    /**
     * The cell of a thread that runs a task of a default executor: its only element is that executor until the task
     * ends, when a store releases it, as Reentrancy's marker is released. No other thread has a cell, and this
     * ThreadLocal has no initial value, so that reading it on any other thread, the thread that closes a registry for
     * one, finds none and leaves nothing on that thread that holds a class of the library.
     */
    private static final ThreadLocal<Executor[]> TASK_OF = new ThreadLocal<>();

    private Submit() { }

    /**
     * The executor that runs what a machine submits to {@code executor}: a new thread per call when it is
     * ForkJoinPool.commonPool() and the common pool's parallelism is below two, as CompletableFuture.supplyAsync does
     * with that pool on JDK 21, and {@code executor} itself otherwise. A common pool whose parallelism is set to 0
     * starts no worker, so a plain task queued there would never run. JDK 25's CompletableFuture runs its tasks on the
     * common pool whatever the pool's parallelism, and raises that parallelism from 0 to 2 when its class is
     * initialized, after which the parallelism read here is 2.
     */
    static Executor screen(Executor executor) {
        if (executor == ForkJoinPool.commonPool() && ForkJoinPool.getCommonPoolParallelism() <= 1) {
            return THREAD_PER_TASK;
        }
        return executor;
    }

    /**
     * A submission the executor refuses returns a failed future. {@code owned} says that {@code executor} is a
     * machine's default executor, which the task marks its thread with while it runs.
     */
    static <R> CompletableFuture<R> supply(Executor executor, boolean owned, Supplier<R> call) {
        CompletableFuture<R> future = new CompletableFuture<>();
        try {
            executor.execute(owned ? () -> runMarked(executor, future, call) : () -> run(future, call));
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
        return future;
    }

    /** Whether the current thread runs a task of {@code executor}, a default executor, as {@link #supply} marks it. */
    static boolean runsTaskOf(Executor executor) {
        Executor[] cell = TASK_OF.get();
        return cell != null && cell[0] == executor;
    }

    /**
     * Runs the task with its thread marked with {@code executor}. A default executor runs each task on a new virtual
     * thread, which has no cell before and ends with the task: the first statement of the finally releases the mark
     * with a store, which needs no stack, then the cell is removed.
     */
    private static <R> void runMarked(Executor executor, CompletableFuture<R> future, Supplier<R> call) {
        Executor[] cell = {executor};
        TASK_OF.set(cell);
        try {
            run(future, call);
        } finally {
            cell[0] = null;
            TASK_OF.remove();
        }
    }

    private static <R> void run(CompletableFuture<R> future, Supplier<R> call) {
        if (future.isDone()) {
            return;
        }
        try {
            future.complete(call.get());
        } catch (Throwable thrown) {
            Interrupts.keep(thrown);
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
