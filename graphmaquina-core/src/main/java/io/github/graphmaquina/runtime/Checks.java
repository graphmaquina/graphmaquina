package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.error.EligibilityCheckException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.internal.Interrupts;
import io.github.graphmaquina.internal.Texts;
import java.util.Optional;

/**
 * The eligibility check of a machine, which {@link DefaultStateMachine#eligibility} runs: the admission of a root step
 * as {@link Steps#admit} runs it, in a transaction of its own that never commits, without the call around it. A check
 * is no call: it records no trail, logs no line of a call and tells no observer, and, whatever it answers or throws, it
 * writes no state, logs no hop, runs no action, hook or watch reaction, starts no sub-machine and runs no exit. It
 * logs lines of its own instead, under {@code check=<id>} in place of {@code call=<id>}, as {@link CallLog} says: at
 * DEBUG a start line and a verdict line, or a failed one, and at TRACE the lines of the step it admits.
 */
final class Checks {

    private Checks() { }

    /**
     * Whether {@code machine} admits {@code transition} for {@code entity} in the state it is in now, with
     * {@code input} as the operation input its guards read, empty when the check carries none. It is refused before
     * it starts as execute is: an identifier of another catalogue with IllegalArgumentException, and a check where no
     * call may start with ReentrantTransitionException, and a check so refused logs nothing. Then it opens its log,
     * which logs its start line at DEBUG; in a unit of work of its own, whose deadline is the machine's counted from
     * now, the root step runs as {@link Steps#admit} says, with that log, under the reentrancy marker, which the first
     * statement of the finally releases with a store before the contexts the step handed out are invalidated, whatever
     * the step throws. A step refused on the way is {@link Eligibility.Ineligible}, with the state it read and the
     * reason, the kind and the guard of the refusal; a step admitted is {@link Eligibility.Eligible}, with the state it
     * read. The verdict line is logged once the marker is released, as an end line is; so is the failed line of the
     * {@link EligibilityCheckException} that {@link #failed} builds when the step throws anything but an Error. An
     * Error propagates as itself, with no failed line, whichever line of the log throws it. Anything else that the
     * backend throws is contained by the log, as {@link CallLog} says, so a log never changes the verdict or turns
     * into a failure of the check.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> Eligibility<E, S, T> check(
            DefaultStateMachine<E, S, T> machine, E entity, T transition, Optional<Object> input) {
        Transaction.requireTransition(machine.key(), transition);
        Reentrancy.check(machine.key(), entity);
        MachineRegistry registry = machine.registry();
        UnitOfWork uow = new UnitOfWork(registry.maxCascadeDepth(), machine.deadlineNanos());
        CallLog log = CallLog.openCheck();
        Transaction.Admission tx = new Transaction.Admission(registry, uow, machine.key(), entity,
                Optional.of(transition), false, Optional.empty(), log);
        if (log.debug()) {
            log.start(machine.key(), transition.name());
        }
        StepResult<S, T> step;
        boolean[] marker = Reentrancy.enter(machine.key(), entity);
        try {
            try {
                step = Steps.admit(tx, machine, entity, transition, input);
            } finally {
                marker[0] = false;
                tx.invalidate();
            }
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable failure) {
            EligibilityCheckException unanswered = failed(tx, machine, entity, transition, failure);
            if (log.debug()) {
                log.checkFailed(unanswered);
            }
            throw unanswered;
        }
        Eligibility<E, S, T> verdict = verdict(entity, transition, step);
        if (log.debug()) {
            log.checked(verdict);
        }
        return verdict;
    }

    /** The answer for a step that was refused on the way, or admitted. */
    private static <E, S extends Enum<S>, T extends Enum<T>> Eligibility<E, S, T> verdict(
            E entity, T transition, StepResult<S, T> step) {
        if (step instanceof StepResult.Rejected<S, T> rejected) {
            return new Eligibility.Ineligible<>(entity, rejected.from(), transition, rejected.reason(),
                    rejected.kind(), rejected.guard());
        }
        return new Eligibility.Eligible<>(entity, step.from(), transition);
    }

    /**
     * What a check of {@code transition} for {@code entity} throws when its step threw {@code failure}, which is no
     * Error. For an abort of the check's own transaction: the state the abort names, or else the state the step read,
     * the abort's cause, if any, and as the reason the message the abort was built with, as
     * {@link Transaction.Admission#abortReason} keeps it, or the reason below when it kept none. For anything else,
     * which only engine code that calls into the application lets through: the state the step read, {@code failure}
     * itself as the cause, and the reason {@code the engine could not go on: <failure>}, the thread interrupted again
     * when {@code failure} is an InterruptedException, as {@link Transaction#classify} does. Each names the guard in
     * flight as {@link Transaction.Admission#guardInFlight} keeps it, and has the message
     * {@code eligibility of <ID> for <entity> <FROM> could not be decided: <reason>}. The entity is the reference
     * the step registered, {@code key#identity}, as the messages of a call's failures name it, or the key alone when
     * the step could not read the identity; the identity function does not run again here, where the marker is
     * released. {@code ?} stands for a state the check did not read.
     */
    private static EligibilityCheckException failed(Transaction.Admission tx, DefaultStateMachine<?, ?, ?> machine,
                                                    Object entity, Enum<?> transition, Throwable failure) {
        Optional<Enum<?>> from;
        Optional<Throwable> cause;
        String reason;
        if (failure instanceof TransitionAbortedException abort && tx.owns(abort)) {
            from = abort.from().or(tx::readState);
            cause = Optional.ofNullable(abort.getCause());
            reason = tx.abortReason().orElseGet(() -> stopped(abort));
        } else {
            Interrupts.keep(failure);
            from = tx.readState();
            cause = Optional.of(failure);
            reason = stopped(failure);
        }
        String named = tx.ref().map((EntityRef ref) -> ref.toString()).orElse(machine.key().toString());
        String message = "eligibility of " + transition.name() + " for " + named + " "
                + from.map((Enum<?> state) -> state.name()).orElse("?") + " could not be decided: " + reason;
        return new EligibilityCheckException(machine.key(), entity, transition, from, tx.guardInFlight(), message,
                cause);
    }

    /** The reason of a failure that is no abort of the check: {@code the engine could not go on: <error>}. */
    private static String stopped(Throwable error) {
        return "the engine could not go on: " + Texts.shown(error);
    }
}
