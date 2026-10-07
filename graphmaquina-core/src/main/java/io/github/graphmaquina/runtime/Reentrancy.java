package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.error.ReentrantTransitionException;

/**
 * One boolean per thread, set for the whole of a root transaction, guards and phase 2 included, while a watch is
 * evaluated: the owner lookup of a dispatch, and a reevaluation's state read, peer lookup and conditions, and while
 * an observer is notified. It detects execute/reevaluate called from a callback that runs there, a guard, an action,
 * a watch function, a state accessor or an identity function, whatever the machine or entity. After-commit hooks and
 * the listener run once it is released, and a reaction's selected edge runs as a root of its own once the watch
 * functions are done. Carries nothing else. Each holder sets it once everything else is prepared and releases it in a
 * finally, so no failure leaves it set.
 */
final class Reentrancy {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private Reentrancy() { }

    /**
     * Rejects a call made while this thread runs a root transaction or evaluates a watch, without claiming the
     * marker.
     */
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
