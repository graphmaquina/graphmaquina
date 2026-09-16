package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.TransitionKind;
import java.util.Optional;
import lombok.NonNull;

/** One edge of a {@link MachineOutline}, by names: enough for the structural checks, nothing about code. */
public record TransitionOutline(@NonNull String from, @NonNull String transition, @NonNull String to,
                                @NonNull TransitionKind kind, @NonNull Optional<String> onFailure) {

    public TransitionOutline {
        if (onFailure.isPresent() && kind != TransitionKind.ACTION) {
            throw new IllegalArgumentException(from + " -" + transition + "-> " + to
                    + ": onFailure is only allowed with an action");
        }
    }

    /** The key that must be unique within the machine. */
    public String key() {
        return from + "/" + transition;
    }
}
