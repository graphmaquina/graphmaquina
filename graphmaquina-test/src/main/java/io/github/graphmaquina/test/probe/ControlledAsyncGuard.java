package io.github.graphmaquina.test.probe;

import io.github.graphmaquina.api.AsyncTransitionGuard;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.test.GraphAssertionError;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * An {@link AsyncTransitionGuard} that never answers on its own. It behaves exactly as
 * {@link ControlledAsyncAction} does, with {@link GuardResult} in place of the action outcome and
 * {@link GuardCall} in place of the action call: every invocation parks on a stage of its own until the test
 * answers it, and concurrent invocations are independent. History records entry before {@link #onCall}; a
 * handle is published only after that side effect returns successfully, so delivery follows queue publication
 * order and does not promise callback completion order. A failed side effect remains in history but is not
 * delivered. {@link #close()} is idempotent, cancels this fixture's own pending stages and refuses further
 * invocations.
 *
 * <p>The engine evaluates the guards of an edge in declaration order and stops at the first rejection, so a
 * controlled guard placed after a rejecting guard is never invoked at all, and a rejection answered here
 * leaves the edge's action unreached.
 *
 * @param <E> the entity type of the machine the guard belongs to
 * @param <S> the state enum of that machine
 */
public final class ControlledAsyncGuard<E, S extends Enum<S>>
        implements AsyncTransitionGuard<E, S>, AutoCloseable {

    private static final String CONTEXT = "controlled async guard";

    private final BlockingQueue<PendingInvocation<GuardCall<E, S>, GuardResult>> arrivals =
            new LinkedBlockingQueue<>();
    private final List<PendingInvocation<GuardCall<E, S>, GuardResult>> invocations = new ArrayList<>();
    private volatile Consumer<GuardContext<E, S>> onCall = context -> { };
    private boolean closed;

    private ControlledAsyncGuard() { }

    public static <E, S extends Enum<S>> ControlledAsyncGuard<E, S> create() {
        return new ControlledAsyncGuard<>();
    }

    /**
     * Replaces the side effect run on the engine's thread as each invocation is entered. It receives the live
     * context, which the engine invalidates when the transaction ends, so read it now rather than storing it.
     */
    public ControlledAsyncGuard<E, S> onCall(Consumer<GuardContext<E, S>> onCall) {
        this.onCall = Objects.requireNonNull(onCall, "onCall");
        return this;
    }

    /**
     * Waits up to {@code budget} for the next invocation nobody has taken yet, and hands out its handle. An
     * invocation that arrived earlier is returned at once. Raises {@link GraphAssertionError} when the budget
     * expires, since a wait that was not met is a failed expectation of the test and not of the machine.
     */
    public PendingInvocation<GuardCall<E, S>, GuardResult> awaitInvocation(Duration budget) {
        long nanos = budgetNanos(budget);
        try {
            PendingInvocation<GuardCall<E, S>, GuardResult> arrival = arrivals.poll(nanos, TimeUnit.NANOSECONDS);
            if (arrival == null) {
                throw new GraphAssertionError(
                        CONTEXT, "invocation", "within " + budget, "no invocation arrived");
            }
            return arrival;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GraphAssertionError(CONTEXT, "invocation", "an invocation", "test thread interrupted");
        }
    }

    /**
     * Every invocation entered so far, in entry order, answered or not. The list is a copy. An invocation read
     * here may still be running its {@link #onCall} side effect; if that side effect fails, the invocation
     * remains in this history but is not delivered by {@link #awaitInvocation(Duration)}.
     */
    public List<PendingInvocation<GuardCall<E, S>, GuardResult>> invocations() {
        synchronized (invocations) {
            return List.copyOf(invocations);
        }
    }

    @Override
    public CompletionStage<GuardResult> test(GuardContext<E, S> context) {
        PendingInvocation<GuardCall<E, S>, GuardResult> pending =
                new PendingInvocation<>(new GuardCall<>(context.entity(), context.from(), context.to(), context.input()));
        synchronized (invocations) {
            if (closed) {
                throw new IllegalStateException("the controlled guard is closed and takes no more invocations");
            }
            invocations.add(pending);
        }
        try {
            onCall.accept(context);
        } catch (RuntimeException | Error failure) {
            pending.fail(failure);
            throw failure;
        }
        arrivals.add(pending);
        return pending.stage();
    }

    @Override
    public void close() {
        List<PendingInvocation<GuardCall<E, S>, GuardResult>> entered;
        synchronized (invocations) {
            if (closed) {
                return;
            }
            closed = true;
            entered = List.copyOf(invocations);
        }
        for (PendingInvocation<GuardCall<E, S>, GuardResult> pending : entered) {
            pending.cancelStage();
        }
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
