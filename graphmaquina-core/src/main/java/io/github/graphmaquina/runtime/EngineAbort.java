package io.github.graphmaquina.runtime;

import io.github.graphmaquina.error.TransitionAbortedException;

/**
 * An abort the engine made of a step, not yet the poison of the call: where it is caught, the engine poisons the call
 * with it when the step is nested, and throws it. For an action, it is the engine's own abort of the action, for a null
 * outcome or a null stage or for a wait that timed out, was interrupted or was refused, which the action's step takes
 * once it has logged how the action ended, as it does the abort of an action that threw. For an asynchronous guard, it
 * is only the abort of a wait that timed out, was interrupted or was refused, which the guard's evaluation takes as
 * soon as it catches it, since a failed guard logs no end; a guard's null stage, null result or null verdict is aborted
 * at once, without this wrapper. Never leaves the engine, and records no stack trace of its own.
 */
final class EngineAbort extends RuntimeException {

    EngineAbort(TransitionAbortedException abort) {
        super(null, abort, false, false);
    }

    /** The abort, not yet the poison of the call. */
    TransitionAbortedException abort() {
        return (TransitionAbortedException) getCause();
    }
}
