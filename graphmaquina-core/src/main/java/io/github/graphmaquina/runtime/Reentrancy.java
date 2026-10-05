package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.error.ReentrantTransitionException;

/**
 * One boolean per thread, set for the whole of a root transaction, guards and phase 2 included: detects
 * execute/reevaluate called from a callback that runs inside it, a guard, an action, a state accessor or an identity
 * function, whatever the machine or entity. After-commit hooks, watch reactions and the listener run once it is
 * released. Carries nothing else. The root sets it once everything else is prepared and releases it in a finally, so
 * no failure leaves it set.
 */
final class Reentrancy {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private Reentrancy() { }

    /** Rejects a call made while this thread runs a root transaction, without claiming the marker. */
    static void check(MachineKey<?, ?, ?> key, Object entity) {
        if (ACTIVE.get()) {
            throw new ReentrantTransitionException(key, entity);
        }
    }

    static void enter(MachineKey<?, ?, ?> key, Object entity) {
        check(key, entity);
        ACTIVE.set(Boolean.TRUE);
    }

    static void exit() {
        ACTIVE.remove();
    }
}
