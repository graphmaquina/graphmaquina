package io.github.graphmaquina.error;

import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.MachineKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.Getter;

/** ABORT: nothing moved and the UnitOfWork was discarded. */
public class TransitionAbortedException extends StateMachineException {

    @Getter
    private final AbortDetails details;

    public TransitionAbortedException(AbortDetails details, String message) {
        super(describe(details, message));
        this.details = details;
    }

    public TransitionAbortedException(AbortDetails details, String message, Throwable cause) {
        super(describe(details, message), cause);
        this.details = details;
    }

    public MachineKey<?, ?> key() { return details.key(); }

    public Object entity() { return details.entity(); }

    public Enum<?> from() { return details.from(); }

    public Enum<?> to() { return details.to(); }

    public Optional<String> action() { return details.action(); }

    public List<String> cascadePath() { return details.cascadePath(); }

    /** Actions that ran, in order, with their status. The only record of which external effects were left loose. */
    public List<ActionRecord> journal() { return details.journal(); }

    private static String describe(AbortDetails details, String message) {
        Objects.requireNonNull(details, "details");
        Objects.requireNonNull(message, "message");
        String path = details.cascadePath().isEmpty() ? "" : " [route " + String.join(" → ", details.cascadePath()) + "]";
        return "transition aborted in " + details.key() + " " + details.from() + " → " + details.to() + ": " + message + path;
    }
}
