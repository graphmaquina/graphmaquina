package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.GuardRef;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.error.EligibilityCheckException;
import io.github.graphmaquina.error.StateMachineException;
import io.github.graphmaquina.error.TransitionAbortedException;
import java.util.Optional;

/**
 * The guard's own throwable that a failed call or check of a machine hands to its caller in place of the library's
 * exception, as {@link GuardRef#propagates()} says: one the guard of the root step threw, or completed its stage with,
 * and whose type that guard declares. The guard of a cascaded step or of a sub-machine's exit raises its abort in
 * ACTION, not in GUARD, so its throwable never qualifies here.
 */
final class Propagation {

    private Propagation() { }

    /**
     * The throwable {@code failure}, a failure of a call that the application made on {@code machine}, hands to the
     * caller: the guard's own one when the abort happened in GUARD, on the root step of {@code machine}, and that
     * step's guard declares its type; empty otherwise.
     */
    static Optional<Throwable> ofCall(DefaultStateMachine<?, ?, ?> machine, StateMachineException failure) {
        if (!(failure instanceof TransitionAbortedException abort) || abort.phase() != CallPhase.GUARD
                || !abort.key().equals(machine.key())) {
            return Optional.empty();
        }
        return declared(machine, abort.from(), abort.transition(), abort.guard(), abort.guardFailure());
    }

    /** The throwable {@code failure}, the failure of a check of {@code machine}, hands to the caller, as above. */
    static Optional<Throwable> ofCheck(DefaultStateMachine<?, ?, ?> machine, EligibilityCheckException failure) {
        return declared(machine, failure.from(), Optional.of(failure.transition()), failure.guard(),
                failure.guardFailure());
    }

    /**
     * Throws {@code thrown} as it is, a checked exception included, which a caller of the facade receives although no
     * method declares it, as a guard that declares it in {@code propagates} asked.
     */
    @SuppressWarnings("unchecked")
    static <X extends Throwable> RuntimeException rethrow(Throwable thrown) throws X {
        throw (X) thrown;
    }

    private static <E, S extends Enum<S>, T extends Enum<T>> Optional<Throwable> declared(
            DefaultStateMachine<E, S, T> machine, Optional<Enum<?>> from, Optional<Enum<?>> transition,
            Optional<String> guard, Optional<Throwable> thrown) {
        if (from.isEmpty() || transition.isEmpty() || guard.isEmpty() || thrown.isEmpty()
                || !machine.key().states().isInstance(from.get())
                || !machine.key().transitions().isInstance(transition.get())) {
            return Optional.empty();
        }
        Optional<TransitionSpec<E, S, T>> edge = machine.definition().findTransition(
                machine.key().states().cast(from.get()), machine.key().transitions().cast(transition.get()));
        if (edge.isEmpty()) {
            return Optional.empty();
        }
        for (GuardRef<E, S, T> declaring : edge.get().guards()) {
            if (declaring.name().equals(guard.get())) {
                for (Class<? extends Throwable> type : declaring.propagates()) {
                    if (type.isInstance(thrown.get())) {
                        return thrown;
                    }
                }
            }
        }
        return Optional.empty();
    }
}
