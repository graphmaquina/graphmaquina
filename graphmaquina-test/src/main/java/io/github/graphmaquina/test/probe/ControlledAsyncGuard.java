package io.github.graphmaquina.test.probe;

import io.github.graphmaquina.api.AsyncTransitionGuard;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.test.GraphAssertionError;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * An {@link AsyncTransitionGuard} that never answers on its own. It behaves as {@link ControlledAsyncAction}
 * does, with {@link GuardResult} in place of the action outcome and {@link GuardCall} in place of the action
 * call: every invocation parks on a stage of its own until the test answers it or the engine's own deadline
 * aborts the transition, and concurrent invocations are independent. History records entry before
 * {@link #onCall}; a handle is published only after that side effect returns successfully, so delivery
 * follows queue publication order and does not promise callback completion order. A failed side effect
 * remains in history, its exception settles the stage and is rethrown to the engine as well, and its handle
 * is not delivered. {@link #close()} is idempotent, cancels this fixture's own pending stages, drops the
 * handles nobody took and refuses further invocations, and a {@link #awaitInvocation(Duration)} on a closed
 * fixture fails at once with a {@link GraphAssertionError}, a wait that was already blocked when the fixture
 * closed included. The one difference is how an invocation that reaches a closed fixture is refused: here
 * with an {@link IllegalStateException}, which aborts the transition, since a guard has no recovery route
 * that could commit it.
 *
 * <p>The engine evaluates the guards of an edge in declaration order and stops at the first rejection, so a
 * controlled guard placed after a rejecting guard is never invoked at all, and a rejection answered here
 * leaves the edge's action unreached.
 *
 * @param <E> the entity type of the machine the guard belongs to
 * @param <S> the state enum of that machine
 * @param <T> the transition identifier enum of that machine
 */
public final class ControlledAsyncGuard<E, S extends Enum<S>, T extends Enum<T>>
        implements AsyncTransitionGuard<E, S, T>, AutoCloseable {

    private static final String CONTEXT = "controlled async guard";

    private final List<PendingInvocation<GuardCall<E, S, T>, GuardResult>> invocations = new ArrayList<>();
    // The handles published and not yet taken, and the flag below, are guarded by the monitor of invocations,
    // which is also what a waiting awaitInvocation sleeps on, so that close can wake it.
    private final Deque<PendingInvocation<GuardCall<E, S, T>, GuardResult>> arrivals = new ArrayDeque<>();
    private volatile Consumer<GuardContext<E, S, T>> onCall = context -> { };
    private boolean closed;

    private ControlledAsyncGuard() { }

    public static <E, S extends Enum<S>, T extends Enum<T>> ControlledAsyncGuard<E, S, T> create() {
        return new ControlledAsyncGuard<>();
    }

    /**
     * Replaces the side effect run on the engine's thread as each invocation is entered. It receives the live
     * context, which the engine invalidates when the transaction ends, so read it now rather than storing it.
     */
    public ControlledAsyncGuard<E, S, T> onCall(Consumer<GuardContext<E, S, T>> onCall) {
        this.onCall = Objects.requireNonNull(onCall, "onCall");
        return this;
    }

    /**
     * Waits up to {@code budget} for the next invocation nobody has taken yet, and hands out its handle. An
     * invocation that arrived earlier is returned at once. Raises {@link GraphAssertionError} when the budget
     * expires, since a wait that was not met is a failed expectation of the test and not of the machine, and
     * at once when the fixture is closed, since nothing can arrive any more. A wait that is already blocked
     * when the fixture closes is woken by the close and fails the same way.
     *
     * <p>Interruption matters only when the call has to wait. A caller whose interrupt flag is set still
     * receives a handle that is already queued, and the flag stays set; a caller that has to wait is released
     * by the interrupt with a {@link GraphAssertionError}, and the flag is set again. A budget that is not
     * positive, or that does not fit in nanoseconds, raises {@link IllegalArgumentException} and a null one
     * {@link NullPointerException}, whatever the state of the fixture.
     */
    public PendingInvocation<GuardCall<E, S, T>, GuardResult> awaitInvocation(Duration budget) {
        long nanos = budgetNanos(budget);
        long start = System.nanoTime();
        synchronized (invocations) {
            while (true) {
                if (closed) {
                    throw closedFixture();
                }
                PendingInvocation<GuardCall<E, S, T>, GuardResult> arrival = arrivals.pollFirst();
                if (arrival != null) {
                    return arrival;
                }
                long remaining = nanos - (System.nanoTime() - start);
                if (remaining <= 0) {
                    throw new GraphAssertionError(
                            CONTEXT, "invocation", "within " + budget, "no invocation arrived");
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(invocations, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new GraphAssertionError(
                            CONTEXT, "invocation", "an invocation", "test thread interrupted");
                }
            }
        }
    }

    /**
     * Every invocation entered so far, in entry order, answered or not. The list is a copy. An invocation read
     * here may still be running its {@link #onCall} side effect; if that side effect fails, the invocation
     * remains in this history but is not delivered by {@link #awaitInvocation(Duration)}.
     */
    public List<PendingInvocation<GuardCall<E, S, T>, GuardResult>> invocations() {
        synchronized (invocations) {
            return List.copyOf(invocations);
        }
    }

    @Override
    public CompletionStage<GuardResult> test(GuardContext<E, S, T> context) {
        PendingInvocation<GuardCall<E, S, T>, GuardResult> pending = new PendingInvocation<>(new GuardCall<>(
                context.entity(), context.from(), context.transition(), context.to(), context.input()));
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
        synchronized (invocations) {
            if (!closed) {
                arrivals.addLast(pending);
                invocations.notifyAll();
            }
        }
        return pending.stage();
    }

    @Override
    public void close() {
        List<PendingInvocation<GuardCall<E, S, T>, GuardResult>> entered;
        synchronized (invocations) {
            if (closed) {
                return;
            }
            closed = true;
            arrivals.clear();
            invocations.notifyAll();
            entered = List.copyOf(invocations);
        }
        for (PendingInvocation<GuardCall<E, S, T>, GuardResult> pending : entered) {
            pending.cancelStage();
        }
    }

    private static GraphAssertionError closedFixture() {
        return new GraphAssertionError(CONTEXT, "invocation", "an open fixture", "closed");
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
