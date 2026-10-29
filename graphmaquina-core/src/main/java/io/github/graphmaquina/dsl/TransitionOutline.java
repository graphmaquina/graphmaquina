package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.TransitionKind;
import java.util.List;
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
 * @param onFailure the identifier its {@code onFailure} names, when it declares one
 * @param recoveryTransitions the identifiers its recovery rules name, copied in declaration order; an identifier
 *     appears once per rule that names it
 * @param guarded whether the edge declares at least one guard
 * @param automatic whether the edge is declared as automatic
 * @param onFailureContinues whether the {@code onFailure} route is declared as a continuing one; it only counts
 *     when {@code onFailure} is present
 * @param continuingRecoveries the identifiers that at least one continuing recovery rule of the edge names, in
 *     declaration order and without repeats; each one is also in {@code recoveryTransitions}
 */
public record TransitionOutline(@NonNull String from, @NonNull String transition, @NonNull String to,
                                @NonNull TransitionKind kind, @NonNull Optional<String> onFailure,
                                @NonNull List<String> recoveryTransitions, boolean guarded, boolean automatic,
                                boolean onFailureContinues, @NonNull List<String> continuingRecoveries) {

    /** Copies {@code recoveryTransitions}, keeping its order and its repeats, and {@code continuingRecoveries}. */
    public TransitionOutline {
        recoveryTransitions = List.copyOf(recoveryTransitions);
        continuingRecoveries = List.copyOf(continuingRecoveries);
    }

    /** An edge that declares no recovery rule, no guard, and is not automatic. */
    public TransitionOutline(String from, String transition, String to, TransitionKind kind,
                             Optional<String> onFailure) {
        this(from, transition, to, kind, onFailure, List.of(), false, false, false, List.of());
    }

    /**
     * The key that must be unique within the machine, as diagnostics print it: {@code FROM/ID}. Uniqueness is
     * decided on the pair, not on this text, which two distinct pairs share when a name holds a {@code /}.
     */
    public String key() {
        return from + "/" + transition;
    }

    /** {@code FROM -ID-> TO}, the form diagnostics use. */
    public String describe() {
        return from + " -" + transition + "-> " + to;
    }
}
