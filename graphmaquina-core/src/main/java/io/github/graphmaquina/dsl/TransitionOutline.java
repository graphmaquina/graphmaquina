package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.TransitionKind;
import java.util.Optional;
import lombok.NonNull;

/**
 * One edge of a {@link MachineOutline}, by names: enough for the structural checks, nothing about code.
 * It holds whatever the DSL or the processor normalised, invalid combinations included, so that
 * {@link MachineOutlineValidator} can answer with a positioned code instead of a thrown constructor.
 *
 * @param from origin state name
 * @param transition identifier name
 * @param to destination state name
 * @param kind whether the edge runs an action
 * @param onFailure the identifier this edge recovers through, when it declares one
 */
public record TransitionOutline(@NonNull String from, @NonNull String transition, @NonNull String to,
                                @NonNull TransitionKind kind, @NonNull Optional<String> onFailure) {

    /** The key that must be unique within the machine. */
    public String key() {
        return from + "/" + transition;
    }

    /** {@code FROM -ID-> TO}, the form diagnostics use. */
    public String describe() {
        return from + " -" + transition + "-> " + to;
    }
}
