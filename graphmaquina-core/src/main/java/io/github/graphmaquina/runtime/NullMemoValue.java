package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.MemoKey;

/**
 * What {@code memo} throws when its supplier answers null: the engine turns it into the abort a guard that returns null
 * gets, as {@link Steps} says, without taking it for something the guard threw. Thrown only on the thread running the
 * guard's test call.
 */
final class NullMemoValue extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The key whose supplier answered null. */
    private final transient MemoKey<?> key;

    NullMemoValue(MemoKey<?> key) {
        super("the supplier of the memo key " + key.name() + " answered null", null, false, false);
        this.key = key;
    }

    /** The key whose supplier answered null. */
    MemoKey<?> key() {
        return key;
    }
}
