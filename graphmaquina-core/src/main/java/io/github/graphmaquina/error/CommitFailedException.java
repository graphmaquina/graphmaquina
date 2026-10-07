package io.github.graphmaquina.error;

import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.StatesWritten;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase 2 of a call failed, so its phase is COMMIT. Before its first write, phase 2 checks that every entity is still
 * in the state the call started from. One that is not was written while the call ran, by an action that called the
 * setter or by a concurrent writer of the same entity, typically another transition, and nothing is written:
 * {@link #statesWritten()} is NONE and every entity is {@link #pending()}, as when reading the states or grouping the
 * entities before the first setter throws, which this exception then carries as its cause. Afterwards, a setter that
 * throws an exception, checked ones included, stops phase 2 where it stood: {@link #statesWritten()} is PARTIAL,
 * {@link #applied()} lists the entities whose setter returned, {@link #failedEntity()} the one whose setter threw,
 * which may or may not have been written, and {@link #pending()} the ones never tried. A setter's Error propagates
 * unchanged instead of this exception. The journal still lists the actions that ran: their external effects happened
 * although the states did not move, or moved only in part.
 */
public final class CommitFailedException extends CallFailedException {

    private final List<ActionRecord> journal;
    private final List<Object> applied;
    private final Optional<Object> failedEntity;
    private final List<Object> pending;

    /** Phase 2 stopped before calling any setter, for the reason {@code message} gives. */
    public CommitFailedException(MachineCall call, List<ActionRecord> journal, List<?> pending, String message) {
        this(call, journal, List.of(), Optional.empty(), pending, message, Optional.empty());
    }

    /** The setter of {@code failedEntity} threw {@code cause}, after those of {@code applied} returned. */
    public CommitFailedException(MachineCall call, List<ActionRecord> journal, List<?> applied, Object failedEntity,
                                 List<?> pending, Throwable cause) {
        this(call, journal, applied, Optional.of(Objects.requireNonNull(failedEntity, "failedEntity")), pending,
                "a setter threw " + Objects.requireNonNull(cause, "cause"), Optional.of(cause));
    }

    /** Phase 2 stopped before calling any setter because {@code cause} was thrown, by a state read for instance. */
    public CommitFailedException(MachineCall call, List<ActionRecord> journal, List<?> pending, Throwable cause) {
        this(call, journal, List.of(), Optional.empty(), pending,
                "preparing the commit threw " + Objects.requireNonNull(cause, "cause"), Optional.of(cause));
    }

    private CommitFailedException(MachineCall call, List<ActionRecord> journal, List<?> applied,
                                  Optional<Object> failedEntity, List<?> pending, String message,
                                  Optional<Throwable> cause) {
        super(call, describe(applied, failedEntity, pending, message));
        this.journal = List.copyOf(journal);
        this.applied = List.copyOf(applied);
        this.failedEntity = failedEntity;
        this.pending = List.copyOf(pending);
        // StateMachineException(message, cause) refuses a null cause, so the cause, when there is one, is set here.
        if (cause.isPresent()) {
            initCause(cause.get());
        }
    }

    /** The entities whose setter returned, in commit order. */
    public List<Object> applied() {
        return applied;
    }

    /** The entity whose setter threw, if one did; it may or may not have been written. */
    public Optional<Object> failedEntity() {
        return failedEntity;
    }

    /** The entities whose setter was never called, in commit order. */
    public List<Object> pending() {
        return pending;
    }

    /** COMMIT. */
    @Override
    public CallPhase phase() {
        return CallPhase.COMMIT;
    }

    /** PARTIAL when a setter threw, NONE when phase 2 stopped before its first setter. */
    @Override
    public StatesWritten statesWritten() {
        return failedEntity.isPresent() ? StatesWritten.PARTIAL : StatesWritten.NONE;
    }

    @Override
    public List<ActionRecord> journal() {
        return journal;
    }

    private static String describe(List<?> applied, Optional<Object> failedEntity, List<?> pending, String message) {
        Objects.requireNonNull(applied, "applied");
        Objects.requireNonNull(pending, "pending");
        Objects.requireNonNull(message, "message");
        return "phase 2 failed: " + message + "; applied " + applied.size()
                + (failedEntity.isPresent() ? ", failed 1" : "") + ", pending " + pending.size();
    }
}
