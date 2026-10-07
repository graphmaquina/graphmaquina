package io.github.graphmaquina.runtime;

/**
 * The user's action threw a throwable that is not an Error, or the stage of an action or a guard completed
 * exceptionally, whatever it carried, a library exception included. Never leaves the engine. Unwraps the cause on the
 * way in, so every path classifies the same throwable. It reads nothing of the cause, neither its message nor its
 * string, and it records no stack trace of its own. Exceptional stage completions reach this wrapper with the
 * original throwable and without reading its message.
 */
final class ActionFailure extends RuntimeException {

    /** Unwraps the cause without reading its message or string or recording this wrapper's stack trace. */
    ActionFailure(Throwable cause) {
        super(null, Unwrap.unwrap(cause), false, false);
    }
}
