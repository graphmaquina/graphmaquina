package io.stategraph.api;

import lombok.NonNull;

/** Identity of a machine: the pair (entity, state enum). Ties the generics together across cascades and watches. */
public record MachineKey<E, S extends Enum<S>>(@NonNull Class<E> entity, @NonNull Class<S> states) {

    public static <E, S extends Enum<S>> MachineKey<E, S> of(@NonNull Class<E> entity, @NonNull Class<S> states) {
        return new MachineKey<>(entity, states);
    }

    @Override
    public String toString() {
        return entity.getSimpleName() + "/" + states.getSimpleName();
    }
}
