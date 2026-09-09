package io.github.graphmaquina.runtime;

import io.github.graphmaquina.error.StateMachineException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The engine's only waiting primitive. Joins a foreign stage to a future of its own: it never invokes
 * orTimeout, completeOnTimeout or cancel on anything belonging to the user. join() ignores interruption; get() does not.
 */
final class Await {

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
