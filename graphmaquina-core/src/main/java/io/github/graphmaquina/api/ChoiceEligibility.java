package io.github.graphmaquina.api;

import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/**
 * The answer of {@link StateMachine#chooseEligibility} to which of the caller's candidates a choice would run now:
 * {@link Chosen}, the first candidate admitted, or {@link NoneAdmitted} when every candidate is refused. Each candidate
 * gets the answer {@link StateMachine#eligibility} gives for it, in the caller's order, and the refusals are those
 * {@link Eligibility.Ineligible} answers, in that order. Both variants report the entity and a state the check read;
 * neither reports a destination, as an {@link Eligibility} reports none. {@code asChosen()} and
 * {@code asNoneAdmitted()} return the answer as that variant, with its type arguments, or empty for the other one.
 */
public sealed interface ChoiceEligibility<E, S extends Enum<S>, T extends Enum<T>>
        permits ChoiceEligibility.Chosen, ChoiceEligibility.NoneAdmitted {

    E entity();

    /** The state the entity was in when the check read it, as the variant says. */
    S from();

    /** The candidate a choice would run; empty when none is admitted. */
    Optional<T> chosen();

    default Optional<Chosen<E, S, T>> asChosen() {
        return Optional.empty();
    }

    default Optional<NoneAdmitted<E, S, T>> asNoneAdmitted() {
        return Optional.empty();
    }

    /**
     * {@code transition} is the first candidate admitted from {@code from}, the state its check read. {@code rejected}
     * lists the refusal of each candidate before it, in the caller's order, and is empty when the first candidate is
     * admitted. The constructor refuses a null argument or a null refusal, and copies the list.
     */
    record Chosen<E, S extends Enum<S>, T extends Enum<T>>(
            @NonNull E entity, @NonNull S from, @NonNull T transition,
            @NonNull List<Eligibility.Ineligible<E, S, T>> rejected) implements ChoiceEligibility<E, S, T> {

        /** Copies the refusals. */
        public Chosen {
            rejected = List.copyOf(rejected);
        }

        @Override
        public Optional<T> chosen() {
            return Optional.of(transition);
        }

        @Override
        public Optional<Chosen<E, S, T>> asChosen() {
            return Optional.of(this);
        }
    }

    /**
     * No candidate is admitted. {@code rejections} lists the refusal of each candidate, in the caller's order, and
     * {@code from} is the state the check of the first one read. The constructor refuses a null argument, a null
     * refusal and an empty list, and copies the list.
     */
    record NoneAdmitted<E, S extends Enum<S>, T extends Enum<T>>(
            @NonNull E entity, @NonNull S from,
            @NonNull List<Eligibility.Ineligible<E, S, T>> rejections) implements ChoiceEligibility<E, S, T> {

        /** Checks that there is a refusal, and copies the refusals. */
        public NoneAdmitted {
            if (rejections.isEmpty()) {
                throw new IllegalArgumentException("a NoneAdmitted answer lists the refusal of each candidate");
            }
            rejections = List.copyOf(rejections);
        }

        @Override
        public Optional<T> chosen() {
            return Optional.empty();
        }

        @Override
        public Optional<NoneAdmitted<E, S, T>> asNoneAdmitted() {
            return Optional.of(this);
        }
    }
}
