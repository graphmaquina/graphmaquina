package io.github.graphmaquina.runtime;

/**
 * The user's action or guard threw, or its stage completed exceptionally. Never leaves the engine.
 * Unwraps the cause on the way in, so every path compares the same throwable against {@code failsOn}.
 */
final class ActionFailure extends RuntimeException {

    ActionFailure(Throwable cause) {
        super(Unwrap.unwrap(cause));
    }
}
