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
 * is no call: it records no trail, logs no line and tells no observer, and, whatever it answers or throws, it writes
 * no state, logs no hop, runs no action, hook or watch reaction, starts no sub-machine and runs no exit.
 */
final class Checks {

    private Checks() { }

    /**
     * Whether {@code machine} admits {@code transition} for {@code entity} in the state it is in now, with
     * {@code input} as the operation input its guards read, empty when the check carries none. It is refused before
     * it starts as execute is: an identifier of another catalogue with IllegalArgumentException, and a check where no
     * call may start with ReentrantTransitionException. Then, in a unit of work of its own, whose deadline is the
     * machine's counted from now, the root step runs as {@link Steps#admit} says, under the reentrancy marker, which
     * the first statement of the finally releases with a store before the contexts the step handed out are
     * invalidated, whatever the step throws. A step refused on the way is {@link Eligibility.Ineligible}, with the
     * state it read and the reason, the kind and the guard of the refusal; a step admitted is
     * {@link Eligibility.Eligible}, with the state it read. An Error propagates as itself; anything else the step
     * throws is the {@link EligibilityCheckException} that {@link #failed} builds once the marker is released.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> Eligibility<E, S, T> check(
            DefaultStateMachine<E, S, T> machine, E entity, T transition, Optional<Object> input) {
        Transaction.requireTransition(machine.key(), transition);
        Reentrancy.check(machine.key(), entity);
        MachineRegistry registry = machine.registry();
        UnitOfWork uow = new UnitOfWork(registry.maxCascadeDepth(), machine.deadlineNanos());
        Transaction.Admission tx = new Transaction.Admission(registry, uow, machine.key(), entity,
                Optional.of(transition), false, Optional.empty(), CallLog.OFF);
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
            throw failed(tx, machine, entity, transition, failure);
        }
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
     * when
     * {@code failure} is an InterruptedException, as {@link Transaction#classify} does. Each names the guard in flight
     * as {@link Transaction.Admission#guardInFlight} keeps it, and has the message
     * {@code eligibility of <ID> in <key> <FROM> could not be decided: <reason>}, where {@code ?} stands for a state
     * the check did not read.
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
        String message = "eligibility of " + transition.name() + " in " + machine.key() + " "
                + from.map((Enum<?> state) -> state.name()).orElse("?") + " could not be decided: " + reason;
        return new EligibilityCheckException(machine.key(), entity, transition, from, tx.guardInFlight(), message,
                cause);
    }

    /** The reason of a failure that is no abort of the check: {@code the engine could not go on: <error>}. */
    private static String stopped(Throwable error) {
        return "the engine could not go on: " + Texts.shown(error);
    }
}
