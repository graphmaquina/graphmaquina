package io.github.graphmaquina.runtime;

/**
 * The user's action threw an Exception, or the stage of an action or a guard completed exceptionally; a
 * StateMachineException that the action throws or that completes the stage, bare or wrapped in CompletionException
 * or ExecutionException, is rethrown as is instead, whichever root it comes from, and a TransitionAbortedException
 * from another root keeps details that describe that root. Never leaves the engine. Unwraps the cause on the way in,
 * so every path compares the same throwable against {@code failsOn}.
 */
final class ActionFailure extends RuntimeException {

    ActionFailure(Throwable cause) {
        super(Unwrap.unwrap(cause));
    }
}
