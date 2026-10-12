package io.github.graphmaquina.runtime;

import io.github.graphmaquina.error.TransitionAbortedException;

/**
 * The engine's own abort of a step whose action or guard it gave up on, for a null result or a wait that timed out,
 * was interrupted or was refused, on its way out of the action or the wait before it poisons the call: the step
 * poisons the call with it once it has logged how the action ended, as it does the abort of an action that threw.
 * Never leaves the engine, and records no stack trace of its own.
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
