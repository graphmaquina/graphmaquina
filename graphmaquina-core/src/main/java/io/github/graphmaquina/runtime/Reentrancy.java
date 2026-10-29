package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.error.ReentrantTransitionException;

/**
 * One boolean per thread, set for the whole of a root transaction, guards and phase 2 included; for the whole of an
 * eligibility check, as {@link Checks} runs it; while a watch is evaluated: the owner lookup of a dispatch, and a
 * reevaluation's state read, peer lookup and conditions; while an advance reads its state and its candidates; and while
 * an observer is notified. It detects execute/reevaluate/advance/eligibility called from a callback that runs there, a
 * guard, an action, a sub-machine's childOf or parentOf, a watch function, a state accessor, an identity function or an
 * observer callback, whatever the machine or entity. After-commit hooks run once it is released, and a reaction's
 * selected edge runs as a root of its own once the watch functions are done. Carries nothing else. Each holder sets it
 * once everything else is prepared and releases it with a store as the first statement of a finally, so no failure
 * leaves it set, not even a StackOverflowError that unwinds through that finally with no stack left for a call.
 */
final class Reentrancy {

    /**
     * The thread's cell: its only element is the marker. The thread keeps it, so that a release is a store into it,
     * which needs no stack, where a call, to ThreadLocal.set for one, could overflow again and leave the marker set.
     */
    private static final ThreadLocal<boolean[]> ACTIVE = ThreadLocal.withInitial(() -> new boolean[1]);

    private Reentrancy() { }

    /**
     * Rejects a call made while this thread runs a root transaction, evaluates a watch or notifies an observer,
     * without claiming the marker.
     */
    static void check(MachineKey<?, ?, ?> key, Object entity) {
        if (ACTIVE.get()[0]) {
            throw new ReentrantTransitionException(key, entity);
        }
    }

    /**
     * Rejects a call as {@link #check} does, then claims the marker and returns the thread's cell, which the caller
     * releases with {@code cell[0] = false} as the first statement of the finally of the try that follows: nothing
     * between the claim and the try can throw. The cell is read before the marker is claimed, so a read that
     * overflows leaves the marker as it was.
     */
    static boolean[] enter(MachineKey<?, ?, ?> key, Object entity) {
        boolean[] cell = ACTIVE.get();
        if (cell[0]) {
            throw new ReentrantTransitionException(key, entity);
        }
        cell[0] = true;
        return cell;
    }
}
