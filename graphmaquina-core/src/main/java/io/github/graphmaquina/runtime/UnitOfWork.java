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
 * journal answers which actions actually ran and is never reverted. Both are created on the first append,
 * so a root that rejects or finds no transition allocates neither.
 */
final class UnitOfWork {

    private final List<LogEntry> log = new ArrayList<>();
    /** Created by the first append: a root that never transitions must not pay for the index. */
    private Map<EntityRef, Enum<?>> pendingStates;
    private List<PendingUndo> undo;
    private final List<ActionRecord> journal = new ArrayList<>();
    private final Deque<InFlight> inFlight = new ArrayDeque<>();
    private final Map<EntityRef, EntityRef> known = new HashMap<>();
    private final int maxDepth;
    private final long deadlineNanos;
    private Optional<StateMachineException> poison = Optional.empty();

    UnitOfWork(int maxDepth, long deadlineNanos) {
        this.maxDepth = maxDepth;
        this.deadlineNanos = deadlineNanos;
    }

    int maxDepth() { return maxDepth; }

    long deadlineNanos() { return deadlineNanos; }

    /** Registers the identity and returns the canonical reference. A second object with the same identity is an error. */
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
     * to of the last log entry for the entity, if any. An empty index has no mapping for any reference, so the
     * early return is the same answer without handing ref to the map: the reference stays local to the caller
     * and the JIT can drop it, which is what a watch condition reading N peers before the first hop pays for.
     */
    Optional<Enum<?>> pendingState(EntityRef ref) {
        if (pendingStates == null || pendingStates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(pendingStates.get(ref));
    }

    int savepoint() { return log.size(); }

    /** Undoes the index hop by hop. The first call rejects the savepoint exactly as subList did, before mutating. */
    void rollbackTo(int savepoint) {
        log.subList(savepoint, log.size());
        while (log.size() > savepoint) {
            PendingUndo old = undo.remove(undo.size() - 1);
            if (old.present()) {
                pendingStates.put(old.ref(), old.previous());
            } else {
                pendingStates.remove(old.ref());
            }
            log.remove(log.size() - 1);
        }
    }

    void append(LogEntry entry) {
        if (pendingStates == null) {
            pendingStates = new HashMap<>();
            undo = new ArrayList<>();
        }
        Enum<?> previous = pendingStates.get(entry.ref());
        log.add(entry);
        undo.add(new PendingUndo(entry.ref(), previous != null, previous));
        pendingStates.put(entry.ref(), entry.to());
    }

    List<LogEntry> entries() { return List.copyOf(log); }

    int appendJournal(ActionRecord record) {
        journal.add(record);
        return journal.size() - 1;
    }

    void journalStatus(int index, ActionStatus status) {
        ActionRecord old = journal.get(index);
        journal.set(index, new ActionRecord(old.key(), old.entity(), old.from(), old.transition(), old.to(), old.action(),
                old.depth(), status));
    }

    List<ActionRecord> journal() { return List.copyOf(journal); }

    /**
     * A nested abort condemns every action in flight: the poison keeps the parent from finishing as if nothing happened.
     * Called when building the details of an abort, so that the journal traveling with the exception is the final one.
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

    /** Only cascadeAll with COLLECT can clear it, after reverting the failed element. */
    void clearPoison() { poison = Optional.empty(); }

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

    List<TransitionRecord<?, ?, ?>> records() {
        List<TransitionRecord<?, ?, ?>> records = new ArrayList<>();
        for (LogEntry entry : log) {
            records.add(Typed.record(entry.ref().key(), entry));
        }
        return List.copyOf(records);
    }

    /**
     * What one log entry did to the index, kept one per entry: the entity it moved, whether that entity already
     * had a pending state, and the value to put back when it had one. Absence and an earlier pending state are
     * different answers, and the origin of the entry proves neither.
     */
    private record PendingUndo(EntityRef ref, boolean present, Enum<?> previous) { }
}
