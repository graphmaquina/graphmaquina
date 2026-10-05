package io.github.graphmaquina.runtime;

import io.github.graphmaquina.error.StateMachineException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The engine's only waiting primitive. Joins a foreign stage to a future of its own: it never invokes
 * orTimeout, completeOnTimeout or cancel on anything belonging to the user. join() ignores interruption; get() does not.
 * A deadline of Long.MAX_VALUE means no limit. Any other deadline is only ever subtracted from, so one that wrapped
 * past Long.MAX_VALUE when its budget was added still leaves the exact time remaining. A stage that completes with a
 * StateMachineException rethrows it as is; any other exceptional completion surfaces as an ActionFailure, an Error
 * included: the engine learns of that Error only through the stage, so it reports it as the cause of an abort, while
 * an Error thrown as itself on the calling thread propagates unchanged.
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

    /** The waiting thread was interrupted before the stage completed. */
    static final class Interrupted extends RuntimeException {
        Interrupted(InterruptedException cause) {
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
        stage.whenComplete((T value, Throwable error) -> {
            if (error != null) {
                owned.completeExceptionally(error);
            } else {
                owned.complete(value);
            }
        });
        long remaining = deadlineNanos == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0L, deadlineNanos - System.nanoTime());
        try {
            return owned.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new Timeout(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Interrupted(e);
        } catch (CancellationException e) {
            throw new ActionFailure(e);
        } catch (ExecutionException e) {
            Throwable cause = Unwrap.unwrap(e);
            if (cause instanceof StateMachineException stateMachineError) {
                throw stateMachineError;
            }
            throw new ActionFailure(cause);
        }
    }
}
