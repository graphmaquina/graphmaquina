package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.error.AmbiguousIdentityException;
import io.github.graphmaquina.error.StateMachineException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * State of a root transaction. Local to the thread running the transition; no other thread touches it.
 * log answers what state each entity ends up in and is reverted by savepoint; pendingStates indexes that
 * answer by entity and undo puts it back entry by entry, so a rollback never rebuilds the index.
 * pendingStates and undo are built from the log by the first lookup that finds it non-empty, and append
 * maintains them from then on, so a root that never reads a pending state after its first append allocates
 * neither. journal answers which actions actually ran and is never reverted.
 */
final class UnitOfWork {

    private final List<LogEntry> log = new ArrayList<>();
    /**
     * Created from the log by the first lookup that finds it non-empty: a root that appends and never reads a
     * pending state back must not pay for the index.
     */
    private Map<EntityRef, Enum<?>> pendingStates;
    private List<PendingUndo> undo;
    private final List<ActionRecord> journal = new ArrayList<>();
    // Sized for a root and two nested cascades: a default-sized deque costs every call 56 more bytes.
    private final Deque<InFlight> inFlight = new ArrayDeque<>(3);
    private final Map<EntityRef, EntityRef> known = new HashMap<>();
    private final int maxDepth;
    private final long deadlineNanos;
    private Optional<StateMachineException> poison = Optional.empty();

    /**
     * Whether the poison is an abort that wraps what is not a failure of this call, such as a peer's null state, an
     * unknown machine or an Error. COLLECT never collects such an abort: it stops at once.
     */
    private boolean poisonWrapsAForeignFailure;

    UnitOfWork(int maxDepth, long deadlineNanos) {
        this.maxDepth = maxDepth;
        this.deadlineNanos = deadlineNanos;
    }

    int maxDepth() { return maxDepth; }

    long deadlineNanos() { return deadlineNanos; }

    /**
     * Registers the identity and returns the canonical reference. A second object with the same identity is an
     * error.
     */
    EntityRef register(EntityRef ref) {
        EntityRef existing = known.putIfAbsent(ref, ref);
        if (existing == null) {
            return ref;
        }
        if (existing.instance() != ref.instance()) {
            throw new AmbiguousIdentityException(ref.key(), ref.id(), existing.instance(), ref.instance());
        }
        return existing;
    }

