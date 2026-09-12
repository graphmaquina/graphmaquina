package io.github.graphmaquina.test.internal;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.TransitionRecord;
import java.util.List;
import java.util.Objects;

/**
 * Narrows a wildcard record to the machine it belongs to. The engine publishes its hops as
 * {@code TransitionRecord<?, ?>} because one transaction may touch several machines, so a typed view has
 * to recover the pair {@code <E, S>} from a {@link MachineKey}. Every field the key describes is checked
 * against the key's own classes before the one cast this class exists to localize; a record that does not
 * match is a programming error in the calling test and raises {@link IllegalArgumentException}.
 */
public final class CheckedTypes {

    private CheckedTypes() { }

    public static <E, S extends Enum<S>> TransitionRecord<E, S> record(
            MachineKey<E, S> key, TransitionRecord<?, ?> record) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(record, "record");
        require(key.equals(record.key()), "key", key, record.key());
        require(key.entity().isInstance(record.entity()), "entity", key.entity(), record.entity());
        require(key.states().isInstance(record.from()) && key.states().isInstance(record.to()),
                "states", key.states(), List.of(record.from(), record.to()));
        // Safe: the key equals the record's own key and every field is an instance of the classes it names.
        @SuppressWarnings("unchecked")
        TransitionRecord<E, S> typed = (TransitionRecord<E, S>) record;
        return typed;
    }

    private static void require(boolean held, String field, Object expected, Object actual) {
        if (!held) {
            throw new IllegalArgumentException(
                    "record does not belong to the machine: " + field + " expected " + expected + " but was " + actual);
        }
    }
}
