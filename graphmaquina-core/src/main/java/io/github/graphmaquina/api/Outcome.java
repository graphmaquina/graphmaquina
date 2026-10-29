package io.github.graphmaquina.api;

import io.github.graphmaquina.internal.FailureTypes;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
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
     * The identifier the caller asked for, the one the watch selected in a reevaluation, or the automatic edge an
     * advance admitted; empty only for an ignored outcome, which ran no edge.
     */
    Optional<T> requestedTransition();

    /**
     * Why this outcome is what it is: its variant, refined by the kind of a rejection and by the reason a
     * reevaluation or an advance ran no edge.
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
     * {@link RejectionKind#RESERVED_EXIT} rejection. An ignored outcome of {@link IgnoreReason#NO_CANDIDATE_ADMITTED}
     * gives a text the library composes from the reasons of its rejections, as {@link Ignored#explanation()} says.
     * Empty for an applied outcome, for any other ignored one, whose why is its {@link IgnoreReason}, and for a
     * failure that carries a cause because a declared throwable type, of failsOn or of a recovery rule, routed it from
     * an exception: that reason is composed from the exception and may carry internal details, so it stays in the
     * failure's {@code reason} and {@code cause}.
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
     * The automatic attempts this root call's commit triggered once its watch reactions ran. Each entity with a hop or
     * a sub-machine start in the commit, a self-loop or a round trip included, runs its chain, in the order of the
     * commit's log, unless its last hop there is a failure route that does not continue the chain: from the state the
     * commit left it in, while that state has an automatic edge, one attempt after another, each a call of its own, an
     * {@link AutomaticStep.Done} with its outcome or an {@link AutomaticStep.Aborted}. The chain stops after an attempt
     * that admits no candidate, which is listed with its rejections, after one whose failure takes a route that does
     * not continue, and after one that aborts. The list holds each chain whole, flat and in order. The deepest commit
     * that moved an entity chains it: when a call made on this thread inside this call's post-commit, a hook's call,
     * a watch reaction or an attempt, committed a hop or a start of the entity before its chain began here, that call
     * decided the chain, which is not here; and a chain stops after an attempt whose own post-commit ran such a call
     * for its entity. So the attempts that the commit of an attempt triggers for other entities are in that attempt's
     * own outcome, and those of a chain that a watch reaction decided are in that reaction's outcome, in
     * {@link #reactions()}. The outcome of an advance is its first attempt, so the chain of its entity here starts with
     * the attempt that followed. Empty for a rejected or ignored outcome and for any outcome a cascade returned. An
     * applied outcome may still end in an aborted step, so inspect them whatever the status, or search them with
     * {@link #automaticFailure()}, {@link #automaticAbort()} and {@link #automaticBlock()}.
     */
    default List<AutomaticStep> automaticSteps() {
        return List.of();
    }

    /**
     * The outcome of the first automatic attempt of this call that ended in {@link Failed}, whether its route stopped
     * the chain or let it go on, among every attempt that ran for this call, nested ones included. The search follows
     * the order the attempts ran: first the {@link #reactions()}, each {@link Reaction.Done} searched through its
     * outcome the same way, then the {@link #automaticSteps()}, each step looked at itself and then searched through
     * its outcome, its reactions and its steps. A watch reaction's own result never counts: a
     * {@link Reaction.Done} whose outcome is Failed is the result of a watch, which {@link #reactions()} holds.
     * Empty when no attempt failed, and for a rejected or ignored outcome, which has neither steps nor reactions; the
     * outcome of an {@code advance} is its own first attempt, which this does not return.
     */
    default Optional<Failed<?, ?, ?>> automaticFailure() {
        return AutomaticSearch.failure(this);
    }

    /**
     * The first automatic step of this call that aborted, in the order {@link #automaticFailure()} searches, nested
     * steps included; a {@link Reaction.Aborted} never counts. Its {@link AutomaticStep.Aborted#cause()} says why: a
     * domain failure that no route takes, a technical failure, any other exception, or an attempt past
     * maxAutomaticChain. When the attempt ran, its cause is its {@code CallFailedException}, whose
     * {@code statesWritten()} says how far it got: an attempt that aborted before writing left its entity where it
     * was, and one whose setter or hook threw may have moved it. Empty when no step aborted.
     */
    default Optional<AutomaticStep.Aborted> automaticAbort() {
        return AutomaticSearch.abort(this);
    }

    /**
     * The outcome of the first automatic attempt of this call that admitted none of its candidates, an
     * {@link Ignored} of {@link IgnoreReason#NO_CANDIDATE_ADMITTED} whose {@link Ignored#rejections()} say why, in the
     * order {@link #automaticFailure()} searches, nested attempts included. An attempt whose state had no automatic
     * edge left, {@link IgnoreReason#NO_AUTOMATIC_TRANSITION}, does not count. Empty when no attempt was blocked.
     */
    default Optional<Ignored<?, ?, ?>> automaticBlock() {
        return AutomaticSearch.block(this);
    }

    /**
     * The flow of the root call that returned this outcome, frozen once its hooks, its watch reactions and its
     * automatic steps ran: the
     * root call's entity and the values present then, each under the key that put it, in the order of
     * {@link FlowView#keys()}. They are what the call's steps and hooks put and no failure route dropped, so a failed
     * outcome holds the recovery action's writes and none of the failed action's. When the root machine records its
     * trail, {@link FlowView#trail()} lists the call's hops and sub-machine starts, each wrapping the very record or
     * start of {@link #records()} or {@link #starts()}, and a rejected outcome's trail ends with its rejection. The
     * view is a copy that the caller may keep and read from any thread, and it compares by value, so equal outcomes
     * need equal flows; its values are the very objects that were put, never copies. Reading never creates the flow;
     * a write does, a put or a remove that a step or a hook makes, and so does the start of a call whose root machine
     * records its trail. So the flow is {@link FlowView#empty()}, without an entity, for a root call that records no
     * trail and none of whose steps or hooks wrote to it, a rejected outcome included, since only its guards ran and
     * a guard cannot write; for an ignored outcome; and for any outcome a cascade returned, whose flow belongs to its
     * root. A watch reaction is a call of its own: the outcome in {@link Reaction.Done} hands out the reaction's own
     * flow, whose trail its own machine decides; and so is an automatic attempt, whose outcome in
     * {@link AutomaticStep.Done} hands out the attempt's own flow.
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
     * {@code starts} the sub-machines it started, {@code flow} the call's flow, as {@link Outcome#flow()} says, and
     * {@code automaticSteps} the automatic attempts that commit triggered, as {@link Outcome#automaticSteps()} says.
     * Returned by a cascade, it is written only if its root commits, the four lists are empty and the flow is
     * {@link FlowView#empty()}. The constructor without {@code starts} reports none, and the constructors without
     * {@code flow} give {@link FlowView#empty()} and report no automatic step.
     */
    record Applied<E, S extends Enum<S>, T extends Enum<T>>(
            @NonNull E entity, @NonNull S from, @NonNull T transition, @NonNull S to,
            @NonNull List<TransitionRecord<?, ?, ?>> records,
            @NonNull List<Reaction> reactions,
            @NonNull List<SubmachineStart<?, ?, ?>> starts,
            @NonNull FlowView flow,
            @NonNull List<AutomaticStep> automaticSteps) implements Outcome<E, S, T> {
        /** Copies the committed records, reactions, starts and automatic steps. */
        public Applied {
            records = List.copyOf(records);
            reactions = List.copyOf(reactions);
            starts = List.copyOf(starts);
            automaticSteps = List.copyOf(automaticSteps);
        }

        /** Builds an applied outcome whose flow is {@link FlowView#empty()} and that lists no automatic step. */
        public Applied(E entity, S from, T transition, S to, List<TransitionRecord<?, ?, ?>> records,
                       List<Reaction> reactions, List<SubmachineStart<?, ?, ?>> starts) {
            this(entity, from, transition, to, records, reactions, starts, FlowView.empty(), List.of());
        }

        /**
         * Builds an applied outcome that started no sub-machine, whose flow is {@link FlowView#empty()} and that lists
         * no automatic step.
         */
        public Applied(E entity, S from, T transition, S to, List<TransitionRecord<?, ?, ?>> records,
                       List<Reaction> reactions) {
            this(entity, from, transition, to, records, reactions, List.of(), FlowView.empty(), List.of());
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
     * A call that requests no identifier and ran no edge, for {@code reason}: a reevaluation with no watch on the
     * state, or none of whose watch rules held; an advance whose state has no automatic edge, or none of whose
     * automatic edges was admitted. Nothing was written and no action ran; its flow is {@link FlowView#empty()},
     * whether or not the call records its trail. {@code rejections} lists, for
     * {@link IgnoreReason#NO_CANDIDATE_ADMITTED} only, the rejection of each automatic edge the advance tried, in the
     * order it tried them, which {@link #explanation()} names. The constructor refuses an empty list with that reason
     * and a non-empty one with any other, and copies the list; the constructor without {@code rejections} lists none.
     */
    record Ignored<E, S extends Enum<S>, T extends Enum<T>>(
            @NonNull E entity, @NonNull S from, @NonNull IgnoreReason reason,
            @NonNull List<Eligibility.Ineligible<E, S, T>> rejections) implements Outcome<E, S, T> {
        /** Checks that the rejections match the reason, and copies them. */
        public Ignored {
            boolean admittedNone = reason == IgnoreReason.NO_CANDIDATE_ADMITTED;
            if (admittedNone && rejections.isEmpty()) {
                throw new IllegalArgumentException("a " + reason + " outcome lists the rejection of each candidate");
            }
            if (!admittedNone && !rejections.isEmpty()) {
                throw new IllegalArgumentException("a " + reason + " outcome lists no rejection");
            }
            rejections = List.copyOf(rejections);
        }

        /** Builds an ignored outcome that lists no rejection, as every reason but NO_CANDIDATE_ADMITTED does. */
        public Ignored(E entity, S from, IgnoreReason reason) {
            this(entity, from, reason, List.of());
        }

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
            // Not a switch: on another class's enum, javac compiles one to a synthetic class with a static
            // initializer, which assembling a registry cannot name to initialise, as
            // MachineRegistry.staticallyInitialized says.
            return reason == IgnoreReason.NO_WATCH ? OutcomeCode.NO_WATCH
                    : reason == IgnoreReason.NO_RULE_MATCHED ? OutcomeCode.NO_RULE_MATCHED
                    : reason == IgnoreReason.NO_AUTOMATIC_TRANSITION ? OutcomeCode.NO_AUTOMATIC_TRANSITION
                    : OutcomeCode.NO_CANDIDATE_ADMITTED;
        }

        /**
         * For {@link IgnoreReason#NO_CANDIDATE_ADMITTED},
         * {@code no automatic transition was admitted: } followed by one entry per rejection, in order, joined by
         * {@code "; "}: {@code <T> (<kind>[ <guard>]): <reason>}, the identifier and the kind by {@code name()}, as in
         * {@code COMPLETE_ADDRESS (GUARD addressComplete): address is incomplete}. Empty for any other reason.
         */
        @Override
        public Optional<String> explanation() {
            if (rejections.isEmpty()) {
                return Optional.empty();
            }
            StringJoiner entries = new StringJoiner("; ", "no automatic transition was admitted: ", "");
            for (Eligibility.Ineligible<E, S, T> rejection : rejections) {
                entries.add(rejection.transition().name() + " (" + rejection.kind().name()
                        + rejection.guard().map(" "::concat).orElse("") + "): " + rejection.reason());
            }
            return Optional.of(entries.toString());
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
            // Not a switch, as in Ignored.code().
            if (kind == RejectionKind.NO_TRANSITION) {
                return OutcomeCode.NO_TRANSITION;
            }
            if (kind == RejectionKind.GUARD) {
                return OutcomeCode.GUARD_REJECTED;
            }
            if (kind == RejectionKind.DETACHED) {
                return OutcomeCode.DETACHED;
            }
            return OutcomeCode.RESERVED_EXIT;
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
     * call, it committed, with {@code records}, {@code reactions}, {@code starts}, {@code flow} and
     * {@code automaticSteps} as in {@link Applied}; returned by a cascade, it is written only if its root commits, the
     * four lists are empty and the flow is {@link FlowView#empty()}. The constructor refuses a blank reason, and a
     * present failure kind that {@link ActionOutcome#failed(Class, String)} would refuse; the constructors without
     * {@code failureKind} report none, those without {@code starts} report none either, and those without
     * {@code flow} give {@link FlowView#empty()} and report no automatic step.
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
            @NonNull FlowView flow,
            @NonNull List<AutomaticStep> automaticSteps) implements Outcome<E, S, T> {
        /**
         * Validates the reason and failure kind, and copies the committed records, reactions, starts and automatic
         * steps.
         */
        public Failed {
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
            failureKind.ifPresent(FailureTypes::requireValidKind);
            records = List.copyOf(records);
            reactions = List.copyOf(reactions);
            starts = List.copyOf(starts);
            automaticSteps = List.copyOf(automaticSteps);
        }

        /** Builds a routed failure whose flow is {@link FlowView#empty()} and that lists no automatic step. */
        public Failed(E entity, S from, T transition, S to, T failureTransition, S movedTo, String reason,
                      Optional<Throwable> cause, List<TransitionRecord<?, ?, ?>> records, List<Reaction> reactions,
                      Optional<Class<? extends FailureKind>> failureKind, List<SubmachineStart<?, ?, ?>> starts) {
            this(entity, from, transition, to, failureTransition, movedTo, reason, cause, records, reactions,
                    failureKind, starts, FlowView.empty(), List.of());
        }

        /**
         * Builds a routed failure that started no sub-machine, whose flow is {@link FlowView#empty()} and that lists
         * no automatic step.
         */
        public Failed(E entity, S from, T transition, S to, T failureTransition, S movedTo, String reason,
                      Optional<Throwable> cause, List<TransitionRecord<?, ?, ?>> records, List<Reaction> reactions,
                      Optional<Class<? extends FailureKind>> failureKind) {
            this(entity, from, transition, to, failureTransition, movedTo, reason, cause, records, reactions,
                    failureKind, List.of(), FlowView.empty(), List.of());
        }

        /**
         * Builds a routed failure without a failure kind that started no sub-machine, whose flow is
         * {@link FlowView#empty()} and that lists no automatic step.
         */
        public Failed(E entity, S from, T transition, S to, T failureTransition, S movedTo, String reason,
                      Optional<Throwable> cause, List<TransitionRecord<?, ?, ?>> records, List<Reaction> reactions) {
            this(entity, from, transition, to, failureTransition, movedTo, reason, cause, records, reactions,
                    Optional.empty(), List.of(), FlowView.empty(), List.of());
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
