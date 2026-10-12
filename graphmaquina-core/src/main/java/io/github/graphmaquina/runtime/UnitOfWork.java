package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.FlowEntry;
import io.github.graphmaquina.api.SubmachineStart;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.error.AmbiguousIdentityException;
import io.github.graphmaquina.error.StateMachineException;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * State of a root transaction. Local to the thread running the transition; no other thread touches it.
 * log holds the hops and the sub-machine starts of the transaction, in order; it answers what state each entity ends
 * up in, whichever kind of entry moved it, and is reverted by savepoint; pending, the pending index, answers that by
 * entity with its states and puts it back entry by entry with its undo, so a rollback never rebuilds it. pending is
 * built from the log by the first lookup that finds it non-empty, and append maintains it from then on, so a root
 * that never reads a pending state after its first append allocates none of it. journal answers which actions
 * actually ran and is never reverted. flow is the call's flow, created at the start of the call when its root machine
 * records its trail, and otherwise by the first write, a put or a remove, that a step or a hook of the call makes; a
 * read never creates it. A call that writes nothing and records no trail
 * pays two empty fields, and every rollback reverts the flow's writes with the log. No rollback follows the commit,
 * so nothing reverts what a hook writes. A call that records its trail marks each entry of the log on its clock,
 * right after the entry is in the log, and every rollback truncates the marks with the log; a call that does not
 * reads no clock and keeps no mark.
 */
final class UnitOfWork {

