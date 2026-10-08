package io.github.graphmaquina.test.probe;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.TransitionContext;
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
 * An {@link AsyncTransitionAction} that never answers on its own: every invocation parks on a stage of its
 * own and stays there until the test answers it through the {@link PendingInvocation} handed out by
 * {@link #awaitInvocation(Duration)}, or until the engine's own deadline aborts the transition and leaves the
 * stage pending. That is what replaces sleeping — the test resumes when the engine has entered the action,
 * and the engine resumes when the test says so.
 *
 * <p>Concurrent invocations are independent: each gets its own handle and its own stage. History records an
 * invocation when it enters, before the {@link #onCall} side effect runs. A handle is published only after
 * that side effect returns successfully, so {@link #awaitInvocation(Duration)} observes queue publication
 * order, which can differ from history entry order. This does not promise callback completion order because
 * a thread can pause before publishing its handle. If the side effect throws, the invocation remains in
 * history, its exception settles the stage and is rethrown to the engine as well, and its handle is not
 * delivered.
 *
 * <p>{@link #close()} is idempotent. It cancels the stages of this fixture's own pending invocations — never
 * a stage the test handed in from outside — so a worker blocked inside the action is released before a
 * registry waits for it, and it refuses further invocations. A cancelled handle is nothing to answer: one
 * that nobody took, or that was still being published, is dropped, and a {@link #awaitInvocation(Duration)}
 * on a closed fixture fails at once with a {@link GraphAssertionError}, a wait that was already blocked when
 * the fixture closed included. An invocation that reaches a closed fixture is refused with that same error,
 * which is an {@link Error} so that neither a recovery rule nor the {@code failsOn} of the edge can take the
 * refusal for a failure of the action and commit a recovery. Closing is the fixture's own teardown and is
 * meant to run before the registry is closed.
 *
 * @param <E> the entity type of the machine the action belongs to
 * @param <S> the state enum of that machine
 * @param <T> the transition identifier enum of that machine
 */
public final class ControlledAsyncAction<E, S extends Enum<S>, T extends Enum<T>>
        implements AsyncTransitionAction<E, S, T>, AutoCloseable {

    private static final String CONTEXT = "controlled async action";

    private final List<PendingInvocation<ActionCall<E, S, T>, ActionOutcome>> invocations = new ArrayList<>();
    // The handles published and not yet taken, and the flag below, are guarded by the monitor of invocations,
    // which is also what a waiting awaitInvocation sleeps on, so that close can wake it.
    private final Deque<PendingInvocation<ActionCall<E, S, T>, ActionOutcome>> arrivals = new ArrayDeque<>();
    private volatile Consumer<TransitionContext<E, S, T>> onCall = context -> { };
    private boolean closed;

    private ControlledAsyncAction() { }

    public static <E, S extends Enum<S>, T extends Enum<T>> ControlledAsyncAction<E, S, T> create() {
        return new ControlledAsyncAction<>();
    }

    /**
     * Replaces the side effect run on the engine's thread as each invocation is entered. It receives the live
     * context, which the engine invalidates when the transaction ends, so read it now rather than storing it.
     */
    public ControlledAsyncAction<E, S, T> onCall(Consumer<TransitionContext<E, S, T>> onCall) {
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
    public PendingInvocation<ActionCall<E, S, T>, ActionOutcome> awaitInvocation(Duration budget) {
        long nanos = budgetNanos(budget);
        long start = System.nanoTime();
        synchronized (invocations) {
            while (true) {
                if (closed) {
                    throw closedFixture();
                }
                PendingInvocation<ActionCall<E, S, T>, ActionOutcome> arrival = arrivals.pollFirst();
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
    public List<PendingInvocation<ActionCall<E, S, T>, ActionOutcome>> invocations() {
        synchronized (invocations) {
            return List.copyOf(invocations);
        }
    }

    @Override
    public CompletionStage<ActionOutcome> process(TransitionContext<E, S, T> context) {
        PendingInvocation<ActionCall<E, S, T>, ActionOutcome> pending = new PendingInvocation<>(
                new ActionCall<>(context.entity(), context.from(), context.transition(), context.to(),
                        context.depth(), context.input()));
        synchronized (invocations) {
            if (closed) {
                throw closedFixture();
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
        List<PendingInvocation<ActionCall<E, S, T>, ActionOutcome>> entered;
        synchronized (invocations) {
            if (closed) {
                return;
            }
            closed = true;
            arrivals.clear();
            invocations.notifyAll();
            entered = List.copyOf(invocations);
        }
        for (PendingInvocation<ActionCall<E, S, T>, ActionOutcome> pending : entered) {
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
