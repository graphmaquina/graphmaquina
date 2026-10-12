package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.error.AbortDetails;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.RandomAccess;
import org.junit.jupiter.api.Test;

/**
 * The journal of a unit of work and the snapshots it hands out: a snapshot keeps the entries and the statuses it was
 * taken with while the journal grows past it and ends the actions after it, a status changed below a snapshot leaves
 * the snapshot as it was, and a snapshot is an unmodifiable list that an abort keeps without copying it.
 */
class UnitOfWorkJournalTest {

    /** States of the machine whose actions the journal records. */
    enum State { A, B }

    /** Transition identifiers of that machine. */
    enum Transition { GO }

    static final MachineKey<Object, State, Transition> KEY =
            MachineKey.of(Object.class, State.class, Transition.class);

    /** Appends a running action of {@code entity}, as an action step does before the action runs. */
    private static int start(UnitOfWork work, Object entity) {
        return work.appendJournal(new ActionRecord(KEY, entity, State.A, Transition.GO, State.B, "Act", 0,
                ActionStatus.RUNNING));
    }

    private static List<ActionStatus> statuses(List<ActionRecord> journal) {
        List<ActionStatus> statuses = new ArrayList<>();
        for (ActionRecord record : journal) {
            statuses.add(record.status());
        }
        return statuses;
    }

    private static void assertUnmodifiable(List<ActionRecord> journal) {
        ActionRecord first = journal.get(0);
        assertThrows(UnsupportedOperationException.class, () -> journal.add(first));
        assertThrows(UnsupportedOperationException.class, () -> journal.add(0, first));
        assertThrows(UnsupportedOperationException.class, () -> journal.set(0, first));
        assertThrows(UnsupportedOperationException.class, () -> journal.remove(0));
        assertThrows(UnsupportedOperationException.class, () -> journal.remove(new Object()));
        assertThrows(UnsupportedOperationException.class, journal::clear);
        assertThrows(UnsupportedOperationException.class, () -> journal.addAll(List.of()));
        assertThrows(UnsupportedOperationException.class, () -> journal.addAll(0, List.of()));
        assertThrows(UnsupportedOperationException.class, () -> journal.removeAll(List.of()));
        assertThrows(UnsupportedOperationException.class, () -> journal.retainAll(journal));
        assertThrows(UnsupportedOperationException.class, () -> journal.removeIf((ActionRecord record) -> false));
        assertThrows(UnsupportedOperationException.class, () -> journal.replaceAll((ActionRecord record) -> record));
        assertThrows(UnsupportedOperationException.class, () -> journal.sort(null));
        assertThrows(UnsupportedOperationException.class, () -> journal.subList(0, 0).addAll(List.of()));
        assertThrows(UnsupportedOperationException.class, () -> journal.subList(0, 0).clear());
        assertThrows(UnsupportedOperationException.class, () -> journal.reversed().addAll(List.of()));
        assertThrows(UnsupportedOperationException.class, () -> journal.reversed().clear());
    }

    /** The journal before any action ran is the shared empty list. */
    @Test
    void anEmptyJournalIsTheEmptyList() {
        assertSame(List.of(), new UnitOfWork(100, Long.MAX_VALUE).journal());
    }

