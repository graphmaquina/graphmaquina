package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.error.ReentrantTransitionException;

/** One boolean per thread: detects transitionTo/reevaluate called from within an action. Carries nothing else. */
final class Reentrancy {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private Reentrancy() { }

    /** Rejects a call made from within an action without claiming the marker. */
    static void check(MachineKey<?, ?> key, Object entity) {
        if (ACTIVE.get()) {
            throw new ReentrantTransitionException(key, entity);
        }
    }

    static void enter(MachineKey<?, ?> key, Object entity) {
        check(key, entity);
        ACTIVE.set(Boolean.TRUE);
    }

    static void exit() {
        ACTIVE.remove();
    }
}