    private final List<LogEntry> log = new ArrayList<>();
    /**
     * Created from the log by the first lookup that finds it non-empty: a root that appends and never reads a
     * pending state back must not pay for the index.
     */
    private Optional<PendingIndex> pending = Optional.empty();
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
    /** Whether a start was ever appended: until one is, every entry of the log is a hop. */
    private boolean started;
    /**
     * Whether a rollback is undoing the pending index, whose identities' equals and hashCode it calls: the transaction
     * refuses a cascade meanwhile.
     */
    private boolean rollingBack;
    /**
     * The call's flow; empty until a step or a hook of the call first writes to it, unless the call records its trail.
     */
    private Optional<CallFlow> flow = Optional.empty();
    /** The clock of the call's trail; empty when the root machine does not record its trail. */
    private Optional<InstantSource> clock = Optional.empty();

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
     * The call's flow, created with {@code rootEntity}, the root call's entity, the first time it is asked for. Only a
     * write and {@link #recordTrail} ask for it this way; a read uses {@link #flow()}, which never creates it.
     */
    CallFlow flow(Object rootEntity) {
        if (flow.isEmpty()) {
            flow = Optional.of(new CallFlow(rootEntity));
        }
        return flow.get();
    }

    /**
     * The call's flow, if the call records its trail or a write of the call created it; empty otherwise. It never
     * creates the flow.
     */
    Optional<CallFlow> flow() { return flow; }

    /** Whether the call has its flow: it records its trail, or a write of a step or a hook of the call created it. */
    boolean hasFlow() { return flow.isPresent(); }

    /**
     * Records the trail of the call on {@code clock}: creates the call's flow now, with {@code rootEntity}, the root
     * call's entity, and marks every entry appended from now on. The root calls it once, before its step runs.
     */
    void recordTrail(Object rootEntity, InstantSource clock) {
        this.clock = Optional.of(clock);
        flow(rootEntity);
    }

    /** Whether the call records its trail. */
    boolean recordsTrail() { return clock.isPresent(); }

    /** The clock of the call's trail; only for a call that records its trail. */
    InstantSource clock() { return clock.orElseThrow(); }

    /**
     * Undoes the index hop by hop. Before the index exists, savepoint zero clears the whole log directly; zero is
     * valid even for an empty log. Every other path first uses subList to reject invalid savepoints before mutating.
     * Before the index exists there is nothing to undo but the log: the discarded entries are cleared and the first
     * lookup builds the index from what remains. Each hop restores the map before its undo record and
     * its log entry are removed, so an identity whose equals or hashCode throws stops the rollback with the three
     * still in step, and the same rollback can be retried. Each branch then drops the flow's writes made after the
     * savepoint and the marks of the entries it dropped, if the call has a flow; a rollback that an identity stops
     * drops those of the entries it undid, so the flow stays in step with the log it left. While the index is undone,
     * {@link #rollingBack()} answers true: a cascade that an identity's equals or hashCode attempted through a retained
     * context would add an entry in the middle of the undo, which would then drop it in place of the one it undid.
     */
    void rollbackTo(int savepoint) {
        if (savepoint == 0 && pending.isEmpty()) {
            log.clear();
            rollbackFlow(savepoint);
            return;
        }
        List<LogEntry> discarded = log.subList(savepoint, log.size());
        if (pending.isEmpty()) {
            discarded.clear();
            rollbackFlow(savepoint);
            return;
        }
        Map<EntityRef, Enum<?>> states = pending.get().states();
        List<PendingUndo> undo = pending.get().undo();
        rollingBack = true;
        try {
            while (log.size() > savepoint) {
                PendingUndo old = undo.get(undo.size() - 1);
                if (old.present()) {
                    states.put(old.ref(), old.previous());
                } else {
                    states.remove(old.ref());
                }
                undo.remove(undo.size() - 1);
                log.remove(log.size() - 1);
            }
        } finally {
            rollingBack = false;
            rollbackFlow(log.size());
        }
    }

    /** Whether a rollback is undoing the pending index, as {@link #rollbackTo} says. */
    boolean rollingBack() { return rollingBack; }

    private void rollbackFlow(int savepoint) {
        if (flow.isPresent()) {
            flow.get().rollbackTo(savepoint);
            flow.get().truncateMarks(savepoint);
        }
    }

    /**
     * When the index exists it is updated before the log, so an identity whose equals or hashCode throws leaves the
     * log, the index and undo as they were. A call that records its trail then marks the entry, as {@link #mark()}
     * says.
     */
    void append(LogEntry entry) {
        add(entry);
        mark();
    }

    private void add(LogEntry entry) {
        if (pending.isPresent()) {
            index(pending.get().states(), pending.get().undo(), entry);
        }
        log.add(entry);
    }

    /**
     * Marks the entry just appended, when the call records its trail: the clock is read now, once the entry is in the
     * log, never before, so a mark never sits at the savepoint its own step took. A clock that answers null is refused
     * as {@link CallFlow#now} says, and the entry stays without a mark. A call that does not record its trail reads no
     * clock.
     */
    private void mark() {
        if (clock.isPresent()) {
            flow.orElseThrow().mark(log.size() - 1, CallFlow.now(clock.get()));
        }
    }

    /**
     * The first lookup that finds a non-empty log builds the index from it; append maintains it afterwards. The
     * replay fills local collections and publishes them only once it completes, so an identity whose equals or
     * hashCode throws midway leaves no partial index behind and the next lookup starts again from the log. The
     * replay is an indexed loop because a for-each allocates an iterator the JIT did not remove in the cascade
     * benchmark.
     */
    private Map<EntityRef, Enum<?>> index() {
        if (pending.isEmpty()) {
            Map<EntityRef, Enum<?>> states = new HashMap<>();
            List<PendingUndo> undoEntries = new ArrayList<>();
            for (int i = 0; i < log.size(); i++) {
                index(states, undoEntries, log.get(i));
            }
            pending = Optional.of(new PendingIndex(states, undoEntries));
        }
        return pending.get().states();
    }

    /**
     * Indexes one entry. put returns the earlier pending state, as get would, and runs the identity's equals and
     * hashCode before it changes the map; undo grows last. A throw therefore leaves both collections as they were.
     */
    private static void index(Map<EntityRef, Enum<?>> states, List<PendingUndo> undo, LogEntry entry) {
        Enum<?> previous = states.put(entry.ref(), entry.to());
        undo.add(new PendingUndo(entry.ref(), previous != null, previous));
    }

    /**
     * Appends the start of a sub-machine as {@link #append} appends a hop, and notes that the log holds starts before
     * it marks the start.
     */
    void appendStart(LogEntry.Start start) {
        add(start);
        started = true;
        mark();
    }

    List<LogEntry> entries() { return List.copyOf(log); }

    /**
     * The last start in the log whose parent is {@code parent}: when the parent's latest hop entered a bound state,
     * the start of the sub-machine that hop started.
     */
    Optional<LogEntry.Start> lastStartUnder(EntityRef parent) {
        return lastStartUnderSince(parent, 0);
    }

    /**
     * The last start whose parent is {@code parent} among the entries logged at {@code position} or later, a size the
     * log had earlier: a start that an entry of the parent made since then. Only those entries are read.
     */
    Optional<LogEntry.Start> lastStartUnderSince(EntityRef parent, int position) {
        for (int index = log.size() - 1; index >= position; index--) {
            if (log.get(index) instanceof LogEntry.Start start && start.parent().equals(parent)) {
                return Optional.of(start);
            }
        }
        return Optional.empty();
    }

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

    /** The machine of each entity in flight, outermost first: a path that prints no identity. */
    List<String> machinePath() {
        List<String> path = new ArrayList<>();
        for (InFlight flight : inFlight) {
            path.add(flight.ref().key().toString());
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

    /**
     * The log grouped into one change per entity, read straight off the log without copying its entries. A log of one
     * entry skips the grouping map when no action ran. The transaction refuses cascades once the commit begins,
     * including one attempted from an identity's hashCode while this log is grouped.
     */
    CommitSummary commitSummary() {
        if (journal.isEmpty() && log.size() == 1) {
            return CommitSummary.single(log.get(0));
        }
        return CommitSummary.from(log);
    }

    /** Chains per entity, in order of first appearance. Kept as the control the summary is compared against. */
    Map<EntityRef, List<LogEntry>> chains() {
        Map<EntityRef, List<LogEntry>> chains = new LinkedHashMap<>();
        for (LogEntry entry : log) {
            chains.computeIfAbsent(entry.ref(), (EntityRef ref) -> new ArrayList<>()).add(entry);
        }
        return chains;
    }

    /**
     * An immutable snapshot of the hops of the log in entry order, with newly created records that retain the
     * original entity references; the starts are left out. Later appends and rollbacks do not change an existing
     * snapshot. A log that never held a start is read entry by entry, every entry being a hop.
     */
    List<TransitionRecord<?, ?, ?>> records() {
        if (started) {
            return hopRecords();
        }
        TransitionRecord<?, ?, ?>[] records = new TransitionRecord<?, ?, ?>[log.size()];
        for (int index = 0; index < records.length; index++) {
            LogEntry.Hop hop = (LogEntry.Hop) log.get(index);
            records[index] = Typed.record(hop.ref().key(), hop);
        }
        return List.of(records);
    }

    /** {@link #records()} of a log that held a start: the hops alone. */
    private List<TransitionRecord<?, ?, ?>> hopRecords() {
        List<TransitionRecord<?, ?, ?>> records = new ArrayList<>();
        for (int index = 0; index < log.size(); index++) {
            if (log.get(index) instanceof LogEntry.Hop hop) {
                records.add(Typed.record(hop.ref().key(), hop));
            }
        }
        return List.copyOf(records);
    }

    /**
     * An immutable snapshot of the starts of the log in entry order, each typed by its child's key. A log that never
     * held a start answers the shared empty list and allocates nothing.
     */
    List<SubmachineStart<?, ?, ?>> starts() {
        return started ? loggedStarts() : List.of();
    }

    /** {@link #starts()} of a log that held a start. */
    private List<SubmachineStart<?, ?, ?>> loggedStarts() {
        List<SubmachineStart<?, ?, ?>> starts = new ArrayList<>();
        for (int index = 0; index < log.size(); index++) {
            if (log.get(index) instanceof LogEntry.Start start) {
                starts.add(Typed.start(start.ref().key(), start));
            }
        }
        return List.copyOf(starts);
    }

    /**
     * The trail of the call so far, unmodifiable: an entry per entry of the log, in order, as {@link #trailOf} builds
     * it from new records. The empty list when the call does not record its trail.
     */
    List<FlowEntry> trail() {
        if (clock.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(trailOf(records(), starts()));
    }

    /**
     * The entries of the trail of a call that records it, in log order, in a new list the caller may extend: a hop
     * entry wraps the next of {@code records}, a start entry the next of {@code starts}, which are the
     * {@link #records()} and {@link #starts()} of the log as it stands; each is at the instant of its entry's mark, and
     * a hop carries the recovery its mark holds. The entries stop at the first entry of the log without a mark: only
     * the last entry can lack one, when the clock threw or answered null instead of marking it, and the call aborts
     * with that exception as its cause.
     */
    List<FlowEntry> trailOf(List<TransitionRecord<?, ?, ?>> records, List<SubmachineStart<?, ?, ?>> starts) {
        CallFlow marks = flow.orElseThrow();
        List<FlowEntry> entries = new ArrayList<>(log.size() + 1);
        int hop = 0;
        int start = 0;
        for (int index = 0; index < log.size(); index++) {
            Optional<Instant> at = marks.markAt(index);
            if (at.isEmpty()) {
                break;
            }
            if (log.get(index) instanceof LogEntry.Hop) {
                entries.add(new FlowEntry.Hop(records.get(hop++), marks.recoveryAt(index), at.get()));
            } else {
                entries.add(new FlowEntry.Start(starts.get(start++), at.get()));
            }
        }
        return entries;
    }

    /**
     * What one log entry did to the index, kept one per entry: the entity it moved, whether that entity already
     * had a pending state, and the value to put back when it had one. Absence and an earlier pending state are
     * different answers, and the origin of the entry proves neither.
     */
    private record PendingUndo(EntityRef ref, boolean present, Enum<?> previous) { }

    /**
     * The pending index: {@code states}, the pending state of each entity the log moved, and {@code undo}, one
     * {@link PendingUndo} per entry of the log, in order. Both are built together and kept in step.
     */
    private record PendingIndex(Map<EntityRef, Enum<?>> states, List<PendingUndo> undo) { }
}
