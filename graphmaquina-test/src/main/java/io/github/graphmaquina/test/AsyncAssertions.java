package io.github.graphmaquina.test;

import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.test.internal.Checks;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bounded waits that turn an asynchronous transition into the same typed views a synchronous one produces.
 * Each wait takes a budget of its own, which belongs to the test and not to the machine: when it expires the
 * failure says the test stopped waiting, never that the engine timed out, and the stage is left exactly as it
 * was — neither wait ever cancels a stage it was handed.
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
            return OutcomeAssert.assertThat(
                    awaited.toCompletableFuture().get(nanos, TimeUnit.NANOSECONDS));
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
            completed = awaited.toCompletableFuture().get(nanos, TimeUnit.NANOSECONDS);
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
