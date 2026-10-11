package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.FlowEntry;
import io.github.graphmaquina.api.FlowKey;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The flow of one root call, owned by its {@link UnitOfWork}: the root entity of the call and the writes its steps
 * and its hooks made, in order. Local to the thread running the call, like the unit of work, so it has no
 * synchronization.
 * <ul>
 *   <li>Each put or remove is a write that keeps the size the log had when it was made, its {@code atLog}. A read
 *       answers the last write of the key, so a remove is a write without a value.</li>
 *   <li>{@link #rollbackTo} drops the writes made strictly after a savepoint. The savepoint of a step is the size of
 *       the log before its hop is appended, and its action writes once the hop is in, at {@code atLog} of at least
 *       the savepoint plus one; what an earlier step wrote has {@code atLog} of at most the savepoint. A COLLECT
 *       element's savepoint can equal the position of its parent action's write, which must stay.</li>
 *   <li>{@code atLog} never decreases along the list: a rollback leaves only writes at or below the savepoint, and
 *       the log it truncates to that size grows again from there. So a rollback only drops writes from the end.</li>
 *   <li>A post-commit hook writes after the commit, at the size of the committed log, so {@code atLog} still never
 *       decreases. No rollback follows the commit: nothing drops a hook's write.</li>
 *   <li>When the root machine records its trail, each entry of the log has a mark at its own index: when it was
 *       logged, and, on the hop of a failure route, what the route recovered from. {@link #truncateMarks} drops the
 *       marks of the entries a rollback dropped. A flow that a write created, in a call that does not record its
 *       trail, keeps no mark and allocates nothing for marks.</li>
 * </ul>
 */
final class CallFlow {

    private final Object rootEntity;
    private final List<Write> writes = new ArrayList<>();
    /** The mark of each entry of the log, by index; created by the first mark. */
    private Optional<List<Mark>> marks = Optional.empty();

    CallFlow(Object rootEntity) {
        this.rootEntity = rootEntity;
    }

    /** The entity of the root call: the instance its caller passed, never a copy. */
    Object rootEntity() { return rootEntity; }

    /** The value of the last write of a key equal to {@code key}, if that write put one. */
    <V> Optional<V> get(FlowKey<V> key) {
        for (int index = writes.size() - 1; index >= 0; index--) {
            Write write = writes.get(index);
            if (write.key().equals(key)) {
                return write.value().map(key.type()::cast);
            }
        }
        return Optional.empty();
    }

    /** The keys that hold a value, in the order of the write that gave each its current value; unmodifiable. */
    Set<FlowKey<?>> keys() {
        return values().keySet();
    }

    /** Stages {@code value} under {@code key}, written when the log had {@code atLog} entries. */
    <V> void put(FlowKey<V> key, V value, int atLog) {
        writes.add(new Write(key, Optional.of(value), atLog));
    }

    /** Stages the removal of {@code key}, written when the log had {@code atLog} entries. */
    void remove(FlowKey<?> key, int atLog) {
        writes.add(new Write(key, Optional.empty(), atLog));
    }

    /** Drops the writes made when the log had more than {@code savepoint} entries; see the class comment. */
    void rollbackTo(int savepoint) {
        int kept = writes.size();
        while (kept > 0 && writes.get(kept - 1).atLog() > savepoint) {
            kept--;
        }
        writes.subList(kept, writes.size()).clear();
    }

    /**
     * The value of each key that holds one, in the order of {@link #keys()}, each under the key of the write that put
     * it; unmodifiable.
     */
    Map<FlowKey<?>, Object> values() {
        Map<FlowKey<?>, Optional<Object>> latest = new LinkedHashMap<>();
        for (int index = 0; index < writes.size(); index++) {
            Write write = writes.get(index);
            latest.remove(write.key());
            latest.put(write.key(), write.value());
        }
        Map<FlowKey<?>, Object> present = new LinkedHashMap<>();
        for (Map.Entry<FlowKey<?>, Optional<Object>> entry : latest.entrySet()) {
            if (entry.getValue().isPresent()) {
                present.put(entry.getKey(), entry.getValue().get());
            }
        }
        return Collections.unmodifiableMap(present);
    }

    /**
     * What {@code clock}, the clock of a call's trail, reads now. A clock that answers null is refused here with a
     * NullPointerException, so the call fails as it does when the clock throws.
     */
    static Instant now(InstantSource clock) {
        return Objects.requireNonNull(clock.instant(), "the clock of the trail answered null");
    }

    /**
     * Marks the entry at {@code logIndex}, the one just appended: it was logged {@code at}. The marks follow the log,
     * so that index is the number of marks.
     */
    void mark(int logIndex, Instant at) {
        if (marks.isEmpty()) {
            marks = Optional.of(new ArrayList<>());
        }
        marks.get().add(logIndex, new Mark(at, Optional.empty()));
    }

    /** Leaves {@code recovery} on the mark of the entry at {@code logIndex}, the hop of a failure route. */
    void recovery(int logIndex, FlowEntry.Recovery recovery) {
        List<Mark> list = marks.orElseThrow();
        list.set(logIndex, new Mark(list.get(logIndex).at(), Optional.of(recovery)));
    }

    /** Drops the marks of the entries at {@code logSize} and after: a rollback truncated the log to that size. */
    void truncateMarks(int logSize) {
        if (marks.isPresent()) {
            List<Mark> list = marks.get();
            list.subList(Math.min(logSize, list.size()), list.size()).clear();
        }
    }

    /** When the entry at {@code logIndex} was logged; empty when it has no mark. */
    Optional<Instant> markAt(int logIndex) {
        return logIndex < markCount() ? Optional.of(marks.get().get(logIndex).at()) : Optional.empty();
    }

    /** What the hop at {@code logIndex} recovered from, if a failure route took it; empty otherwise. */
    Optional<FlowEntry.Recovery> recoveryAt(int logIndex) {
        return logIndex < markCount() ? marks.get().get(logIndex).recovery() : Optional.empty();
    }

    private int markCount() {
        return marks.isPresent() ? marks.get().size() : 0;
    }

    /** One put, with its value, or one remove, without; {@code atLog} is the size of the log when it was made. */
    private record Write(FlowKey<?> key, Optional<Object> value, int atLog) { }

    /** The mark of one entry of the log: when it was logged, and what a failure route recovered from, if any. */
    private record Mark(Instant at, Optional<FlowEntry.Recovery> recovery) { }
}
