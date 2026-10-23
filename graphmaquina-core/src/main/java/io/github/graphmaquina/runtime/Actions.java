package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionRef;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.internal.Interrupts;
import io.github.graphmaquina.internal.Texts;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * The action of a step: run sync or async, awaited under the edge's deadline, journaled, and turned into the step's
 * result, the failure route its failure selects, or an abort.
 */
final class Actions {

    private Actions() { }

    static <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> runAction(
            Transaction tx, DefaultStateMachine<E, S, T> machine, EntityRef ref, E entity,
            TransitionSpec<E, S, T> transition, int depth, int savepoint, Optional<Object> input) {
        UnitOfWork uow = tx.uow();
        ActionRef<E, S, T> action = transition.action().orElseThrow();
        int journalIndex = uow.appendJournal(new ActionRecord(machine.key(), entity, transition.from(),
                transition.transition(), transition.to(), action.name(), depth, ActionStatus.RUNNING));
        DefaultTransitionContext<E, S, T> ctx =
                new DefaultTransitionContext<>(tx, machine, entity, transition, depth, input);
        tx.track(ctx);
        ActionOutcome outcome;
        try {
            outcome = executeAction(tx, machine, ref, transition, action, ctx);
        } catch (ActionFailure failure) {
            uow.journalStatus(journalIndex, ActionStatus.THREW);
            tx.settle(ctx);
            return routeThrown(tx, machine, ref, entity, transition, action, depth, savepoint, input,
                    failure.getCause());
        } catch (EngineAbort aborted) {
            uow.journalStatus(journalIndex, ActionStatus.THREW);
            tx.settle(ctx);
            // A null stage or outcome, or a wait that timed out, was interrupted or was refused: poisoned now that the
            // action's end is logged, as the abort of an action that threw is.
            throw tx.poisonOr(tx.poisonIfNested(depth, aborted.abort()));
        } catch (CallFailedException e) {
            uow.journalStatus(journalIndex, ActionStatus.THREW);
            tx.settle(ctx);
            // Reaching here: a CallFailedException that a hand-written stage's handle throws.
            throw tx.poisonOr(e);
        }
        tx.settle(ctx);
        if (uow.isPoisoned()) {
            uow.journalStatus(journalIndex, ActionStatus.THREW);
            throw uow.poison().orElseThrow();
        }
        if (outcome instanceof ActionOutcome.Failed failed) {
            uow.journalStatus(journalIndex, ActionStatus.FAILED);
            return FailureRoutes.failureRoute(tx, machine, ref, entity, transition, depth, savepoint,
                    RecoverySelector.returned(transition, failed.failureKind()), failed.failureKind(), failed.reason(),
                    Optional.empty(), input);
        }
        uow.journalStatus(journalIndex, ActionStatus.OK);
        return new StepResult.Applied<>(transition.from(), transition.transition(), transition.to());
    }

