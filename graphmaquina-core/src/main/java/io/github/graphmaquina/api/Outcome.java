package io.github.graphmaquina.api;

import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/**
 * Domain-level result of a request. Technical failures are not modeled here: they throw, as a
 * {@code CallFailedException} of the call or an Error. {@link #status()} and
 * {@link #code()} classify an outcome as a value and {@link #explanation()} carries the reason given for it, so
 * reading what happened needs no narrowing. {@code asApplied()}, {@code asRejected()}, {@code asFailed()} and
 * {@code asIgnored()} return the outcome as that variant, with the outcome's type arguments, or empty for the other
 * three; the sealed variants stay available to a switch.
 */
public sealed interface Outcome<E, S extends Enum<S>, T extends Enum<T>>
        permits Outcome.Applied, Outcome.Ignored, Outcome.Rejected, Outcome.Failed {

    E entity();

    S from();

    /**
     * The state this call recorded for the entity: {@code to} for Applied, {@code movedTo} for Failed, and
     * {@code from} for Rejected and Ignored. It is not a read of the entity: an after-commit hook, a watch reaction
     * or a later call may have moved it since, so read the entity for its live state.
     */
    S recordedState();

    /**
     * The identifier the caller asked for, or the one the watch selected in a reevaluation; empty only when a
     * reevaluation selected no edge.
     */
    Optional<T> requestedTransition();

    /**
     * Why this outcome is what it is: its variant, refined by the kind of a rejection and by the reason a
     * reevaluation selected nothing.
     */
    OutcomeCode code();

    /**
     * Which variant this is, as the status of {@link #code()}. Inside a cascade, {@link OutcomeStatus#APPLIED} and
     * {@link OutcomeStatus#FAILED} are provisional: the hop is written only if the root commits.
     */
    default OutcomeStatus status() {
        return code().status();
    }

    /**
     * The reason given for this result: the reason of the state policy or of the guard for a rejection, and the reason
     * the action returned for a failure. The graph's author writes them all, except the fixed text the library
     * generates for a terminal state that declares no policy. Empty for an applied outcome, for an ignored one, whose
     * why is its {@link IgnoreReason}, and for a failure that carries a cause because failsOn routed it from an
     * exception: that reason is composed from the exception and may carry internal details, so it stays in the
     * failure's {@code reason} and {@code cause}.
     */
    Optional<String> explanation();

    /**
     * The hops this root call committed, in log order, cascaded ones included. Empty for a rejected or ignored
     * outcome, which committed nothing, and for any outcome a cascade returned, whose hops belong to its root.
     */
    default List<TransitionRecord<?, ?, ?>> records() {
        return List.of();
    }

    /**
     * The watch reactions of this root call's commit. Empty for a rejected or ignored outcome and for any outcome a
     * cascade returned. An applied outcome may still carry an aborted reaction, so inspect them whatever the status.
     */
    default List<Reaction> reactions() {
        return List.of();
    }

    default Optional<Applied<E, S, T>> asApplied() {
        return Optional.empty();
    }

    default Optional<Rejected<E, S, T>> asRejected() {
        return Optional.empty();
    }

    default Optional<Failed<E, S, T>> asFailed() {
        return Optional.empty();
    }

    default Optional<Ignored<E, S, T>> asIgnored() {
        return Optional.empty();
    }

    /**
     * The selected edge ran. Returned by a root call, it committed: {@code records} holds every hop the root
     * committed, cascaded ones included, in log order, and {@code reactions} the watch reactions of that commit.
     * Returned by a cascade, it is written only if its root commits, and both lists are empty.
     */
    record Applied<E, S extends Enum<S>, T extends Enum<T>>(@NonNull E entity, @NonNull S from, @NonNull T transition,
                                                            @NonNull S to,
                                                            @NonNull List<TransitionRecord<?, ?, ?>> records,
                                                            @NonNull List<Reaction> reactions) implements Outcome<E, S, T> {
        public Applied {
            records = List.copyOf(records);
            reactions = List.copyOf(reactions);
        }

        @Override
        public S recordedState() {
            return to;
        }

        @Override
        public Optional<T> requestedTransition() {
            return Optional.of(transition);
        }

        @Override
        public OutcomeCode code() {
            return OutcomeCode.APPLIED;
        }

        @Override
        public Optional<String> explanation() {
            return Optional.empty();
        }

        @Override
        public Optional<Applied<E, S, T>> asApplied() {
            return Optional.of(this);
        }
    }

    /** A reevaluation that selected no edge: no watch on the state, or no rule of its watch held. */
    record Ignored<E, S extends Enum<S>, T extends Enum<T>>(@NonNull E entity, @NonNull S from,
                                                            @NonNull IgnoreReason reason) implements Outcome<E, S, T> {
        @Override
        public S recordedState() {
            return from;
        }

        @Override
        public Optional<T> requestedTransition() {
            return Optional.empty();
        }

        @Override
        public OutcomeCode code() {
            return switch (reason) {
                case NO_WATCH -> OutcomeCode.NO_WATCH;
                case NO_RULE_MATCHED -> OutcomeCode.NO_RULE_MATCHED;
            };
        }

        @Override
        public Optional<String> explanation() {
            return Optional.empty();
        }

        @Override
        public Optional<Ignored<E, S, T>> asIgnored() {
            return Optional.of(this);
        }
    }

    /**
     * No edge leaves {@code from} with that identifier, or a guard rejected: no action ran and nothing was written,
     * though on a guard rejection the guards up to the rejecting one ran. {@code guard} names that guard, and is
     * empty for NO_TRANSITION. It reports no destination. Only a root call returns it: inside a cascade, a rejection
     * aborts the root instead. The reason is never blank.
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
        public S recordedState() {
            return from;
        }

        @Override
        public Optional<T> requestedTransition() {
            return Optional.of(transition);
        }

        @Override
        public OutcomeCode code() {
            return switch (kind) {
                case NO_TRANSITION -> OutcomeCode.NO_TRANSITION;
                case GUARD -> OutcomeCode.GUARD_REJECTED;
            };
        }

        @Override
        public Optional<String> explanation() {
            return Optional.of(reason);
        }

        @Override
        public Optional<Rejected<E, S, T>> asRejected() {
            return Optional.of(this);
        }
    }

    /**
     * Domain-level failure routed through {@code failureTransition}: the action returned a failed outcome, or failed
     * with an exception its edge lists in failsOn, which {@code cause} then holds. The entity moves to
     * {@code movedTo}. Returned by a root call, it committed, with {@code records} and {@code reactions} as in
     * {@link Applied}; returned by a cascade, it is written only if its root commits, and both lists are empty. The
     * reason is never blank. When failsOn routed the failure, it carries the exception as {@code cause} and a reason
     * composed from it, so {@link #explanation()} leaves that reason out.
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
        public S recordedState() {
            return movedTo;
        }

        @Override
        public Optional<T> requestedTransition() {
            return Optional.of(transition);
        }

        @Override
        public OutcomeCode code() {
            return OutcomeCode.FAILURE_ROUTED;
        }

        @Override
        public Optional<String> explanation() {
            return cause.isPresent() ? Optional.empty() : Optional.of(reason);
        }

        @Override
        public Optional<Failed<E, S, T>> asFailed() {
            return Optional.of(this);
        }
    }
}
