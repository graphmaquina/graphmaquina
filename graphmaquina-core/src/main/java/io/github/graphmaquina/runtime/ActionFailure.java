package io.github.graphmaquina.runtime;

/**
 * The user's action threw an Exception, or the stage of an action or a guard completed exceptionally, whatever it
 * carried, a library exception included. Never leaves the engine. Unwraps the cause on the way in, so every path
 * classifies the same throwable.
 */
final class ActionFailure extends RuntimeException {

    ActionFailure(Throwable cause) {
        super(Unwrap.unwrap(cause));
    }
}