    /**
     * What the step makes of {@code cause}, what its action threw or completed its stage with, once the action's end
     * is journaled: a failure of this call as {@link Transaction#poisonOr} says; the failure route that the most
     * specific declared type that accepts it selects; anything else an abort of the call with it as cause.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> routeThrown(
            Transaction tx, DefaultStateMachine<E, S, T> machine, EntityRef ref, E entity,
            TransitionSpec<E, S, T> transition, ActionRef<E, S, T> action, int depth, int savepoint,
            Optional<Object> input, Throwable cause) {
        if (cause instanceof CallFailedException own && tx.owns(own)) {
            throw tx.poisonOr(own);
        }
        if (RecoverySelector.admits(transition, cause)) {
            // A poisoned unit of work never commits, and every route commits: the poison is thrown before a route
            // is chosen and before the reason reads the throwable's message, so no recovery action can run, as
            // when the action returns.
            if (tx.uow().isPoisoned()) {
                throw tx.uow().poison().orElseThrow();
            }
            RecoverySelector.Route<T> selected = RecoverySelector.thrown(transition, cause);
            return FailureRoutes.failureRoute(tx, machine, ref, entity, transition, depth, savepoint,
                    selected.recovery(), selected.failureKind(), FailureRoutes.describe(cause), Optional.of(cause),
                    input);
        }
        throw tx.abort(machine, ref, transition.from(), Optional.of(transition.transition()),
                Optional.of(transition.to()), Optional.of(action.name()), depth,
                "the action " + action.name() + " threw " + Texts.shown(cause), Optional.of(cause));
    }

    /**
     * Runs the action as {@link #invokeAction} says. A call that logs at TRACE logs its start and how it ended, with
     * the time it took: the outcome it returned, or the class of what ended it, the throwable the action threw or
     * completed its stage with, or the engine's own abort. The line names that class alone and never reads the
     * throwable's message, so the engine reads it, if at all, when it would without the log, after the poison check
     * for a throwable a declared type accepts. An Error logs no end.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> ActionOutcome executeAction(
            Transaction tx, DefaultStateMachine<E, S, T> machine, EntityRef ref, TransitionSpec<E, S, T> transition,
            ActionRef<E, S, T> action, DefaultTransitionContext<E, S, T> ctx) {
        CallLog log = tx.log();
        if (!log.trace()) {
            return invokeAction(tx, machine, ref, transition, action, ctx);
        }
        String name = "action " + action.name();
        log.trace(ctx.depth(), name + " start mode=" + (action instanceof ActionRef.Async ? "async" : "sync"));
        long started = System.nanoTime();
        ActionOutcome outcome;
        try {
            outcome = invokeAction(tx, machine, ref, transition, action, ctx);
        } catch (RuntimeException thrown) {
            Throwable cause = thrown instanceof ActionFailure || thrown instanceof EngineAbort
                    ? thrown.getCause() : thrown;
            log.trace(ctx.depth(), name + " THREW error=" + cause.getClass().getName() + " elapsed="
                    + CallLog.micros(System.nanoTime() - started));
            throw thrown;
        }
        String end = outcome instanceof ActionOutcome.Failed failed
                ? " FAILED" + (failed.failureKind().isPresent() ? " kind=" + failed.failureKind().get().getSimpleName()
                        : "") + " reason=\"" + failed.reason() + "\""
                : " OK";
        log.trace(ctx.depth(), name + end + " elapsed=" + CallLog.micros(System.nanoTime() - started));
        return outcome;
    }

    /**
     * Sync actions run inline; async ones return a stage that is awaited under the edge's deadline, as
     * {@link #asyncOutcome} says. Whatever the action throws, an Error aside, or completes its stage with, checked
     * exceptions included, which Kotlin or {@code @SneakyThrows} code can throw without declaring them, and library
     * exceptions too, becomes an ActionFailure that its caller classifies. An InterruptedException that the action
     * throws on the calling thread, a sync one or an async one before it returns its stage, interrupts the thread
     * again, since the blocking call that threw it cleared the interrupt; one that completes the stage leaves the
     * thread as it is. An Error is never routed: thrown as itself, it propagates; wrapped in CompletionException or
     * ExecutionException, it is unwrapped and aborts. Exceptional stage completions hand the throwable they completed
     * with to ActionFailure; the engine reads no message before classification, though the JDK may already have read
     * it, as when {@code supplyAsync} wrapped the throwable in a CompletionException. The engine's own abort of the
     * step, for a null outcome or stage or a wait that timed out, was interrupted or was refused, leaves as an
     * EngineAbort, which a nested step poisons the call with once executeAction logged the action's end.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> ActionOutcome invokeAction(
            Transaction tx, DefaultStateMachine<E, S, T> machine, EntityRef ref, TransitionSpec<E, S, T> transition,
            ActionRef<E, S, T> action, DefaultTransitionContext<E, S, T> ctx) {
        ActionOutcome outcome = switch (action) {
            case ActionRef.Sync<E, S, T> sync -> {
                try {
                    yield sync.action().process(ctx);
                } catch (Error fatal) {
                    throw fatal;
                } catch (Throwable e) {
                    Interrupts.keep(e);
                    throw new ActionFailure(e);
                }
            }
            case ActionRef.Async<E, S, T> async -> asyncOutcome(tx, machine, ref, transition, async, ctx);
        };
        if (outcome == null) {
            throw new EngineAbort(tx.unpoisonedAbort(machine, ref, ctx.from(), Optional.of(ctx.transition()),
                    Optional.of(ctx.to()), Optional.of(action.name()), "the action " + action.name() + " returned null",
                    Optional.empty()));
        }
        return outcome;
    }

    /**
     * The outcome of the asynchronous {@code action}: the stage it returns, awaited under the edge's deadline. Before
     * it waits for the stage, a call that logs at TRACE logs the budget the wait has left: none when neither the edge
     * nor the call sets a limit. A null stage and a stage completed with null are the engine's aborts, as
     * {@link #invokeAction} says.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> ActionOutcome asyncOutcome(
            Transaction tx, DefaultStateMachine<E, S, T> machine, EntityRef ref, TransitionSpec<E, S, T> transition,
            ActionRef.Async<E, S, T> action, DefaultTransitionContext<E, S, T> ctx) {
        long edgeBudget = edgeBudgetNanos(transition);
        CompletionStage<ActionOutcome> stage;
        try {
            stage = action.action().process(ctx);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            Interrupts.keep(e);
            throw new ActionFailure(e);
        }
        if (stage == null) {
            throw new EngineAbort(tx.unpoisonedAbort(machine, ref, ctx.from(), Optional.of(ctx.transition()),
                    Optional.of(ctx.to()), Optional.of(action.name()),
                    "the action " + action.name() + " returned a null stage", Optional.empty()));
        }
        long deadline = edgeDeadline(tx, edgeBudget);
        if (tx.log().trace()) {
            tx.log().trace(ctx.depth(), "action " + action.name() + " awaiting budget=" + (deadline == Long.MAX_VALUE
                    ? "none" : CallLog.micros(Math.max(0L, deadline - System.nanoTime()))));
        }
        Optional<ActionOutcome> result = await(tx, machine, ref, ctx.from(), Optional.of(ctx.transition()),
                Optional.of(ctx.to()), Optional.of(action.name()), "the action " + action.name(), stage, deadline);
        if (result.isEmpty()) {
            throw new EngineAbort(tx.unpoisonedAbort(machine, ref, ctx.from(), Optional.of(ctx.transition()),
                    Optional.of(ctx.to()), Optional.of(action.name()),
                    "the action " + action.name() + " returned null", Optional.empty()));
        }
        return result.orElseThrow();
    }

    /**
     * Waits under the deadline. Timeout, interruption and the refusal of the thread's ForkJoinPool to let it block
     * are aborts, each caused by what ended the wait, which leave as an EngineAbort that has not poisoned the call: a
     * nested action's step poisons the call with it once it logged the action's end, a nested guard's evaluation as
     * soon as it catches it. The user's stage is left running.
     */
    static <R> Optional<R> await(Transaction tx, DefaultStateMachine<?, ?, ?> machine, EntityRef ref, Enum<?> from,
                                 Optional<Enum<?>> id, Optional<Enum<?>> to, Optional<String> action, String what,
                                 CompletionStage<R> stage, long deadlineNanos) {
        try {
            return Await.completedOrAwait(stage, deadlineNanos);
        } catch (Await.Timeout timeout) {
            throw new EngineAbort(tx.unpoisonedAbort(machine, ref, from, id, to, action,
                    "waiting for " + what + " timed out", Optional.of(timeout.getCause())));
        } catch (Await.Interrupted interrupted) {
            throw new EngineAbort(tx.unpoisonedAbort(machine, ref, from, id, to, action,
                    "interrupted while waiting for " + what, Optional.of(interrupted.getCause())));
        } catch (Await.Rejected rejected) {
            throw new EngineAbort(tx.unpoisonedAbort(machine, ref, from, id, to, action,
                    "waiting for " + what + " was refused by the thread's ForkJoinPool",
                    Optional.of(rejected.getCause())));
        }
    }

    /**
     * The edge's own budget in nanoseconds, Long.MAX_VALUE when it sets no limit. It is converted before the action
     * runs, although its clock only starts once the action returns its stage; the conversion saturates and never
     * throws.
     */
    private static long edgeBudgetNanos(TransitionSpec<?, ?, ?> transition) {
        return transition.timeout().isEmpty() ? Long.MAX_VALUE : Await.budgetNanos(transition.timeout().get());
    }

    /**
     * The edge's timeout can only shorten the transaction's deadline, never extend it: the wait ends at the earlier
     * of the two. The edge's clock starts now, after the action returned its stage. The budgets left are compared
     * rather than the deadlines, because a deadline may have wrapped past Long.MAX_VALUE, and Long.MAX_VALUE on
     * either side means that side sets no limit.
     */
    private static long edgeDeadline(Transaction tx, long edgeBudget) {
        long base = tx.uow().deadlineNanos();
        if (edgeBudget == Long.MAX_VALUE) {
            return base;
        }
        long now = System.nanoTime();
        if (base != Long.MAX_VALUE && base - now <= edgeBudget) {
            return base;
        }
        return now + edgeBudget;
    }
}
