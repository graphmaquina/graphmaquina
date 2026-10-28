package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionRef;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.FlowEntry;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.error.AbortDetails;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.StateMachineException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UnhandledFailureException;
import io.github.graphmaquina.internal.Interrupts;
import java.util.Optional;

/**
 * The failure route of a step whose action failed: the rollback to the step's savepoint, then the recovery edge that
 * the failure selects, run without guards, with its own action, or the unhandled failure that ends the call.
 */
final class FailureRoutes {

    private FailureRoutes() { }

    /**
     * Roll back to the savepoint, then resolve {@code recovery}, the identifier the failure selected, from the original
     * origin and run that edge without guards and without a failure route of its own. Its hop is logged as one that
     * stops the automatic chain unless {@code continueChain}, the flag of the route that selected it, is set. An empty
     * {@code recovery} leaves the failure unhandled. The result carries {@code failureKind} as it is. A recovery action that throws, or
     * completes its stage with, a throwable that is neither an Error thrown as itself nor a failure of the call aborts
     * the call with it as cause, and the abort holds {@code cause}, the original throwable, as suppressed when it is
     * present and another one: adding it reads no message. These aborts that the engine builds for the recovery step
     * hold it as suppressed too, unless one is caused by that very throwable, as {@link Transaction#keepingOriginal}
     * says: its own abort of the recovery action, for a null outcome or stage or a wait that timed out, was interrupted
     * or was refused, before it poisons the call, where a poison stored earlier still ends the call as it is; the abort
     * of a sub-machine that the recovery hop enters and that cannot start, once it poisoned the call, since no poison
     * can precede it; and, once this method returned, the abort built for this step when the exit that a terminal state
     * selects cannot run, as {@link Submachines#exits} says. A call that logs at TRACE logs the route, or the
     * unhandled failure, once the rollback is done. A call that records its trail leaves what failed on the mark of the
     * recovery hop, which sits at the savepoint since the rollback truncated the log there, as soon as the hop is
     * marked: before a sub-machine its target binds starts and before the recovery action runs.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> failureRoute(
            Transaction tx, DefaultStateMachine<E, S, T> machine, EntityRef ref, E entity,
            TransitionSpec<E, S, T> failing, int depth, int savepoint, Optional<T> recovery,
            Optional<Class<? extends FailureKind>> failureKind, boolean continueChain, String reason,
            Optional<Throwable> cause, Optional<Object> input) {
        UnitOfWork uow = tx.uow();
        uow.rollbackTo(savepoint);
        S from = failing.from();
        Optional<String> actionName = failing.action().map(ActionRef::name);
        if (recovery.isEmpty()) {
            throw unhandled(tx, machine, ref, failing, depth, reason, cause, actionName);
        }
        T recoveryId = recovery.orElseThrow();
        if (tx.log().trace()) {
            tx.log().trace(depth, "route " + failing.transition().name() + " -> " + recoveryId.name() + " rollback-to="
                    + savepoint + (failureKind.isPresent() ? " kind=" + failureKind.get().getSimpleName() : "")
                    + " reason=\"" + reason + "\"");
        }
        TransitionSpec<E, S, T> route = machine.definition().findTransition(from, recoveryId).orElseThrow();
        // The failed action has ended. Keep one frame for the entity, now identifying the recovery edge.
        uow.pop();
        uow.push(ref, route);
        Optional<FlowEntry.Recovery> recovered = uow.recordsTrail()
                ? Optional.of(new FlowEntry.Recovery(failing.transition(), reason, cause, failureKind))
                : Optional.empty();
        try {
            Steps.appendHop(tx, machine, ref, entity, route, depth, !continueChain, recovered);
        } catch (TransitionAbortedException entering) {
            // Only the abort a start built for this hop gets here, on a unit of work that no poison preceded.
            throw Transaction.keepingOriginal(entering, cause);
        }
        if (route.action().isPresent()) {
            runRecoveryAction(tx, machine, ref, entity, route, from, recoveryId, depth, reason, cause, input);
        }
        return new StepResult.Failed<>(from, failing.transition(), failing.to(), recoveryId, route.to(), reason, cause,
                failureKind);
    }

    /**
     * The failure of {@code failing} that no recovery edge takes, once the step is rolled back: an
     * UnhandledFailureException of the call, which carries {@code cause} when a throwable selected the route and
     * poisons when nested, logged first at TRACE when the call logs there.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> UnhandledFailureException unhandled(
            Transaction tx, DefaultStateMachine<E, S, T> machine, EntityRef ref, TransitionSpec<E, S, T> failing,
            int depth, String reason, Optional<Throwable> cause, Optional<String> actionName) {
        if (tx.log().trace()) {
            tx.log().trace(depth, "unhandled " + failing.transition().name() + " reason=\"" + reason + "\"");
        }
        AbortDetails details = tx.details(machine, ref, failing.from(), Optional.of(failing.transition()),
                Optional.of(failing.to()), actionName);
        UnhandledFailureException unhandled = cause.isPresent()
                ? new UnhandledFailureException(tx.call(), details, reason, cause.get())
                : new UnhandledFailureException(tx.call(), details, reason);
        return tx.poisonIfNested(depth, unhandled);
    }

    /**
     * Runs the action of {@code route}, the recovery edge that {@code recoveryId} selected from {@code from}, once its
     * hop is logged, journaled as any action is; how it ends is what {@link #failureRoute} says of a recovery action.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> void runRecoveryAction(
            Transaction tx, DefaultStateMachine<E, S, T> machine, EntityRef ref, E entity,
            TransitionSpec<E, S, T> route, S from, T recoveryId, int depth, String reason, Optional<Throwable> cause,
            Optional<Object> input) {
        UnitOfWork uow = tx.uow();
        ActionRef<E, S, T> action = route.action().orElseThrow();
        int journalIndex = uow.appendJournal(new ActionRecord(machine.key(), entity, from, recoveryId, route.to(),
                action.name(), depth, ActionStatus.RUNNING));
        DefaultTransitionContext<E, S, T> ctx =
                new DefaultTransitionContext<>(tx, machine, entity, route, depth, input);
        tx.track(ctx);
        ActionOutcome outcome;
        try {
            outcome = Actions.executeAction(tx, machine, ref, route, action, ctx);
        } catch (ActionFailure failure) {
            uow.journalStatus(journalIndex, ActionStatus.THREW);
            tx.settle(ctx);
            throw recoveryThrew(tx, machine, ref, from, recoveryId, route, action, depth, reason, cause,
                    failure.getCause());
        } catch (EngineAbort aborted) {
            uow.journalStatus(journalIndex, ActionStatus.THREW);
            tx.settle(ctx);
            // As in Actions.runAction: a poison stored earlier still wins.
            throw tx.poisonOr(tx.poisonIfNested(depth, Transaction.keepingOriginal(aborted.abort(), cause)));
        } catch (CallFailedException e) {
            uow.journalStatus(journalIndex, ActionStatus.THREW);
            tx.settle(ctx);
            // Reaching here: what reaches the same catch in Actions.runAction.
            throw tx.poisonOr(e);
        }
        tx.settle(ctx);
        if (uow.isPoisoned()) {
            uow.journalStatus(journalIndex, ActionStatus.THREW);
            throw uow.poison().orElseThrow();
        }
        if (outcome instanceof ActionOutcome.Failed nested) {
            uow.journalStatus(journalIndex, ActionStatus.FAILED);
            throw tx.abort(machine, ref, from, Optional.of(recoveryId), Optional.of(route.to()),
                    Optional.of(action.name()), depth,
                    "the failure route action " + action.name() + " returned failed (" + nested.reason()
                            + ") after the original failure (" + reason + ")", cause);
        }
        uow.journalStatus(journalIndex, ActionStatus.OK);
    }

    /**
     * What the engine throws for {@code recoveryCause}, what the recovery action threw or completed its stage with: a
     * failure of this call as {@link Transaction#poisonOr} says; anything else an abort of the call with it as cause,
     * which holds {@code cause}, the original throwable, as {@link Transaction#keepingOriginal} says, and poisons when
     * nested.
     */
    private static StateMachineException recoveryThrew(Transaction tx, DefaultStateMachine<?, ?, ?> machine,
                                                       EntityRef ref, Enum<?> from, Enum<?> recoveryId,
                                                       TransitionSpec<?, ?, ?> route, ActionRef<?, ?, ?> action,
                                                       int depth, String reason, Optional<Throwable> cause,
                                                       Throwable recoveryCause) {
        if (recoveryCause instanceof CallFailedException own && tx.owns(own)) {
            return tx.poisonOr(own);
        }
        return tx.poisonIfNested(depth, Transaction.keepingOriginal(tx.unpoisonedAbort(machine, ref, from,
                Optional.of(recoveryId), Optional.of(route.to()), Optional.of(action.name()),
                "the failure route action " + action.name() + " threw after the original failure (" + reason + ")",
                Optional.of(recoveryCause)), cause));
    }

    /**
     * The reason a routed throwable reports, whichever declared type admitted it: the throwable's simple name and its
     * message. An anonymous class has an empty simple name, so its binary name stands in: a reason is never blank. A
     * message whose read throws anything but an Error reads {@code <getMessage threw <class name>>}, so the route is
     * taken all the same, and an InterruptedException interrupts the thread again; an Error propagates.
     */
    static String describe(Throwable cause) {
        Optional<String> message;
        try {
            message = Optional.ofNullable(cause.getMessage());
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable unreadable) {
            Interrupts.keep(unreadable);
            message = Optional.of("<getMessage threw " + unreadable.getClass().getName() + ">");
        }
        String type = cause.getClass().getSimpleName();
        return (type.isEmpty() ? cause.getClass().getName() : type) + message.map((String text) -> ": " + text)
                .orElse("");
    }
}
