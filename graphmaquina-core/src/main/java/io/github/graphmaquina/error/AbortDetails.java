package io.github.graphmaquina.error;

import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.MachineKey;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/**
 * Common payload of every abort: what was being attempted, how far the cascade had gone, and which actions
 * managed to run. The requested identifier and the destination are optional because an abort can happen before
 * any edge was resolved, and no destination is invented for a request that matched none.
 *
 * @param key the machine that was running
 * @param entity the entity it was running on
 * @param from the state the entity was in
 * @param transition the identifier that was requested, if the abort happened after reading it
 * @param to the destination of the resolved edge, present only once one was selected
 * @param action the name of the action that was running, if any
 * @param cascadePath the entities in flight, outermost first
 * @param journal the actions that ran, in order, with their status
 */
public record AbortDetails(@NonNull MachineKey<?, ?, ?> key, @NonNull Object entity, @NonNull Enum<?> from,
                           @NonNull Optional<Enum<?>> transition, @NonNull Optional<Enum<?>> to,
                           @NonNull Optional<String> action, @NonNull List<String> cascadePath,
                           @NonNull List<ActionRecord> journal) {

    public AbortDetails {
        cascadePath = List.copyOf(cascadePath);
        journal = List.copyOf(journal);
    }

    /** {@code FROM -ID-> TO}, with the parts that are not known yet left out. */
    public String describeEdge() {
        if (transition.isEmpty()) {
            return from.toString();
        }
        return from + " -" + transition.get() + "-> " + to.map(Enum::toString).orElse("?");
    }
}
