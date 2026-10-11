package io.github.graphmaquina.api;

import io.github.graphmaquina.internal.FailureTypes;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/**
 * Domain-level result of a request. Technical failures are not modeled here: they throw, as a
 * {@code CallFailedException} of the call or an Error. {@link #status()} and {@link #code()} classify an outcome as a
 * value and {@link #explanation()} carries the reason given for it, so reading what happened needs no narrowing.
 * {@code asApplied()}, {@code asRejected()}, {@code asFailed()} and {@code asIgnored()} return the outcome as that
 * variant, with the outcome's type arguments, or empty for the other three; the sealed variants stay available to a
 * switch.
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
     * the action returned for a failure. The graph's author writes them all, except the fixed texts the library
     * generates: for a terminal state that declares no policy, and for a {@link RejectionKind#DETACHED} or
     * {@link RejectionKind#RESERVED_EXIT} rejection. Empty for an applied outcome, for an ignored one, whose
     * why is its {@link IgnoreReason}, and for a failure that carries a cause because a declared throwable type, of
     * failsOn or of a recovery rule, routed it from an exception: that reason is composed from the exception and may
     * carry internal details, so it stays in the failure's {@code reason} and {@code cause}.
     */
    Optional<String> explanation();

    /**
     * The hops this root call committed, in log order, cascaded ones and the exits of sub-machines included. Empty for
     * a rejected or ignored outcome, which committed nothing, and for any outcome a cascade returned, whose hops belong
     * to its root. The start of a sub-machine is no hop: {@link #starts()} holds it.
     */
    default List<TransitionRecord<?, ?, ?>> records() {
        return List.of();
    }

    /**
     * The sub-machines this root call started, in log order, cascaded steps included: one per hop that entered a
     * bound state, followed by the start of a grandchild when the child's initial state is bound too. Empty for a
     * rejected or ignored outcome, which committed nothing, and for any outcome a cascade returned, whose starts belong
     * to its root.
     */
    default List<SubmachineStart<?, ?, ?>> starts() {
        return List.of();
    }

    /**
     * The watch reactions of this root call's commit. Empty for a rejected or ignored outcome and for any outcome a
     * cascade returned. An applied outcome may still carry an aborted reaction, so inspect them whatever the status.
     */
    default List<Reaction> reactions() {
        return List.of();
    }

    /**
     * The flow of the root call that returned this outcome, frozen once its hooks and its watch reactions ran: the
     * root call's entity and the values present then, each under the key that put it, in the order of
     * {@link FlowView#keys()}. They are what the call's steps and hooks put and no failure route dropped, so a failed
     * outcome holds the recovery action's writes and none of the failed action's. The view is a copy that the caller
     * may keep and read from any thread, and it compares by value, so equal outcomes need equal flows; its values are
     * the very objects that were put, never copies. Reading never creates the flow; only a write does, a put or a
     * remove that a step or a hook makes. So the flow is {@link FlowView#empty()}, without an entity, for a root call
     * none of whose steps or hooks wrote to it, a rejected outcome included, since only its guards ran and a guard
     * cannot write; for an ignored outcome; and for any outcome a cascade returned, whose flow belongs to its root. A
     * watch reaction is a call of its own: the outcome in {@link Reaction.Done} hands out the reaction's own flow.
     */
    FlowView flow();

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
     * committed, cascaded ones included, in log order, {@code reactions} the watch reactions of that commit,
     * {@code starts} the sub-machines it started, and {@code flow} the call's flow, as {@link Outcome#flow()} says.
     * Returned by a cascade, it is written only if its root commits, the three lists are empty and the flow is
     * {@link FlowView#empty()}. The constructor without {@code starts} reports none, and the constructors without
     * {@code flow} give {@link FlowView#empty()}.
     */
    record Applied<E, S extends Enum<S>, T extends Enum<T>>(
            @NonNull E entity, @NonNull S from, @NonNull T transition, @NonNull S to,
            @NonNull List<TransitionRecord<?, ?, ?>> records,
            @NonNull List<Reaction> reactions,
            @NonNull List<SubmachineStart<?, ?, ?>> starts,
            @NonNull FlowView flow) implements Outcome<E, S, T> {
        public Applied {
            records = List.copyOf(records);
            reactions = List.copyOf(reactions);
            starts = List.copyOf(starts);
        }

        /** Builds an applied outcome whose flow is {@link FlowView#empty()}. */
        public Applied(E entity, S from, T transition, S to, List<TransitionRecord<?, ?, ?>> records,
                       List<Reaction> reactions, List<SubmachineStart<?, ?, ?>> starts) {
            this(entity, from, transition, to, records, reactions, starts, FlowView.empty());
        }

        /** Builds an applied outcome that started no sub-machine and whose flow is {@link FlowView#empty()}. */
        public Applied(E entity, S from, T transition, S to, List<TransitionRecord<?, ?, ?>> records,
                       List<Reaction> reactions) {
            this(entity, from, transition, to, records, reactions, List.of(), FlowView.empty());
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

    /**
     * A reevaluation that selected no edge: no watch on the state, or no rule of its watch held. No step ran, so its
     * flow is {@link FlowView#empty()}.
     */
    record Ignored<E, S extends Enum<S>, T extends Enum<T>>(@NonNull E entity, @NonNull S from,
                                                            @NonNull IgnoreReason reason) implements Outcome<E, S, T> {
        @Override
        public S recordedState() {
            return from;
        }

        @Override
        public FlowView flow() {
            return FlowView.empty();
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
     * The request was refused for the reason {@code kind} names: no edge leaves {@code from} with that identifier, a
     * guard rejected, the entity is a sub-machine whose parent does not hold it, or the identifier is an exit of the
     * sub-machine bound to {@code from}, which no request may ask for from there. No action ran and nothing was
     * written, though on a guard rejection the guards up to the rejecting one ran. {@code guard} names that guard, and
     * is empty for every other kind. It reports no destination. Only a root call returns it: inside a cascade, a
     * rejection aborts the root instead. The reason is never blank. {@code flow} is the call's flow, as
     * {@link Outcome#flow()} says, and the constructor without it gives {@link FlowView#empty()}.
     */
    record Rejected<E, S extends Enum<S>, T extends Enum<T>>(
            @NonNull E entity, @NonNull S from, @NonNull T transition, @NonNull String reason,
            @NonNull RejectionKind kind,
            @NonNull Optional<String> guard,
            @NonNull FlowView flow) implements Outcome<E, S, T> {
        public Rejected {
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

        /** Builds a rejection whose flow is {@link FlowView#empty()}. */
        public Rejected(E entity, S from, T transition, String reason, RejectionKind kind, Optional<String> guard) {
            this(entity, from, transition, reason, kind, guard, FlowView.empty());
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
                case DETACHED -> OutcomeCode.DETACHED;
                case RESERVED_EXIT -> OutcomeCode.RESERVED_EXIT;
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
     * with a throwable that a type its edge declares accepts, which {@code cause} then holds; no declared type accepts
     * an Error, a CancellationException or a StateMachineException. A returned failure takes the recovery of the edge's
     * recovery rule that declares its failure kind, compared by identity, and the edge's onFailure otherwise. A
     * throwable takes the route of the most specific declared type that accepts it, among the types of the edge's
     * recovery rules and of its failsOn, for the types the DSL and RecoveryRule admit: that rule's recovery, or
     * onFailure for a failsOn type. {@code failureKind} is the kind the action returned with its failure, whichever
     * route took it, or the kind of the rule whose type routed the throwable; it is empty for a failure returned
     * without one and for a throwable a failsOn type routed. The entity moves to {@code movedTo}. Returned by a root
     * call, it committed, with {@code records}, {@code reactions}, {@code starts} and {@code flow} as in
     * {@link Applied}; returned by a cascade, it is written only if its root commits, the three lists are empty and the
     * flow is {@link FlowView#empty()}. The constructor refuses a blank reason, and a present failure kind that
     * {@link ActionOutcome#failed(Class, String)} would refuse; the constructors without {@code failureKind} report
     * none, those without {@code starts} report none either, and those without {@code flow} give
     * {@link FlowView#empty()}.
     * When a throwable was routed, it carries it as {@code cause} and a reason composed from it, so
     * {@link #explanation()} leaves that reason out.
     */
    record Failed<E, S extends Enum<S>, T extends Enum<T>>(
            @NonNull E entity, @NonNull S from, @NonNull T transition, @NonNull S to,
            @NonNull T failureTransition, @NonNull S movedTo,
            @NonNull String reason, @NonNull Optional<Throwable> cause,
            @NonNull List<TransitionRecord<?, ?, ?>> records,
            @NonNull List<Reaction> reactions,
            @NonNull Optional<Class<? extends FailureKind>> failureKind,
            @NonNull List<SubmachineStart<?, ?, ?>> starts,
            @NonNull FlowView flow) implements Outcome<E, S, T> {
        /** Validates the reason and failure kind, and copies the committed records, reactions and starts. */
        public Failed {
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
            failureKind.ifPresent(FailureTypes::requireValidKind);
            records = List.copyOf(records);
            reactions = List.copyOf(reactions);
            starts = List.copyOf(starts);
        }

        /** Builds a routed failure whose flow is {@link FlowView#empty()}. */
        public Failed(E entity, S from, T transition, S to, T failureTransition, S movedTo, String reason,
                      Optional<Throwable> cause, List<TransitionRecord<?, ?, ?>> records, List<Reaction> reactions,
                      Optional<Class<? extends FailureKind>> failureKind, List<SubmachineStart<?, ?, ?>> starts) {
            this(entity, from, transition, to, failureTransition, movedTo, reason, cause, records, reactions,
                    failureKind, starts, FlowView.empty());
        }

        /** Builds a routed failure that started no sub-machine and whose flow is {@link FlowView#empty()}. */
        public Failed(E entity, S from, T transition, S to, T failureTransition, S movedTo, String reason,
                      Optional<Throwable> cause, List<TransitionRecord<?, ?, ?>> records, List<Reaction> reactions,
                      Optional<Class<? extends FailureKind>> failureKind) {
            this(entity, from, transition, to, failureTransition, movedTo, reason, cause, records, reactions,
                    failureKind, List.of(), FlowView.empty());
        }

        /**
         * Builds a routed failure without a failure kind that started no sub-machine and whose flow is
         * {@link FlowView#empty()}.
         */
        public Failed(E entity, S from, T transition, S to, T failureTransition, S movedTo, String reason,
                      Optional<Throwable> cause, List<TransitionRecord<?, ?, ?>> records, List<Reaction> reactions) {
            this(entity, from, transition, to, failureTransition, movedTo, reason, cause, records, reactions,
                    Optional.empty(), List.of(), FlowView.empty());
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
