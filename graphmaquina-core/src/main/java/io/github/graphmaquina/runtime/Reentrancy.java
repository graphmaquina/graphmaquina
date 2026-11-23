package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.error.ReentrantTransitionException;
import io.github.graphmaquina.error.ReentrantTransitionException.Refusal;
import java.util.Optional;

/**
 * One cell per thread, which tells what the thread is running.
 * <ul>
 *   <li>Its marker is set for the whole of a root transaction, guards and phase 2 included; for the whole of an
 *       eligibility check, as {@link Checks} runs it; while a watch is evaluated: the owner lookup of a dispatch, and a
 *       reevaluation's state read, peer lookup and conditions; while an advance reads its state and its candidates;
 *       and while an observer is notified. execute, reevaluate, advance, choose and a check called from a callback
 *       that runs there are refused, whatever the machine or entity: a guard, an action, a sub-machine's childOf or
 *       parentOf, a watch function, a state accessor, an identity function or an observer callback. After-commit
 *       hooks run once it is released, and a reaction's selected edge runs as a root of its own once the watch
 *       functions are done.</li>
 *   <li>Its scopes are the units of work of the root call and the checks running on the thread, innermost on top, each
 *       linked to the one below it. A unit of work counts the synchronous guards of its steps that run, as
 *       {@link Steps} brackets each one's test call. The one exception to the refusals is a check started while a
 *       synchronous guard of the innermost scope runs, whose entity no scope holds, as {@link #requireCheckMayStart}
 *       says: it runs as a scope of its own, from its claim of the cell to its release, which is its own step, from the
 *       read of its entity's identity to its last guard, with that step's TRACE lines and the texts they and its
 *       failures print; it starts with no guard running, so its identity function, its state getter and its binding
 *       lookups can start nothing, while its own guards follow the same rule. In that scope a read of an entity that a
 *       scope below holds, through a context's peer view or a binding lookup, is refused, as
 *       {@link #requireNotHeldOutside} says, whichever live context's view on this thread makes it, and so is a cascade
 *       through a retained context of an enclosing call, as {@link #requireInnermost} says. The application code the
 *       engine runs for a nested check outside that scope runs in the enclosing scope, under that scope's rules, with
 *       that scope's guard count held at 0, so it cannot start a check: the entity's identity function and, when an
 *       enclosing scope holds an entity of that machine, its identity's equals, before the check starts, as
 *       {@link #requireCheckMayStart} says, and the log's backend, for the check's levels and its start, verdict and
 *       failed lines, with the identity text and the failure reason that those lines and the check's failure print, as
 *       {@link Checks#check} says. A cascade holds its call's guard count at 0 too, as {@code DefaultTransitionContext}
 *       says, so nothing in a step that a guard cascades to through a kept context starts a check but that step's own
 *       guards.</li>
 * </ul>
 * Each holder prepares everything first and claims the cell last; it restores what it found with plain stores, the
 * first statements of a finally, so no failure leaves the cell claimed, not even a StackOverflowError that unwinds
 * through that finally with no stack left for a call. A root call and a check capture the marker and the innermost
 * scope in locals before they claim the cell and store them back; the other holders found the marker clear and clear
 * it. Holders nest like a stack, each restoring what it found, so the release of a nested check leaves the enclosing
 * call's scope and marker as they were. Every change of a guard count is undone the same way, and a count lives on
 * its scope's unit of work, so none outlives its call or check.
 */
final class Reentrancy {

    /**
     * The thread's cell. The thread keeps it, so that a release is a store into it, which needs no stack, where a
     * call, to ThreadLocal.set for one, could overflow again and leave the cell claimed.
     */
    private static final ThreadLocal<Cell> CELL = ThreadLocal.withInitial(Cell::new);

    private Reentrancy() { }

    /** What a thread runs. Holders read and write its fields directly, so that a release is plain stores. */
    static final class Cell {

        /** The marker: whether the thread runs a call or a check, evaluates a watch or notifies an observer. */
        boolean active;
        /**
         * The innermost scope: the unit of work of the innermost root call or check that runs on the thread, which
         * links the scopes below it; empty while none runs.
         */
        Optional<UnitOfWork> top = Optional.empty();
    }

    /** The thread's cell, whose values a holder captures before it claims it. */
    static Cell cell() {
        return CELL.get();
    }

    /**
     * Rejects a call made while this thread runs a root transaction or a check, evaluates a watch or notifies an
     * observer, without claiming the cell.
     */
    static void check(MachineKey<?, ?, ?> key, Object entity) {
        if (CELL.get().active) {
            throw new ReentrantTransitionException(key, entity, Refusal.CALL);
        }
    }

    /**
     * Rejects a call as {@link #check} does, then claims the marker, with no scope, and returns the thread's cell,
     * which the caller releases with {@code cell.active = false} as the first statement of the finally of the try
     * that follows: nothing between the claim and the try can throw. The cell is read before the marker is claimed,
     * so a read that overflows leaves the marker as it was. For the holders that read a state or run a watch function
     * or an observer, never inside a scope.
     */
    static Cell enter(MachineKey<?, ?, ?> key, Object entity) {
        Cell cell = CELL.get();
        if (cell.active) {
            throw new ReentrantTransitionException(key, entity, Refusal.CALL);
        }
        cell.active = true;
        return cell;
    }

