package io.github.graphmaquina.error;

import java.util.Objects;

/**
 * Root of the exceptions the library defines, though not of everything it throws: an invalid argument is a
 * {@code NullPointerException} or an {@code IllegalArgumentException}, a builder misused or left incomplete an
 * {@code IllegalStateException}, and a context used off its thread a {@code WrongThreadException}. Not meant to be
 * serialized: it carries live domain objects.
 */
public class StateMachineException extends RuntimeException {

    public StateMachineException(String message) {
        super(Objects.requireNonNull(message, "message"));
    }

    public StateMachineException(String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), Objects.requireNonNull(cause, "cause"));
    }
}
