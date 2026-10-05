package io.github.graphmaquina.api;

import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/** Domain-level result of a request. Technical failures are not modeled here: they throw. */
public sealed interface Outcome<E, S extends Enum<S>, T extends Enum<T>>
        permits Outcome.Applied, Outcome.Ignored, Outcome.Rejected, Outcome.Failed {

    E entity();

    S from();

    /** Where the entity ended up. */
    S current();

    /** The identifier the caller asked for; empty only when a reevaluation selected no edge. */
    Optional<T> requestedTransition();

    /** The selected edge ran and committed. */
    record Applied<E, S extends Enum<S>, T extends Enum<T>>(@NonNull E entity, @NonNull S from, @NonNull T transition,
                                                            @NonNull S to,
                                                            @NonNull List<TransitionRecord<?, ?, ?>> records,
                                                            @NonNull List<Reaction> reactions) implements Outcome<E, S, T> {
        public Applied {
            records = List.copyOf(records);
            reactions = List.copyOf(reactions);
        }

        @Override
        public S current() {
            return to;
        }

        @Override
        public Optional<T> requestedTransition() {
            return Optional.of(transition);
        }
    }

    /** A reevaluation that selected no edge: no watch on the state, or no rule of its watch held. */
    record Ignored<E, S extends Enum<S>, T extends Enum<T>>(@NonNull E entity, @NonNull S from,
                                                            @NonNull IgnoreReason reason) implements Outcome<E, S, T> {
        @Override
        public S current() {
            return from;
        }

        @Override
        public Optional<T> requestedTransition() {
            return Optional.empty();
        }
    }

    /**
     * No edge leaves {@code from} with that identifier, or a guard rejected. Nothing ran, and there is no
     * destination to report: the same identifier may reach different states from different origins. The reason
     * is never blank.
     */
    record Rejected<E, S extends Enum<S>, T extends Enum<T>>(@NonNull E entity, @NonNull S from, @NonNull T transition,
                                                             @NonNull String reason, @NonNull RejectionKind kind,
                                                             @NonNull Optional<String> guard) implements Outcome<E, S, T> {
        public Rejected {
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
            if (kind == RejectionKind.NO_TRANSITION && guard.isPresent()) {
                throw new IllegalArgumentException("a NO_TRANSITION rejection carries no guard");
            }
            if (kind == RejectionKind.GUARD && guard.isEmpty()) {
                throw new IllegalArgumentException("a GUARD rejection names the guard that rejected");
            }
        }

        @Override
        public S current() {
            return from;
        }

        @Override
        public Optional<T> requestedTransition() {
            return Optional.of(transition);
        }
    }

    /**
     * Domain-level failure routed through {@code failureTransition}. Commits: the entity is at {@code movedTo}.
     * The reason is never blank.
     */
    record Failed<E, S extends Enum<S>, T extends Enum<T>>(@NonNull E entity, @NonNull S from, @NonNull T transition,
                                                           @NonNull S to, @NonNull T failureTransition, @NonNull S movedTo,
                                                           @NonNull String reason, @NonNull Optional<Throwable> cause,
                                                           @NonNull List<TransitionRecord<?, ?, ?>> records,
                                                           @NonNull List<Reaction> reactions) implements Outcome<E, S, T> {
        public Failed {
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
            records = List.copyOf(records);
            reactions = List.copyOf(reactions);
        }

        @Override
        public S current() {
            return movedTo;
        }

        @Override
        public Optional<T> requestedTransition() {
            return Optional.of(transition);
        }
    }
}