    /**
     * Rejects a root call as {@link #check} does, then claims the marker and pushes {@code uow}, the call's unit of
     * work, as the innermost scope. The caller captured {@code cell}'s values before, and restores them as the first
     * statements of the finally of the try that follows this call.
     */
    static void enterCall(Cell cell, MachineKey<?, ?, ?> key, Object entity, UnitOfWork uow) {
        if (cell.active) {
            throw new ReentrantTransitionException(key, entity, Refusal.CALL);
        }
        push(cell, uow);
    }

    /**
     * Rejects a check of {@code entity} that may not start on this thread, without claiming the cell. A check may
     * start where nothing runs, and, while the cell is claimed, only while a synchronous guard of the innermost scope
     * runs and only when no scope holds {@code entity}, as {@link UnitOfWork#holds} says, by the reference that
     * {@code machine}'s identity function gives it. That function, and, when an enclosing scope holds an entity of
     * that machine, its identity's equals, run here with the innermost scope's guard count held at 0, restored by a
     * store, so neither can start a check: what either throws propagates as it is. Anything else is refused with
     * ReentrantTransitionException.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> void requireCheckMayStart(
            Cell cell, DefaultStateMachine<E, S, T> machine, E entity) {
        if (!cell.active) {
            return;
        }
        if (cell.top.isEmpty() || cell.top.get().guards == 0) {
            throw new ReentrantTransitionException(machine.key(), entity, Refusal.CALL);
        }
        UnitOfWork innermost = cell.top.get();
        int guards = innermost.guards;
        boolean held;
        innermost.guards = 0;
        try {
            held = heldBy(cell.top, machine.refOf(entity));
        } finally {
            innermost.guards = guards;
        }
        if (held) {
            throw new ReentrantTransitionException(machine.key(), entity, Refusal.HELD_CHECK);
        }
    }

    /**
     * Claims the marker and pushes {@code uow}, the unit of work of a check that {@link #requireCheckMayStart} let
     * start, as the innermost scope, where no guard runs yet. The caller captured {@code cell}'s values before, and
     * restores them as the first statements of the finally of the try that follows this call.
     */
    static void enterCheck(Cell cell, UnitOfWork uow) {
        push(cell, uow);
    }

    /**
     * Links {@code uow} to the scope below it and makes it the innermost one. The only allocation comes first, so that
     * once a store reaches the cell, the rest are stores too.
     */
    private static void push(Cell cell, UnitOfWork uow) {
        Optional<UnitOfWork> scope = Optional.of(uow);
        uow.below = cell.top;
        cell.active = true;
        cell.top = scope;
    }

    /**
     * Rejects a read of {@code ref} made while {@code current} is the thread's innermost scope when a scope below it
     * holds {@code ref}, as {@link UnitOfWork#holds} says, with ReentrantTransitionException: such a read could see a
     * state that the enclosing call is about to replace. Each caller passes the innermost scope: a peer view the
     * thread's, whatever unit of work it reads through, so a view of an enclosing context handed to a nested check's
     * scope is refused too; a binding lookup its step's, which is the innermost one: in a nested check's scope no step
     * of an enclosing call can run, since a write is refused and so is a cascade through an enclosing action's
     * context, as {@link #requireInnermost} says, and outside it the enclosing scope is the innermost again. A scope
     * with none below it, as a root call or a check that no guard started, rejects nothing. Only the equals of
     * {@code ref}'s identity runs.
     */
    static void requireNotHeldOutside(UnitOfWork current, EntityRef ref) {
        if (heldBy(current.below, ref)) {
            throw new ReentrantTransitionException(ref.key(), ref.instance(), Refusal.HELD_READ);
        }
    }

    /**
     * Rejects a cascade through a context of the call or check whose unit of work is {@code uow} while that unit of
     * work is not the thread's innermost scope, which happens only in the scope of a check that a guard started, its
     * own step: a retained action context of the enclosing call, used by code in that scope, would stage hops in the
     * call the check is nested in. The ReentrantTransitionException names {@code key} and {@code entity}, the
     * context's own. Outside such a scope, the context's own unit of work is the innermost scope, and nothing
     * changes.
     */
    static void requireInnermost(UnitOfWork uow, MachineKey<?, ?, ?> key, Object entity) {
        if (CELL.get().top.orElseThrow() != uow) {
            throw new ReentrantTransitionException(key, entity, Refusal.KEPT_CASCADE);
        }
    }

    /** Whether {@code scope}, or a scope below it, holds {@code ref}. */
    private static boolean heldBy(Optional<UnitOfWork> scope, EntityRef ref) {
        Optional<UnitOfWork> each = scope;
        while (each.isPresent()) {
            if (each.get().holds(ref)) {
                return true;
            }
            each = each.get().below;
        }
        return false;
    }

    /**
     * The unit of work of the innermost scope on this thread, whose guard count a peer read holds at 0 while it runs
     * the peer's identity function and state getter, and below which it refuses a held peer; only for code that runs
     * inside a scope, as a context does.
     */
    static UnitOfWork innermost() {
        return CELL.get().top.orElseThrow();
    }
}
