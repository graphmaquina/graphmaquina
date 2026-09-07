package io.stategraph.api;

import java.util.Objects;

/** Identity of a machine: the pair (entity, state enum). Ties the generics together across cascades and watches. */
public record MachineKey<E, S extends Enum<S>>(Class<E> entity, Class<S> states) {

    public MachineKey {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(states, "states");
    }

    public static <E, S extends Enum<S>> MachineKey<E, S> of(Class<E> entity, Class<S> states) {
        return new MachineKey<>(entity, states);
    }

    @Override
    public String toString() {
        return entity.getSimpleName() + "/" + states.getSimpleName();
    }
}
