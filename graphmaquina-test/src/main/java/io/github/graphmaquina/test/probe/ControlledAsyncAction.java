package io.github.graphmaquina.test.probe;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.TransitionContext;
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
 * An {@link AsyncTransitionAction} that never answers on its own: every invocation parks on a stage of its
 * own and stays there until the test answers it through the {@link PendingInvocation} handed out by
 * {@link #awaitInvocation(Duration)}. That is what replaces sleeping — the test resumes exactly when the
 * engine has entered the action, and the engine resumes exactly when the test says so.
 *
 * <p>Concurrent invocations are independent: each gets its own handle and its own stage. History records an
 * invocation when it enters, before the {@link #onCall} side effect runs. A handle is published only after
 * that side effect returns successfully, so {@link #awaitInvocation(Duration)} observes queue publication
 * order, which can differ from history entry order. This does not promise callback completion order because
 * a thread can pause before publishing its handle. If the side effect throws, the invocation remains in
 * history, but its exception settles the stage and its handle is not delivered.
 *
 * <p>{@link #close()} is idempotent. It cancels the stages of this fixture's own pending invocations — never
 * a stage the test handed in from outside — so a worker blocked inside the action is released before a
 * registry waits for it, and it refuses further invocations. Closing is the fixture's own teardown and is
 * meant to run before the registry is closed.
 *
 * @param <E> the entity type of the machine the action belongs to
 * @param <S> the state enum of that machine
 */
public final class ControlledAsyncAction<E, S extends Enum<S>>
        implements AsyncTransitionAction<E, S>, AutoCloseable {

    private static final String CONTEXT = "controlled async action";

    private final BlockingQueue<PendingInvocation<ActionCall<E, S>, ActionOutcome>> arrivals =
            new LinkedBlockingQueue<>();
    private final List<PendingInvocation<ActionCall<E, S>, ActionOutcome>> invocations = new ArrayList<>();
    private volatile Consumer<TransitionContext<E, S>> onCall = context -> { };
    private boolean closed;

    private ControlledAsyncAction() { }

    public static <E, S extends Enum<S>> ControlledAsyncAction<E, S> create() {
        return new ControlledAsyncAction<>();
    }

    /**
     * Replaces the side effect run on the engine's thread as each invocation is entered. It receives the live
     * context, which the engine invalidates when the transaction ends, so read it now rather than storing it.
     */
    public ControlledAsyncAction<E, S> onCall(Consumer<TransitionContext<E, S>> onCall) {
        this.onCall = Objects.requireNonNull(onCall, "onCall");
        return this;
    }

    /**
     * Waits up to {@code budget} for the next invocation nobody has taken yet, and hands out its handle. An
     * invocation that arrived earlier is returned at once. Raises {@link GraphAssertionError} when the budget
     * expires, since a wait that was not met is a failed expectation of the test and not of the machine.
     */
    public PendingInvocation<ActionCall<E, S>, ActionOutcome> awaitInvocation(Duration budget) {
        long nanos = budgetNanos(budget);
        try {
            PendingInvocation<ActionCall<E, S>, ActionOutcome> arrival =
                    arrivals.poll(nanos, TimeUnit.NANOSECONDS);
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
    public List<PendingInvocation<ActionCall<E, S>, ActionOutcome>> invocations() {
        synchronized (invocations) {
            return List.copyOf(invocations);
        }
    }

    @Override
    public CompletionStage<ActionOutcome> process(TransitionContext<E, S> context) {
        PendingInvocation<ActionCall<E, S>, ActionOutcome> pending = new PendingInvocation<>(
                new ActionCall<>(context.entity(), context.from(), context.to(), context.depth()));
        synchronized (invocations) {
            if (closed) {
                throw new IllegalStateException("the controlled action is closed and takes no more invocations");
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
        List<PendingInvocation<ActionCall<E, S>, ActionOutcome>> entered;
        synchronized (invocations) {
            if (closed) {
                return;
            }
            closed = true;
            entered = List.copyOf(invocations);
        }
        for (PendingInvocation<ActionCall<E, S>, ActionOutcome> pending : entered) {
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
