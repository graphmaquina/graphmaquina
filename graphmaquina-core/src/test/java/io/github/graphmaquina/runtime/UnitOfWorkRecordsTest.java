package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRef;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.api.TransitionSpec;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Checks the journal snapshot contract independently of how records are collected. */
class UnitOfWorkRecordsTest {

    /** States of the first machine, whose journal includes both moves and actions. */
    enum State { A, B }

    /** Transition identifiers of the first machine. */
    enum Transition { GO }

    /** States of a second machine sharing the same unit of work. */
    enum OtherState { BEFORE, AFTER }

    /** Transition identifiers of the second machine. */
    enum OtherTransition { RUN }

    /** Builds a move whose entity identity and cascade depth can be checked in the snapshot. */
    private static LogEntry move(Object entity, int depth) {
        MachineKey<Object, State, Transition> key = MachineKey.of(Object.class, State.class, Transition.class);
        EntityRef ref = new EntityRef(key, depth, entity);
        TransitionSpec<Object, State, Transition> spec = new TransitionSpec<>(State.A, Transition.GO, State.B,
                Optional.empty(), List.of(), Optional.empty(), List.of(), Optional.empty(), Optional.empty());
        return new LogEntry(ref, spec, depth);
    }

    /** Empty, small and larger snapshots retain entry order and reject mutation. */
    @Test
    void snapshotsPreserveOrderAndAreImmutableAtDifferentSizes() {
        for (int size : new int[] {0, 1, 2, 3, 12}) {
            UnitOfWork work = new UnitOfWork(100, Long.MAX_VALUE);
            Object[] entities = new Object[size];
            for (int index = 0; index < size; index++) {
                entities[index] = new Object();
                work.append(move(entities[index], index));
            }
            List<TransitionRecord<?, ?, ?>> records = work.records();
            assertEquals(size, records.size());
            for (int index = 0; index < size; index++) {
                TransitionRecord<?, ?, ?> record = records.get(index);
                assertSame(entities[index], record.entity());
                assertEquals(index, record.depth());
                assertEquals(State.A, record.from());
                assertEquals(Transition.GO, record.transition());
                assertEquals(State.B, record.to());
                assertEquals(TransitionKind.MOVE, record.kind());
                assertEquals(Optional.empty(), record.action());
            }
            assertThrows(UnsupportedOperationException.class, records::clear);
        }
    }

    /** A heterogeneous snapshot preserves each machine's key, entity, enums, action name and depth. */
    @Test
    void snapshotPreservesTypedFieldsAcrossMachines() {
        UnitOfWork work = new UnitOfWork(100, Long.MAX_VALUE);
        Object first = new Object();
        LogEntry firstEntry = move(first, 2);
        work.append(firstEntry);
        MachineKey<String, OtherState, OtherTransition> key =
                MachineKey.of(String.class, OtherState.class, OtherTransition.class);
        String second = new String("second");
        ActionRef<String, OtherState, OtherTransition> action = new ActionRef.Sync<>("audit",
                (TransitionContext<String, OtherState, OtherTransition> context) -> ActionOutcome.ok());
        TransitionSpec<String, OtherState, OtherTransition> spec = new TransitionSpec<>(
                OtherState.BEFORE, OtherTransition.RUN, OtherState.AFTER, Optional.of(action), List.of(),
                Optional.empty(), List.of(), Optional.empty(), Optional.empty());
        work.append(new LogEntry(new EntityRef(key, "id", second), spec, 7));

        List<TransitionRecord<?, ?, ?>> records = work.records();
        assertEquals(2, records.size());
        assertSame(firstEntry.ref().key(), records.get(0).key());
        assertSame(first, records.get(0).entity());
        TransitionRecord<?, ?, ?> record = records.get(1);
        assertSame(key, record.key());
        assertSame(second, record.entity());
        assertEquals(OtherState.BEFORE, record.from());
        assertEquals(OtherTransition.RUN, record.transition());
        assertEquals(OtherState.AFTER, record.to());
        assertEquals(TransitionKind.ACTION, record.kind());
        assertEquals(Optional.of("audit"), record.action());
        assertEquals(7, record.depth());
    }

    /** Each read creates fresh records and remains independent of subsequent appends and rollbacks. */
    @Test
    void earlierSnapshotsSurviveAppendAndRollbackWithoutReusingRecords() {
        UnitOfWork work = new UnitOfWork(100, Long.MAX_VALUE);
        Object first = new Object();
        Object second = new Object();
        work.append(move(first, 0));
        int mark = work.savepoint();
        List<TransitionRecord<?, ?, ?>> before = work.records();
        List<TransitionRecord<?, ?, ?>> repeated = work.records();
        assertEquals(before, repeated);
        assertNotSame(before.get(0), repeated.get(0));
        work.append(move(second, 1));
        List<TransitionRecord<?, ?, ?>> afterAppend = work.records();
        assertEquals(1, before.size());
        assertEquals(2, afterAppend.size());
        assertSame(second, afterAppend.get(1).entity());
        work.rollbackTo(mark);
        List<TransitionRecord<?, ?, ?>> afterRollback = work.records();
        assertEquals(before, afterRollback);
        assertNotSame(before.get(0), afterRollback.get(0));
        assertEquals(2, afterAppend.size());
        assertSame(second, afterAppend.get(1).entity());
        work.rollbackTo(0);
        assertEquals(List.of(), work.records());
        assertSame(first, before.get(0).entity());
    }

    /** Snapshot conversion continues to validate both entity and enum types against their owning key. */
    @Test
    void malformedEntriesStillFailAtTheTypedBoundary() {
        MachineKey<String, State, Transition> key = MachineKey.of(String.class, State.class, Transition.class);
        LogEntry validEnums = move(new Object(), 0);
        UnitOfWork wrongEntity = new UnitOfWork(100, Long.MAX_VALUE);
        wrongEntity.append(new LogEntry(new EntityRef(key, 1, new Object()), validEnums.spec(), 0));
        assertThrows(ClassCastException.class, wrongEntity::records);

        MachineKey<Object, OtherState, OtherTransition> otherKey =
                MachineKey.of(Object.class, OtherState.class, OtherTransition.class);
        UnitOfWork wrongEnums = new UnitOfWork(100, Long.MAX_VALUE);
        wrongEnums.append(new LogEntry(new EntityRef(otherKey, 1, new Object()), validEnums.spec(), 0));
        assertThrows(ClassCastException.class, wrongEnums::records);
    }
}
