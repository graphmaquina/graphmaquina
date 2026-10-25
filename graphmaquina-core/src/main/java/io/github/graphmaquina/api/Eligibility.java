package io.github.graphmaquina.api;

import java.util.Optional;
import lombok.NonNull;

/**
 * The answer of {@link StateMachine#eligibility} to whether a request is eligible: {@link Eligible} when it is,
 * {@link Ineligible} when it is not. Both variants report the entity, the state the check read and the identifier it
 * asked about, and neither reports a destination: a rejection reports none, and an eligible answer that named one
 * would read as a promise the check does not make. {@link #explanation()} gives the reason of an ineligible answer and
 * is empty for an eligible one. {@code asEligible()} and {@code asIneligible()} return the answer as that variant,
 * with its type arguments, or empty for the other one.
 */
public sealed interface Eligibility<E, S extends Enum<S>, T extends Enum<T>>
        permits Eligibility.Eligible, Eligibility.Ineligible {

    E entity();

    /** The state the entity was in when the check read it. */
    S from();

    /** The identifier the check asked about. */
    T transition();

    /** Whether this is an {@link Eligible} answer. */
    boolean isEligible();

    /** The reason of an ineligible answer; empty for an eligible one. */
    Optional<String> explanation();

    default Optional<Eligible<E, S, T>> asEligible() {
        return Optional.empty();
    }

    default Optional<Ineligible<E, S, T>> asIneligible() {
        return Optional.empty();
    }

    /** The request is eligible: no refusal applies to it from {@code from}. */
    record Eligible<E, S extends Enum<S>, T extends Enum<T>>(
            @NonNull E entity, @NonNull S from, @NonNull T transition) implements Eligibility<E, S, T> {

        @Override
        public boolean isEligible() {
            return true;
        }

        @Override
        public Optional<String> explanation() {
            return Optional.empty();
        }

        @Override
        public Optional<Eligible<E, S, T>> asEligible() {
            return Optional.of(this);
        }
    }

    /**
     * The request is not eligible, for the reason {@code kind} names, the kinds of a rejection: no edge leaves
     * {@code from} with that identifier, a guard refuses it, the entity is a sub-machine whose parent does not hold
     * it, or the identifier is an exit of the sub-machine bound to {@code from}. {@code guard} names the guard that
     * refuses it, and is empty for every other kind. The constructor refuses a blank reason, and a guard that does not
     * match the kind, with the messages of {@link Outcome.Rejected}.
     */
    record Ineligible<E, S extends Enum<S>, T extends Enum<T>>(
            @NonNull E entity, @NonNull S from, @NonNull T transition, @NonNull String reason,
            @NonNull RejectionKind kind,
            @NonNull Optional<String> guard) implements Eligibility<E, S, T> {
        public Ineligible {
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
            if (kind != RejectionKind.GUARD && guard.isPresent()) {
                throw new IllegalArgumentException("a " + kind + " rejection carries no guard");
            }
            if (kind == RejectionKind.GUARD && guard.isEmpty()) {
                throw new IllegalArgumentException("a GUARD rejection names the guard that rejected");
            }
        }

        @Override
        public boolean isEligible() {
            return false;
        }

        @Override
        public Optional<String> explanation() {
            return Optional.of(reason);
        }

        @Override
        public Optional<Ineligible<E, S, T>> asIneligible() {
            return Optional.of(this);
        }
    }
}
