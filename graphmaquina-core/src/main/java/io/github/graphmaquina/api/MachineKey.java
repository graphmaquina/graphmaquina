package io.github.graphmaquina.api;

import lombok.NonNull;

/**
 * Identity of a machine: the triple (entity, state enum, transition enum). Ties the generics together across
 * cascades and watches, so javac rejects a transition that belongs to another machine's catalogue. The entity type is
 * a reference type, a primitive class token being refused, since the runtime casts each entity to it.
 *
 * @param <E> entity type
 * @param <S> state enum
 * @param <T> transition identifier enum
 * @param entity the entity the machine governs; a reference type
 * @param states the enum of its states
 * @param transitions the enum that identifies its transitions
 */
public record MachineKey<E, S extends Enum<S>, T extends Enum<T>>(@NonNull Class<E> entity, @NonNull Class<S> states,
                                                                  @NonNull Class<T> transitions) {

    public MachineKey {
        if (entity.isPrimitive()) {
            throw new IllegalArgumentException("entity must be a reference type; use its wrapper class");
        }
        if (!states.isEnum() || !transitions.isEnum()) {
            throw new IllegalArgumentException("states and transitions must be concrete enum classes");
        }
        if (states.equals(transitions)) {
            throw new IllegalArgumentException("states and transitions must be distinct enums");
        }
    }

    public static <E, S extends Enum<S>, T extends Enum<T>> MachineKey<E, S, T> of(@NonNull Class<E> entity,
                                                                                   @NonNull Class<S> states,
                                                                                   @NonNull Class<T> transitions) {
        return new MachineKey<>(entity, states, transitions);
    }

    /**
     * Simple names of the entity and the state enum only, short enough for diagnostics. A registry never holds two
     * machines over the same pair of classes, so two of its keys print alike only when two such pairs share their
     * simple names.
     */
    @Override
    public String toString() {
        return entity.getSimpleName() + "/" + states.getSimpleName();
    }
}
