package io.stategraph.runtime;

/** The user's action or guard threw, or its stage completed exceptionally. Never leaves the engine. */
final class ActionFailure extends RuntimeException {

    ActionFailure(Throwable cause) {
        super(cause);
    }
}
