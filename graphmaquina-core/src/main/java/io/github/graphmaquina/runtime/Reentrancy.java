package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.error.ReentrantTransitionException;

/**
 * One boolean per thread, set for the whole of a root transaction, guards and phase 2 included; while a watch is
 * evaluated: the owner lookup of a dispatch, and a reevaluation's state read, peer lookup and conditions; and while an
 * observer is notified. It detects execute/reevaluate called from a callback that runs there, a guard, an action, a
 * sub-machine's childOf, a watch function, a state accessor, an identity function or an observer callback, whatever the
 * machine or entity. After-commit hooks run once it is released, and a reaction's selected edge runs as a root of its
 * own once the watch functions are done. Carries nothing else. Each holder sets it once everything else is prepared and
 * releases it in a finally, so no failure leaves it set.
 */
final class Reentrancy {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private Reentrancy() { }

    /**
     * Rejects a call made while this thread runs a root transaction, evaluates a watch or notifies an observer,
     * without claiming the marker.
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

    /**
     * Releases the marker. The thread keeps its entry, set to false: a removed entry is created again by the next
     * read, which every call makes.
     */
    static void exit() {
        ACTIVE.set(Boolean.FALSE);
    }
}