    /**
     * to of the last log entry for the entity, if any. An empty log has no entry for any reference, so the early
     * return is the same answer without building the index or handing ref to the map: the reference stays local
     * to the caller and the JIT can drop it. The calls that find the log empty are the first state read of every
     * root, made by Transaction.currentState before anything is appended, and the reads of a guard that looks at
     * peers through DefaultPeerView before that first append. The first call that finds the log non-empty builds
     * the index.
     */
    Optional<Enum<?>> pendingState(EntityRef ref) {
        if (log.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(index().get(ref));
    }

    int savepoint() { return log.size(); }

    /**
     * Undoes the index hop by hop. The subList call rejects the savepoint exactly as the old rollback did, before
     * mutating. Before the index exists there is nothing to undo but the log: the discarded entries are cleared
     * and the first lookup builds the index from what remains. Each hop restores the map before its undo record and
     * its log entry are removed, so an identity whose equals or hashCode throws stops the rollback with the three
     * still in step, and the same rollback can be retried.
     */
    void rollbackTo(int savepoint) {
        List<LogEntry> discarded = log.subList(savepoint, log.size());
        if (pendingStates == null) {
            discarded.clear();
            return;
        }
        while (log.size() > savepoint) {
            PendingUndo old = undo.get(undo.size() - 1);
            if (old.present()) {
                pendingStates.put(old.ref(), old.previous());
            } else {
                pendingStates.remove(old.ref());
            }
            undo.remove(undo.size() - 1);
            log.remove(log.size() - 1);
        }
    }

    /**
     * When the index exists it is updated before the log, so an identity whose equals or hashCode throws leaves the
     * log, the index and undo as they were.
     */
    void append(LogEntry entry) {
        if (pendingStates != null) {
            index(pendingStates, undo, entry);
        }
        log.add(entry);
    }

    /**
     * The first lookup that finds a non-empty log builds the index from it; append maintains it afterwards. The
     * replay fills local collections and publishes them only once it completes, so an identity whose equals or
     * hashCode throws midway leaves no partial index behind and the next lookup starts again from the log. The
     * replay is an indexed loop because a for-each allocates an iterator the JIT did not remove in the cascade
     * benchmark.
     */
    private Map<EntityRef, Enum<?>> index() {
        if (pendingStates == null) {
            Map<EntityRef, Enum<?>> states = new HashMap<>();
            List<PendingUndo> undoEntries = new ArrayList<>();
            for (int i = 0; i < log.size(); i++) {
                index(states, undoEntries, log.get(i));
            }
            pendingStates = states;
            undo = undoEntries;
        }
        return pendingStates;
    }

    /**
     * Indexes one entry. put returns the earlier pending state, as get would, and runs the identity's equals and
     * hashCode before it changes the map; undo grows last. A throw therefore leaves both collections as they were.
     */
    private static void index(Map<EntityRef, Enum<?>> states, List<PendingUndo> undo, LogEntry entry) {
        Enum<?> previous = states.put(entry.ref(), entry.to());
        undo.add(new PendingUndo(entry.ref(), previous != null, previous));
    }

    List<LogEntry> entries() { return List.copyOf(log); }

    int appendJournal(ActionRecord record) {
        journal.add(record);
        return journal.size() - 1;
    }

    void journalStatus(int index, ActionStatus status) {
        ActionRecord old = journal.get(index);
        journal.set(index, new ActionRecord(old.key(), old.entity(), old.from(), old.transition(), old.to(),
                old.action(), old.depth(), status));
    }

    List<ActionRecord> journal() { return List.copyOf(journal); }

    /**
     * A nested abort condemns every action in flight: the poison keeps the parent from finishing as if nothing
     * happened. Called when building the details of an abort, so that the journal traveling with the exception is
     * the final one.
     */
    void markRunningAsThrew() {
        for (int i = 0; i < journal.size(); i++) {
            if (journal.get(i).status() == ActionStatus.RUNNING) {
                journalStatus(i, ActionStatus.THREW);
            }
        }
    }

    void push(EntityRef ref, TransitionSpec<?, ?, ?> spec) { inFlight.addLast(new InFlight(ref, spec)); }

    void pop() { inFlight.removeLast(); }

    Optional<InFlight> inFlight(EntityRef ref) {
        for (InFlight flight : inFlight) {
            if (flight.ref().equals(ref)) {
                return Optional.of(flight);
            }
        }
        return Optional.empty();
    }

    List<String> path() {
        List<String> path = new ArrayList<>();
        for (InFlight flight : inFlight) {
            path.add(flight.ref().toString());
        }
        return List.copyOf(path);
    }

    boolean isPoisoned() { return poison.isPresent(); }

    Optional<StateMachineException> poison() { return poison; }

    void poison(StateMachineException error) {
        if (poison.isEmpty()) {
            poison = Optional.of(error);
        }
    }

    /** Poisons with an abort that wraps what is not a failure of this call, if nothing poisoned yet. */
    void poisonWrapping(StateMachineException abort) {
        if (poison.isEmpty()) {
            poison = Optional.of(abort);
            poisonWrapsAForeignFailure = true;
        }
    }

    boolean poisonWrapsAForeignFailure() { return poisonWrapsAForeignFailure; }

    /** Only cascadeAll with COLLECT can clear it, after reverting the failed element. */
    void clearPoison() {
        poison = Optional.empty();
        poisonWrapsAForeignFailure = false;
    }

    /** The log grouped into one change per entity, read straight off the log without copying its entries. */
    CommitSummary commitSummary() { return CommitSummary.from(log); }

    /** Chains per entity, in order of first appearance. Kept as the control the summary is compared against. */
    Map<EntityRef, List<LogEntry>> chains() {
        Map<EntityRef, List<LogEntry>> chains = new LinkedHashMap<>();
        for (LogEntry entry : log) {
            chains.computeIfAbsent(entry.ref(), (EntityRef ref) -> new ArrayList<>()).add(entry);
        }
        return chains;
    }

    /**
     * A fresh immutable snapshot of the log in entry order, with newly created records that retain the
     * original entity references. Later appends and rollbacks do not change an existing snapshot.
     */
    List<TransitionRecord<?, ?, ?>> records() {
        TransitionRecord<?, ?, ?>[] records = new TransitionRecord<?, ?, ?>[log.size()];
        for (int index = 0; index < records.length; index++) {
            LogEntry entry = log.get(index);
            records[index] = Typed.record(entry.ref().key(), entry);
        }
        return List.of(records);
    }

    /**
     * What one log entry did to the index, kept one per entry: the entity it moved, whether that entity already
     * had a pending state, and the value to put back when it had one. Absence and an earlier pending state are
     * different answers, and the origin of the entry proves neither.
     */
    private record PendingUndo(EntityRef ref, boolean present, Enum<?> previous) { }
}
