package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.MachineKey;
import java.util.Objects;

/** Identity of an entity within a transaction: (machine, id). The instance does not take part in equals. */
record EntityRef(MachineKey<?, ?> key, Object id, Object instance) {

    @Override
    public boolean equals(Object other) {
        return other instanceof EntityRef that && key.equals(that.key) && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, id);
    }

    @Override
    public String toString() {
        return key + "#" + id;
    }
}
