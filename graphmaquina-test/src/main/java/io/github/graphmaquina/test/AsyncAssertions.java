package io.github.graphmaquina.test;

import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.test.internal.Checks;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bounded waits that turn an asynchronous transition into the same typed views a synchronous one produces.
 * Each wait takes a budget of its own, which belongs to the test and not to the machine: when it expires the
 * failure says the test stopped waiting, never that the engine timed out, and the stage is left exactly as it
 * was — neither wait ever cancels a stage it was handed. A stage is awaited through a callback of its own, as
 * the engine awaits one, so even a stage that refuses {@link CompletionStage#toCompletableFuture()} can be
 * handed in. Neither the wait nor that callback prints the throwable a stage ended with, so {@link #awaitFailure}
 * hands one whose message cannot be read on like any other, and {@link #await} reports it as
 * {@link GraphAssertionError} reports a value it cannot print: an {@link Error} that printing it throws
 * propagates out of {@link #await}, as it propagates out of that error's constructor. The thread that completes
 * the stage never receives an exception from the callback.
 *
 * <p>The two entry points split the two things a stage can do. {@link #await} expects a result and reports
 * anything else — an exceptional completion, a cancellation, an expired budget — as a
 * {@link GraphAssertionError}. {@link #awaitFailure} expects the opposite: it unwraps the
 * {@link ExecutionException} of its own wait and hands the cause on for inspection, accepts a cancellation as
 * an observable failure too, and reports a stage that completed normally as the unmet expectation.
 */
public final class AsyncAssertions {

    private static final String CONTEXT = "async test";

    private AsyncAssertions() { }

    /** Waits for the outcome the stage carries; every other ending is a discrepancy, not a subject. */
    public static <E, S extends Enum<S>, T extends Enum<T>> OutcomeAssert<E, S, T> await(
            CompletionStage<? extends Outcome<E, S, T>> stage, Duration budget) {
        long nanos = budgetNanos(budget);
        CompletionStage<? extends Outcome<E, S, T>> awaited =
                Checks.subject(CONTEXT, "completion", "a stage to await", stage);
        try {
            return OutcomeAssert.assertThat(join(awaited, nanos));
        } catch (TimeoutException timeout) {
            throw new GraphAssertionError(CONTEXT, "completion", "within " + budget, "test wait expired");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GraphAssertionError(CONTEXT, "completion", "completed", "test thread interrupted");
        } catch (CancellationException cancelled) {
            throw new GraphAssertionError(CONTEXT, "outcome", "normal result", cancelled);
        } catch (ExecutionException execution) {
            throw new GraphAssertionError(CONTEXT, "outcome", "normal result", execution.getCause());
        }
    }

    /** Waits for the throwable the stage ended with, and hands it to the view that knows the phases apart. */
    public static TransitionFailureAssert awaitFailure(CompletionStage<?> stage, Duration budget) {
        long nanos = budgetNanos(budget);
        CompletionStage<?> awaited = Checks.subject(CONTEXT, "failure", "a stage to await", stage);
        Object completed;
        try {
            completed = join(awaited, nanos);
        } catch (TimeoutException timeout) {
            throw new GraphAssertionError(CONTEXT, "completion", "within " + budget, "test wait expired");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GraphAssertionError(CONTEXT, "completion", "completed", "test thread interrupted");
        } catch (CancellationException cancelled) {
            return TransitionFailureAssert.assertThat(cancelled);
        } catch (ExecutionException execution) {
            return TransitionFailureAssert.assertThat(execution.getCause());
        }
        throw new GraphAssertionError(CONTEXT, "failure", "an exception", completed);
    }

    /**
     * Waits through a future of this class's own, completed by a handle callback on the stage, which is how the
     * engine waits as well. A stage is never converted to a future, since a stage may refuse that, and never
     * cancelled. The throwable a stage ended with is never printed, since printing it may throw: the callback
     * returns normally, so the stage constructs no CompletionException around it on the thread that completes it,
     * and the wait reads it back through another handle, not through get, which constructs its ExecutionException
     * from the throwable's string. It ends the wait as get would: a CancellationException as itself, any other
     * throwable as the cause of an ExecutionException, once unwrapped from a CompletionException that carries a
     * cause.
     */
    private static <T> T join(CompletionStage<? extends T> stage, long nanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<T> owned = new CompletableFuture<>();
        stage.handle((T value, Throwable error) -> {
            if (error != null) {
                owned.completeExceptionally(error);
            } else {
                owned.complete(value);
            }
            return null;
        });
        Throwable error = owned.handle((T value, Throwable thrown) -> thrown).get(nanos, TimeUnit.NANOSECONDS);
        if (error == null) {
            return owned.resultNow();
        }
        if (error instanceof CancellationException cancelled) {
            throw cancelled;
        }
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        throw new ExecutionException(null, cause);
    }

    private static long budgetNanos(Duration budget) {
        Objects.requireNonNull(budget, "budget");
        if (budget.isZero() || budget.isNegative()) {
            throw new IllegalArgumentException("the budget must be positive, not " + budget);
        }
        try {
            return budget.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("the budget does not fit in nanoseconds: " + budget, overflow);
        }
    }
}
