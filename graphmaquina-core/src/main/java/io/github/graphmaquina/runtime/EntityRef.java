package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.internal.Texts;
import java.util.Objects;

/** Identity of an entity within a transaction: (machine, id). The instance does not take part in equals. */
record EntityRef(MachineKey<?, ?, ?> key, Object id, Object instance) {

    @Override
    public boolean equals(Object other) {
        return other instanceof EntityRef that && key.equals(that.key) && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return 31 * (31 + Objects.hashCode(key)) + Objects.hashCode(id);
    }

    /**
     * {@code key#id}, the machine key always there: an id whose {@code toString()} throws anything but an Error, a
     * checked exception included, prints {@code <toString threw <class name>>} in its place, and one whose
     * {@code toString()} answers null prints {@code null}. An Error propagates.
     */
    @Override
    public String toString() {
        return key + "#" + Texts.identity(id);
    }
}
