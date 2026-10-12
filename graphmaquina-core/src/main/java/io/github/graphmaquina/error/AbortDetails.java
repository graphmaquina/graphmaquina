package io.github.graphmaquina.error;

import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.MachineKey;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/**
 * Common payload of every abort: what was being attempted, how far the cascade had gone, and which actions
 * managed to run. The state, the requested identifier and the destination are optional because an abort can happen
 * before the state was read or before any edge was resolved, and no destination is invented for a request that
 * matched none. An abort raised for the caller of a cascade or a peer read, such as a cycle, the depth limit, a
 * failed cascade, COLLECT's aggregate or a poisoned transaction, describes the caller's own step. One raised for the
 * exit that the end of a sub-machine calls for, a terminal state with no exit or the depth limit, describes the step
 * that ended, which is no longer in flight.
 *
 * @param key the machine that was running
 * @param entity the entity it was running on
 * @param from the state the entity was in, absent when the abort happened before it was read, because the entity's
 *     identity or state could not be read
 * @param transition the identifier that was requested, if the abort happened after reading it
 * @param to the destination of the resolved edge, present only once one was selected
 * @param action the action whose own run caused the abort, when one did; absent for a guard, a rejection, the watch
 *     chain limit and an abort raised for the caller of a cascade or a peer read, even when that caller is a running
 *     action, and the abort the engine raises when its own code lets a failure through, which names no action even
 *     while one ran
 * @param cascadePath the entities in flight, outermost first; the engine prints each as {@code key#id}, with
 *     {@code <toString threw <class name>>} in place of an id whose {@code toString()} throws anything but an Error,
 *     and as its machine key alone in the abort that poisons a cascade when printing threw an Error
 * @param journal the actions that ran, in order, with their status
 */
public record AbortDetails(@NonNull MachineKey<?, ?, ?> key, @NonNull Object entity, @NonNull Optional<Enum<?>> from,
                           @NonNull Optional<Enum<?>> transition, @NonNull Optional<Enum<?>> to,
                           @NonNull Optional<String> action, @NonNull List<String> cascadePath,
                           @NonNull List<ActionRecord> journal) {

    public AbortDetails {
        cascadePath = List.copyOf(cascadePath);
        journal = List.copyOf(journal);
    }

    /**
     * {@code FROM -ID-> TO}, each constant by its {@code name()}, never through a {@code toString()} the application
     * may override: only {@code FROM} when no identifier was read, {@code ?} in place of a state that was not read and
     * in place of a destination that was never selected.
     */
    public String describeEdge() {
        String origin = from.map(Enum::name).orElse("?");
        if (transition.isEmpty()) {
            return origin;
        }
        return origin + " -" + transition.get().name() + "-> " + to.map(Enum::name).orElse("?");
    }
}
