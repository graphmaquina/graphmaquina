package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.FlowEntry;
import io.github.graphmaquina.api.FlowKey;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The flow of one root call, owned by its {@link UnitOfWork}: the root entity of the call and the writes its steps
 * and its hooks made, in order, less those a later write replaced. Local to the thread running the call, like the unit
 * of work, so it has no synchronization.
 * <ul>
 *   <li>Each put or remove is a write that keeps the size the log had when it was made, its {@code atLog}. A read
 *       answers the last write of the key, so a remove is a write without a value.</li>
 *   <li>Each write keeps the position of the previous write of its key, if any. Up to {@link #SCANNED} writes, a read
 *       scans them back from the last. Once the flow holds more, an index maps each key written to the position of
 *       its last write, and from then on a read is one lookup, and {@link #values()} sorts the last write of each key
 *       by position rather than replaying every write.</li>
 *   <li>A write of the key of the write just before it, at the same {@code atLog}, replaces that write in place: no
 *       read could see the replaced write any more, and no rollback could drop one of the two without the other,
 *       since a rollback drops by {@code atLog}. So a step that writes one key again and again keeps one write.</li>
 *   <li>{@link #rollbackTo} drops the writes made strictly after a savepoint. The savepoint of a step is the size of
 *       the log before its hop is appended, and its action writes once the hop is in, at {@code atLog} of at least
 *       the savepoint plus one; what an earlier step wrote has {@code atLog} of at most the savepoint. A COLLECT
 *       element's savepoint can equal the position of its parent action's write, which must stay.</li>
 *   <li>{@code atLog} never decreases along the list: a rollback leaves only writes at or below the savepoint, and
 *       the log it truncates to that size grows again from there. So a rollback only drops writes from the end, and
 *       once the flow has its index, it points the index of the key of each write it drops back at the previous write
 *       of that key, from the last dropped to the first, which leaves the index as it was before those writes.</li>
 *   <li>A post-commit hook writes after the commit, at the size of the committed log, so {@code atLog} still never
 *       decreases. No rollback follows the commit: nothing drops a hook's write.</li>
 *   <li>When the root machine records its trail, each entry of the log has a mark at its own index: when it was
 *       logged, and, on the hop of a failure route, what the route recovered from. {@link #truncateMarks} drops the
 *       marks of the entries a rollback dropped. A flow that a write created, in a call that does not record its
 *       trail, keeps no mark and allocates nothing for marks.</li>
 * </ul>
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class CallFlow {

    /** What a write keeps as the position of the previous write of its key when it is the first write of that key. */
    private static final int NONE = -1;
    /**
     * How many writes a read scans: a flow that holds more has its index. A call that writes a few values reads them
     * faster by comparing a few keys than by hashing one.
     */
    static final int SCANNED = 8;

    /** The entity of the root call: the instance its caller passed, never a copy. */
    @Getter(AccessLevel.PACKAGE)
    private final Object rootEntity;
    private final List<Write> writes = new ArrayList<>();
    /**
     * The position in {@link #writes} of the last write of each key written, keys compared by equality; created by the
     * write that makes the flow hold more than {@link #SCANNED} writes, and kept from then on.
     */
    private Optional<Map<FlowKey<?>, Integer>> index = Optional.empty();
    /** The mark of each entry of the log, by index; created by the first mark. */
    private Optional<List<Mark>> marks = Optional.empty();

    /** The value of the last write of a key equal to {@code key}, if that write put one. */
    <V> Optional<V> get(FlowKey<V> key) {
        int position = lastWrite(key);
        return position == NONE ? Optional.empty() : writes.get(position).value().map(key.type()::cast);
    }

    /** The keys that hold a value, in the order of the write that gave each its current value; unmodifiable. */
    Set<FlowKey<?>> keys() {
        return values().keySet();
    }

    /** Stages {@code value} under {@code key}, written when the log had {@code atLog} entries. */
    <V> void put(FlowKey<V> key, V value, int atLog) {
        write(key, Optional.of(value), atLog);
    }

    /** Stages the removal of {@code key}, written when the log had {@code atLog} entries. */
    void remove(FlowKey<?> key, int atLog) {
        write(key, Optional.empty(), atLog);
    }

    /**
     * Drops the writes made when the log had more than {@code savepoint} entries, and points the index back for each,
     * if the flow has its index; see the class comment.
     */
    void rollbackTo(int savepoint) {
        int kept = writes.size();
        while (kept > 0 && writes.get(kept - 1).atLog() > savepoint) {
            kept--;
            if (index.isPresent()) {
                Write dropped = writes.get(kept);
                if (dropped.previous() == NONE) {
                    index.get().remove(dropped.key());
                } else {
                    index.get().put(dropped.key(), dropped.previous());
                }
            }
        }
        writes.subList(kept, writes.size()).clear();
    }

    /** How many writes the flow keeps: a write that replaced the one just before it counts once. */
    int writeCount() { return writes.size(); }

    /** Whether the flow has its index, as {@link #SCANNED} says. */
    boolean indexed() { return index.isPresent(); }

    /**
     * The value of each key that holds one, in the order of {@link #keys()}, each under the key of the write that put
     * it; unmodifiable. A flow without a write, that of a call that records its trail and writes nothing, answers the
     * empty map without building one.
     */
    Map<FlowKey<?>, Object> values() {
        if (writes.isEmpty()) {
            return Map.of();
        }
        int[] positions;
        int count = 0;
        if (index.isPresent()) {
            positions = new int[index.get().size()];
            for (Integer position : index.get().values()) {
                if (writes.get(position).value().isPresent()) {
                    positions[count] = position;
                    count++;
                }
            }
            Arrays.sort(positions, 0, count);
        } else {
            positions = new int[writes.size()];
            for (int position = 0; position < writes.size(); position++) {
                Write write = writes.get(position);
                if (write.value().isPresent() && lastWrite(write.key()) == position) {
                    positions[count] = position;
                    count++;
                }
            }
        }
        Map<FlowKey<?>, Object> present = new LinkedHashMap<>();
        for (int rank = 0; rank < count; rank++) {
            Write write = writes.get(positions[rank]);
            present.put(write.key(), write.value().get());
        }
        return Collections.unmodifiableMap(present);
    }

    /**
     * Appends a write of {@code key}, or replaces the last write when it is of the same key at the same {@code atLog},
     * as the class comment says. An appended write points the index at itself, or creates the index from every write
     * when it is the first to make the flow hold more than {@link #SCANNED}.
     */
    private void write(FlowKey<?> key, Optional<Object> value, int atLog) {
        int end = writes.size() - 1;
        if (end >= 0 && writes.get(end).atLog() == atLog && writes.get(end).key().equals(key)) {
            writes.set(end, new Write(key, value, atLog, writes.get(end).previous()));
            return;
        }
        writes.add(new Write(key, value, atLog, lastWrite(key)));
        if (index.isPresent()) {
            index.get().put(key, end + 1);
        } else if (writes.size() > SCANNED) {
            Map<FlowKey<?>, Integer> created = new HashMap<>();
            for (int position = 0; position < writes.size(); position++) {
                created.put(writes.get(position).key(), position);
            }
            index = Optional.of(created);
        }
    }

    /** The position of the last write of a key equal to {@code key}; {@link #NONE} when no write has such a key. */
    private int lastWrite(FlowKey<?> key) {
        if (index.isPresent()) {
            Integer position = index.get().get(key);
            return position == null ? NONE : position;
        }
        for (int position = writes.size() - 1; position >= 0; position--) {
            if (writes.get(position).key().equals(key)) {
                return position;
            }
        }
        return NONE;
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

    /**
     * One put, with its value, or one remove, without; {@code atLog} is the size of the log when it was made, and
     * {@code previous} the position of the previous write of its key, {@link #NONE} when it is the first.
     */
    private record Write(FlowKey<?> key, Optional<Object> value, int atLog, int previous) { }

    /** The mark of one entry of the log: when it was logged, and what a failure route recovered from, if any. */
    private record Mark(Instant at, Optional<FlowEntry.Recovery> recovery) { }
}
