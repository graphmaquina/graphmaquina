package io.github.graphmaquina.test.internal;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.TransitionRecord;
import java.util.List;
import java.util.Objects;

/**
 * Narrows a wildcard record or outcome to the machine it belongs to. The engine publishes its hops as
 * {@code TransitionRecord<?, ?>} because one transaction may touch several machines, and a watch reaction
 * publishes its {@code Outcome<?, ?>} because the machine that re-evaluated is not the one that committed, so
 * a typed view has to recover the pair {@code <E, S>} from a {@link MachineKey}. Every field the key describes
 * is checked against the key's own classes before the one cast this class exists to localize; a value that does
 * not match is a programming error in the calling test and raises {@link IllegalArgumentException}.
 */
public final class CheckedTypes {

    private CheckedTypes() { }

    public static <E, S extends Enum<S>> TransitionRecord<E, S> record(
            MachineKey<E, S> key, TransitionRecord<?, ?> record) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(record, "record");
        require("record", key.equals(record.key()), "key", key, record.key());
        require("record", key.entity().isInstance(record.entity()), "entity", key.entity(), record.entity());
        require("record", key.states().isInstance(record.from()) && key.states().isInstance(record.to()),
                "states", key.states(), List.of(record.from(), record.to()));
        // Safe: the key equals the record's own key and every field is an instance of the classes it names.
        @SuppressWarnings("unchecked")
        TransitionRecord<E, S> typed = (TransitionRecord<E, S>) record;
        return typed;
    }

    /**
     * An outcome carries no key of its own, so every state it names is checked instead: the origin, the state
     * it records as current, and the target it was asked for, which a rejected or ignored outcome reports
     * without ever having moved there.
     */
    public static <E, S extends Enum<S>> Outcome<E, S> outcome(MachineKey<E, S> key, Outcome<?, ?> outcome) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(outcome, "outcome");
        require("outcome", key.entity().isInstance(outcome.entity()), "entity", key.entity(), outcome.entity());
        Enum<?> target = targetOf(outcome);
        List<Enum<?>> states = List.of(outcome.from(), outcome.current(), target);
        require("outcome", states.stream().allMatch(key.states()::isInstance), "states", key.states(), states);
        // Safe: the entity and all three states are instances of the classes the key names.
        @SuppressWarnings("unchecked")
        Outcome<E, S> typed = (Outcome<E, S>) outcome;
        return typed;
    }

    private static Enum<?> targetOf(Outcome<?, ?> outcome) {
        return switch (outcome) {
            case Outcome.Applied<?, ?> applied -> applied.to();
            case Outcome.Failed<?, ?> failed -> failed.to();
            case Outcome.Rejected<?, ?> rejected -> rejected.to();
            case Outcome.Ignored<?, ?> ignored -> ignored.to();
        };
    }

    private static void require(String subject, boolean held, String field, Object expected, Object actual) {
        if (!held) {
            throw new IllegalArgumentException(subject + " does not belong to the machine: " + field
                    + " expected " + expected + " but was " + actual);
        }
    }
}