    /**
     * A snapshot taken with two ended actions keeps them while forty more are appended, past every capacity the
     * journal grows through, and ended; a same status set again changes no entry.
     */
    @Test
    void aSnapshotKeepsItsEntriesWhileTheJournalGrowsPastIt() {
        UnitOfWork work = new UnitOfWork(100, Long.MAX_VALUE);
        Object first = new Object();
        Object second = new Object();
        work.journalStatus(start(work, first), ActionStatus.OK);
        start(work, second);
        work.markRunningAsThrew();
        List<ActionRecord> snapshot = work.journal();
        List<ActionRecord> copy = List.copyOf(snapshot);
        for (int index = 0; index < 40; index++) {
            int entry = start(work, new Object());
            work.journalStatus(entry, index % 2 == 0 ? ActionStatus.OK : ActionStatus.FAILED);
        }
        start(work, new Object());
        work.markRunningAsThrew();
        work.journalStatus(1, ActionStatus.THREW);
        List<ActionRecord> later = work.journal();
        assertEquals(copy, snapshot);
        assertEquals(2, snapshot.size());
        assertSame(first, snapshot.get(0).entity());
        assertSame(second, snapshot.get(1).entity());
        assertEquals(List.of(ActionStatus.OK, ActionStatus.THREW), statuses(snapshot));
        assertThrows(IndexOutOfBoundsException.class, () -> snapshot.get(2));
        assertThrows(IndexOutOfBoundsException.class, () -> snapshot.get(-1));
        assertEquals(43, later.size());
        assertSame(snapshot.get(1), later.get(1));
        assertEquals(snapshot, later.subList(0, 2));
        assertEquals(ActionStatus.FAILED, later.get(3).status());
        assertEquals(ActionStatus.THREW, later.get(42).status());
    }

    /**
     * A status changed below a snapshot, which the engine never does once it handed one out, still leaves that
     * snapshot as it was: the journal is copied first.
     */
    @Test
    void aStatusChangedBelowASnapshotLeavesTheSnapshotAsItWas() {
        UnitOfWork work = new UnitOfWork(100, Long.MAX_VALUE);
        int first = start(work, new Object());
        start(work, new Object());
        List<ActionRecord> snapshot = work.journal();
        work.journalStatus(first, ActionStatus.OK);
        work.markRunningAsThrew();
        start(work, new Object());
        List<ActionRecord> later = work.journal();
        work.journalStatus(2, ActionStatus.FAILED);
        assertEquals(List.of(ActionStatus.RUNNING, ActionStatus.RUNNING), statuses(snapshot));
        assertEquals(List.of(ActionStatus.OK, ActionStatus.THREW, ActionStatus.RUNNING), statuses(later));
        assertEquals(List.of(ActionStatus.OK, ActionStatus.THREW, ActionStatus.FAILED), statuses(work.journal()));
    }

    /**
     * A snapshot is a random-access list that refuses every change, even one that would change nothing, and compares,
     * hashes and prints as the list of its entries does.
     */
    @Test
    void aSnapshotIsAnUnmodifiableListOfItsEntries() {
        UnitOfWork work = new UnitOfWork(100, Long.MAX_VALUE);
        work.journalStatus(start(work, "first"), ActionStatus.OK);
        work.journalStatus(start(work, "second"), ActionStatus.FAILED);
        List<ActionRecord> snapshot = work.journal();
        List<ActionRecord> entries = List.copyOf(snapshot);
        assertInstanceOf(RandomAccess.class, snapshot);
        assertUnmodifiable(snapshot);
        assertEquals(entries, snapshot);
        assertEquals(snapshot, entries);
        assertEquals(entries.hashCode(), snapshot.hashCode());
        assertEquals(entries.toString(), snapshot.toString());
        assertEquals(entries, List.copyOf(snapshot.subList(0, 2)));
    }

    /** An abort keeps the journal's snapshot as it is and copies any other list it is given. */
    @Test
    void anAbortKeepsTheSnapshotAndCopiesAnyOtherList() {
        UnitOfWork work = new UnitOfWork(100, Long.MAX_VALUE);
        work.journalStatus(start(work, "first"), ActionStatus.OK);
        List<ActionRecord> snapshot = work.journal();
        AbortDetails kept = new AbortDetails(KEY, "first", Optional.of(State.A), Optional.empty(), Optional.empty(),
                Optional.empty(), List.of(), snapshot);
        assertSame(snapshot, kept.journal());
        List<ActionRecord> given = new ArrayList<>(snapshot);
        AbortDetails copied = new AbortDetails(KEY, "first", Optional.of(State.A), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(), given);
        given.clear();
        assertNotSame(given, copied.journal());
        assertEquals(snapshot, copied.journal());
    }
}
