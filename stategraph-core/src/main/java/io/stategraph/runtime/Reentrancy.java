package io.stategraph.runtime;

import io.stategraph.api.MachineKey;
import io.stategraph.error.ReentrantTransitionException;

/** One boolean per thread: detects transitionTo/reevaluate called from within an action. Carries nothing else. */
final class Reentrancy {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private Reentrancy() { }

    static void enter(MachineKey<?, ?> key, Object entity) {
        if (ACTIVE.get()) {
            throw new ReentrantTransitionException(key, entity);
        }
        ACTIVE.set(Boolean.TRUE);
    }

    static void exit() {
        ACTIVE.remove();
    }
}
