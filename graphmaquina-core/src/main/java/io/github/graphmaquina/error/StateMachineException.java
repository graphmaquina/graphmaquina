package io.github.graphmaquina.error;

import java.util.Objects;

/** Root of everything the library throws. Not meant to be serialized: it carries live domain objects. */
public class StateMachineException extends RuntimeException {

    public StateMachineException(String message) {
        super(Objects.requireNonNull(message, "message"));
    }

    public StateMachineException(String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), Objects.requireNonNull(cause, "cause"));
    }
}
