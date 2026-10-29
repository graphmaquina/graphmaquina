package io.github.graphmaquina.test.internal;

import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.TransitionRecord;
import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;

/**
 * Narrows a wildcard record or outcome to the machine it belongs to. The engine publishes its hops as
 * {@code TransitionRecord<?, ?, ?>} because one transaction may touch several machines, and a watch reaction
 * publishes its {@code Outcome<?, ?, ?>} because the machine that re-evaluated is not the one that committed,
 * so a typed view has to recover the triple {@code <E, S, T>} from a {@link MachineKey}. Every entity, state
 * and identifier the key describes, the recovery identifier of a failed outcome included, is checked against
 * the key's own classes before the unchecked cast this class exists to localize. A value that does not match
 * is a programming error in the calling test and raises {@link IllegalArgumentException}.
 */
public final class CheckedTypes {

    private CheckedTypes() { }

    public static <E, S extends Enum<S>, T extends Enum<T>> TransitionRecord<E, S, T> record(
            @NonNull MachineKey<E, S, T> key, @NonNull TransitionRecord<?, ?, ?> record) {
        require("record", key.equals(record.key()), "key", key, record.key());
        require("record", key.entity().isInstance(record.entity()), "entity", key.entity(), record.entity());
        require("record", key.states().isInstance(record.from()) && key.states().isInstance(record.to()),
                "states", key.states(), List.of(record.from(), record.to()));
        require("record", key.transitions().isInstance(record.transition()),
                "transitions", key.transitions(), record.transition());
        // Safe: the key equals the record's own key and every field is an instance of the classes it names.
        @SuppressWarnings("unchecked")
        TransitionRecord<E, S, T> typed = (TransitionRecord<E, S, T>) record;
        return typed;
    }

    /**
     * An outcome carries no key of its own, so the values it names are checked instead: the entity, the origin,
     * the state it records, the destination of an applied or failed outcome, the identifier it was
     * asked for, which a rejected outcome reports without ever having run an edge and an ignored one does not
     * report at all, and the recovery identifier of a failed outcome.
     */
    public static <E, S extends Enum<S>, T extends Enum<T>> Outcome<E, S, T> outcome(
            @NonNull MachineKey<E, S, T> key, @NonNull Outcome<?, ?, ?> outcome) {
        require("outcome", key.entity().isInstance(outcome.entity()), "entity", key.entity(), outcome.entity());
        List<Enum<?>> states = statesOf(outcome);
        require("outcome", states.stream().allMatch(key.states()::isInstance), "states", key.states(), states);
        require("outcome", outcome.requestedTransition().stream().allMatch(key.transitions()::isInstance),
                "transitions", key.transitions(), outcome.requestedTransition());
        if (outcome instanceof Outcome.Failed<?, ?, ?> failed) {
            require("outcome", key.transitions().isInstance(failed.failureTransition()),
                    "transitions", key.transitions(), failed.failureTransition());
        }
        // Safe: the entity, every state and every identifier the outcome carries, the recovery identifier of a
        // failed one included, are instances of the classes the key names.
        @SuppressWarnings("unchecked")
        Outcome<E, S, T> typed = (Outcome<E, S, T>) outcome;
        return typed;
    }

    /**
     * A failed outcome found by a search through the attempts of {@code owner}, narrowed to the machine of {@code
     * owner}. A search publishes its result untyped because the attempts can be of other machines, and the result
     * carries no key, so the machine is recognised by what it does carry: see {@link #sameMachine}. An outcome of
     * another machine is a programming error in the calling test and raises {@link IllegalArgumentException}.
     */
    public static <E, S extends Enum<S>, T extends Enum<T>> Outcome.Failed<E, S, T> failedOf(
            @NonNull Outcome<E, S, T> owner, @NonNull Outcome.Failed<?, ?, ?> found) {
        sameMachine(owner, found, List.of(found.failureTransition()));
        // Safe: the entity, the origin and the identifiers the failure carries belong to the owner's classes.
        @SuppressWarnings("unchecked")
        Outcome.Failed<E, S, T> typed = (Outcome.Failed<E, S, T>) found;
        return typed;
    }

    /** An ignored outcome found by a search through the attempts of {@code owner}, narrowed like {@link #failedOf}. */
    public static <E, S extends Enum<S>, T extends Enum<T>> Outcome.Ignored<E, S, T> ignoredOf(
            @NonNull Outcome<E, S, T> owner, @NonNull Outcome.Ignored<?, ?, ?> found) {
        List<Enum<?>> identifiers = new ArrayList<>();
        found.rejections().forEach((Eligibility.Ineligible<?, ?, ?> rejection) ->
                identifiers.add(rejection.transition()));
        sameMachine(owner, found, identifiers);
        // Safe: the entity, the origin and the identifiers of every rejection belong to the owner's classes.
        @SuppressWarnings("unchecked")
        Outcome.Ignored<E, S, T> typed = (Outcome.Ignored<E, S, T>) found;
        return typed;
    }

    /**
     * The entity of {@code found} and the owner's are instances of one another's class, their origins belong to one
     * state enum and the identifiers of {@code found}, with the one the owner requested if it requested any, to one
     * transition enum.
     */
    private static void sameMachine(Outcome<?, ?, ?> owner, Outcome<?, ?, ?> found, List<Enum<?>> identifiers) {
        require("outcome", owner.entity().getClass().isInstance(found.entity())
                || found.entity().getClass().isInstance(owner.entity()),
                "entity", owner.entity().getClass(), found.entity());
        require("outcome", owner.from().getDeclaringClass() == found.from().getDeclaringClass(),
                "states", owner.from().getDeclaringClass(), found.from());
        List<Enum<?>> all = new ArrayList<>(identifiers);
        owner.requestedTransition().ifPresent(all::add);
        Class<?> transitions = all.isEmpty() ? null : all.get(0).getDeclaringClass();
        require("outcome", all.stream().allMatch((Enum<?> id) -> id.getDeclaringClass() == transitions),
                "transitions", transitions, all);
    }

    /** Origin and recorded state always, plus the destination, which only an applied and a failed outcome report. */
    private static List<Enum<?>> statesOf(Outcome<?, ?, ?> outcome) {
        List<Enum<?>> states = new ArrayList<>();
        states.add(outcome.from());
        states.add(outcome.recordedState());
        switch (outcome) {
            case Outcome.Applied<?, ?, ?> applied -> states.add(applied.to());
            case Outcome.Failed<?, ?, ?> failed -> states.add(failed.to());
            default -> { }
        }
        return List.copyOf(states);
    }

    private static void require(String subject, boolean held, String field, Object expected, Object actual) {
        if (!held) {
            throw new IllegalArgumentException(subject + " does not belong to the machine: " + field
                    + " expected " + expected + " but was " + actual);
        }
    }
}
