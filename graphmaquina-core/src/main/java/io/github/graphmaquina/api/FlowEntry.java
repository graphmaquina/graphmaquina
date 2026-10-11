package io.github.graphmaquina.api;

import java.time.Instant;
import java.util.Optional;
import lombok.NonNull;

/**
 * One entry of the trail of a call, {@link FlowView#trail()}, which a call records when its root machine was built
 * with {@code recordTrail}. The trail lists what the call took, in order: a {@link Hop} for each hop and a
 * {@link Start} for each sub-machine start, those of cascaded steps and of the exits of sub-machines included, then,
 * when the call ends in a rejection or in a failure, a final {@link Rejected} or {@link Failed}. A hop or a start that
 * a rollback dropped, the failed step of a failure route or a {@code COLLECT} element that aborted, leaves the trail
 * with it. {@link #at()} is read on the clock the root machine was built with: for a hop or a start, right after it
 * was logged, before its action runs; for the final entry, when the call ended. Each entry is a value, compared by
 * its components.
 */
public sealed interface FlowEntry permits FlowEntry.Hop, FlowEntry.Start, FlowEntry.Rejected, FlowEntry.Failed {

    /** When the entry was taken, on the clock of the root machine's trail. */
    Instant at();

    /**
     * A hop the call took. {@code hop} is the record of the hop: in the trail of an outcome, the very instance the
     * outcome lists in {@link Outcome#records()}. {@code recovery} is present on the hop of a failure route, and says
     * what failed.
     *
     * @param hop the record of the hop
     * @param recovery what failed, on the hop a failure route took; empty on any other hop
     * @param at when the hop was logged, before its action ran
     */
    record Hop(@NonNull TransitionRecord<?, ?, ?> hop, @NonNull Optional<Recovery> recovery, @NonNull Instant at)
            implements FlowEntry { }

    /**
     * What a failure route recovered from, on the hop of its route: the transition whose step failed, and the failure
     * as the outcome reports it. The trail has no entry for the failed hop itself, which the route dropped.
     *
     * @param failed the identifier of the transition whose step failed
     * @param reason the reason of the failure, as {@link Outcome.Failed#reason()} reports it: the one the action
     *     returned, or the one composed from a routed throwable
     * @param cause the throwable the route took, if a throwable failed the step
     * @param kind the failure kind, as {@link Outcome.Failed#failureKind()} reports it: the one a returned failure
     *     named, or the one of the recovery rule whose type routed the throwable; empty otherwise
     */
    record Recovery(@NonNull Enum<?> failed, @NonNull String reason, @NonNull Optional<Throwable> cause,
                    @NonNull Optional<Class<? extends FailureKind>> kind) { }

    /**
     * A sub-machine the call started. {@code start} is the record of the start: in the trail of an outcome, the very
     * instance the outcome lists in {@link Outcome#starts()}.
     *
     * @param start the record of the start
     * @param at when the start was logged, right after the hop that entered its node
     */
    record Start(@NonNull SubmachineStart<?, ?, ?> start, @NonNull Instant at) implements FlowEntry { }

    /**
     * The rejection the caller received, the last entry of a call that returned {@link Outcome.Rejected}: the root
     * call's machine and entity, and the rejection as the outcome reports it. A rejection inside a cascade or an exit
     * aborts the call, which then ends with {@link Failed}.
     *
     * @param key the machine of the root call
     * @param entity the entity of the root call
     * @param from the state the request was refused in
     * @param transition the identifier that was requested
     * @param kind why the request was refused
     * @param reason the reason of the rejection
     * @param guard the guard that rejected, for a {@link RejectionKind#GUARD} rejection
     * @param at when the call returned the rejection
     */
    record Rejected(@NonNull MachineKey<?, ?, ?> key, @NonNull Object entity, @NonNull Enum<?> from,
                    @NonNull Enum<?> transition, @NonNull RejectionKind kind, @NonNull String reason,
                    @NonNull Optional<String> guard, @NonNull Instant at) implements FlowEntry { }

    /**
     * The failure that ended the call, the last entry of a call that threw a {@code CallFailedException}:
     * {@code failure} is equal to the one the observers receive, and its {@link CallFailure#statesWritten()} says
     * what the call wrote. Its {@code toString()} prints the failure's exception as its {@code toString()} does, never
     * that exception's flow. An {@code Error} ends a call without an entry.
     *
     * @param failure how the call failed: its phase, how far it got in writing states, and its exception
     * @param at when the call failed
     */
    record Failed(@NonNull CallFailure failure, @NonNull Instant at) implements FlowEntry { }
}
