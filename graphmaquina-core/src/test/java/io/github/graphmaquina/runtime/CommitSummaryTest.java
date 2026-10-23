package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.TransitionSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The grouping a committed log is reduced to: one change per entity, in order of first appearance, keeping the
 * entry that says where the entity started and the one that says where it ends. The log is built here by hand so
 * that order, net movement and the reference that survives the grouping stay observable without a registry.
 */
class CommitSummaryTest {

    /** States of the fixture machine. */
    enum S { A, B, C }

    /** Transition identifiers of the fixture machine. */
    enum T { GO }

    static EntityRef ref(Object id) {
        return new EntityRef(MachineKey.of(Object.class, S.class, T.class), id, new Object());
    }

    static LogEntry hop(EntityRef ref, S from, S to) {
        return new LogEntry.Hop(ref, new TransitionSpec<Object, S, T>(from, T.GO, to,
                Optional.empty(), List.of(), Optional.empty(), List.of(),
                Optional.empty(), Optional.empty()), 0);
    }

    @Test
    void preservesFirstAppearanceAndNetMovement() {
        EntityRef a = ref(1);
        EntityRef b = ref(2);
        CommitSummary summary = CommitSummary.from(List.of(hop(a, S.A, S.B), hop(b, S.A, S.C), hop(a, S.B, S.A)));
        assertEquals(List.of(a, b), summary.changes().stream().map(CommitSummary.Change::ref).toList());
        assertEquals(S.A, summary.changes().get(0).first().from());
        assertEquals(S.A, summary.changes().get(0).last().to());
        assertEquals(List.of(b), summary.movedEntities());
    }

    @Test
    void anEmptyLogSummarisesToNothing() {
        CommitSummary summary = CommitSummary.from(List.of());
        assertEquals(List.of(), summary.changes());
        assertEquals(List.of(), summary.movedEntities());
    }

    /**
     * A start reads like a hop: its child starts where the start's previous state says and ends at the initial
     * state, so it moves the child unless the child already stood at its initial state.
     */
    @Test
    void aStartMovesItsChildOnlyFromAnotherState() {
        EntityRef parent = ref(1);
        EntityRef restarted = ref(2);
        EntityRef idle = ref(3);
        CommitSummary summary = CommitSummary.from(List.of(hop(parent, S.A, S.B),
                new LogEntry.Start(restarted, S.C, S.A, parent, S.B, 0),
                new LogEntry.Start(idle, S.A, S.A, parent, S.B, 0)));
        assertEquals(List.of(parent, restarted, idle),
                summary.changes().stream().map(CommitSummary.Change::ref).toList());
        assertEquals(List.of(parent, restarted), summary.movedEntities());
    }

    /** A self-loop is a change with an entry to commit, and no net movement to notify. */
    @Test
    void aSelfLoopIsAChangeThatMovesNothing() {
        EntityRef a = ref(1);
        CommitSummary summary = CommitSummary.from(List.of(hop(a, S.A, S.A)));
        assertEquals(1, summary.changes().size());
        assertEquals(S.A, summary.changes().get(0).last().to());
        assertEquals(List.of(), summary.movedEntities());
    }

    /**
     * The grouping keeps the reference of the first entry, which is the one phase 2 writes through. Two equal
     * references carry different instances, so which one survives is observable.
     */
    @Test
    void theReferenceOfTheFirstEntrySurvivesTheGrouping() {
        EntityRef first = ref(1);
        EntityRef second = ref(1);
        CommitSummary summary = CommitSummary.from(List.of(hop(first, S.A, S.B), hop(second, S.B, S.C)));
        assertEquals(1, summary.changes().size());
        assertSame(first, summary.changes().get(0).ref());
        assertSame(first, summary.movedEntities().get(0));
    }

    @Test
    void theListsItHandsOutAreImmutable() {
        CommitSummary summary = CommitSummary.from(List.of(hop(ref(1), S.A, S.B)));
        assertThrows(UnsupportedOperationException.class, () -> summary.changes().clear());
        assertThrows(UnsupportedOperationException.class, () -> summary.movedEntities().clear());
    }

    /**
     * The historical control: chains() still answers the same grouping entry by entry, so the summary can be
     * compared against it on a log a unit of work built through its own append and rollback protocol.
     */
    @Test
    void itAnswersWhatTheChainsHelperAnswers() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        EntityRef a = ref(1);
        EntityRef b = ref(2);
        EntityRef c = ref(3);
        uow.append(hop(a, S.A, S.B));
        uow.append(hop(b, S.A, S.C));
        int mark = uow.savepoint();
        uow.append(hop(c, S.A, S.B));
        uow.rollbackTo(mark);
        uow.append(hop(a, S.B, S.A));
        CommitSummary summary = uow.commitSummary();
        List<EntityRef> refs = new ArrayList<>();
        List<Enum<?>> firsts = new ArrayList<>();
        List<Enum<?>> lasts = new ArrayList<>();
        List<EntityRef> moved = new ArrayList<>();
        for (Map.Entry<EntityRef, List<LogEntry>> chain : uow.chains().entrySet()) {
            List<LogEntry> entries = chain.getValue();
            Enum<?> first = entries.get(0).from();
            Enum<?> last = entries.get(entries.size() - 1).to();
            refs.add(chain.getKey());
            firsts.add(first);
            lasts.add(last);
            if (first != last) {
                moved.add(chain.getKey());
            }
        }
        assertEquals(refs, summary.changes().stream().map(CommitSummary.Change::ref).toList());
        assertEquals(firsts,
                summary.changes().stream().map((CommitSummary.Change change) -> change.first().from()).toList());
        assertEquals(lasts,
                summary.changes().stream().map((CommitSummary.Change change) -> change.last().to()).toList());
        assertEquals(moved, summary.movedEntities());
        assertEquals(List.of(b), summary.movedEntities());
    }

    /** Later appends and rollbacks preserve the immutable entries and references of an earlier summary. */
    @Test
    void aLaterAppendIsNotVisibleInAnAlreadyBuiltSummary() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        EntityRef a = ref(1);
        LogEntry first = hop(a, S.A, S.B);
        uow.append(first);
        CommitSummary summary = uow.commitSummary();
        List<CommitSummary.Change> changes = summary.changes();
        List<EntityRef> moved = summary.movedEntities();
        uow.append(hop(a, S.B, S.C));
        assertEquals(S.B, summary.changes().get(0).last().to());
        assertEquals(S.C, uow.commitSummary().changes().get(0).last().to());
        uow.rollbackTo(1);
        assertSame(first, uow.commitSummary().changes().get(0).last());
        uow.rollbackTo(0);
        assertEquals(List.of(), uow.commitSummary().changes());
        assertEquals(1, changes.size());
        assertSame(a, changes.get(0).ref());
        assertSame(first, changes.get(0).first());
        assertSame(first, changes.get(0).last());
        assertSame(a, moved.get(0));
        assertThrows(UnsupportedOperationException.class, changes::clear);
        assertThrows(UnsupportedOperationException.class, moved::clear);
    }

    /** A one-entry action-free transaction retains either kind of entry, even when it moves nothing. */
    @Test
    void actionFreeSingleEntriesRetainTheirReferencesAndMovement() {
        EntityRef child = ref(1);
        EntityRef parent = ref(2);
        List<LogEntry> entries = List.of(hop(child, S.A, S.B), hop(child, S.A, S.A),
                new LogEntry.Start(child, S.C, S.A, parent, S.B, 0),
                new LogEntry.Start(child, S.A, S.A, parent, S.B, 0));
        List<List<EntityRef>> expectedMoved = List.of(List.of(child), List.of(), List.of(child), List.of());
        for (int index = 0; index < entries.size(); index++) {
            UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
            LogEntry entry = entries.get(index);
            if (entry instanceof LogEntry.Start start) {
                uow.appendStart(start);
            } else {
                uow.append(entry);
            }
            assertEquals(List.of(), uow.journal());
            CommitSummary summary = uow.commitSummary();
            assertEquals(1, summary.changes().size());
            CommitSummary.Change change = summary.changes().get(0);
            assertSame(child, change.ref());
            assertSame(entry, change.first());
            assertSame(entry, change.last());
            assertEquals(expectedMoved.get(index), summary.movedEntities());
            if (!expectedMoved.get(index).isEmpty()) {
                assertSame(child, summary.movedEntities().get(0));
            }
            assertThrows(UnsupportedOperationException.class, () -> summary.changes().clear());
            assertThrows(UnsupportedOperationException.class, () -> summary.movedEntities().clear());
        }
    }

    /** Counts the first grouping hash and runs a one-shot callback without an absent callback sentinel. */
    private static final class HashCallbackIdentity {

        private int hashes;
        private int equalities;
        private Runnable onHash = () -> { };

        @Override
        public boolean equals(Object other) {
            equalities++;
            return this == other;
        }

        @Override
        public int hashCode() {
            hashes++;
            Runnable callback = onHash;
            onHash = () -> { };
            callback.run();
            return 17;
        }
    }

    /** A journal-only callback does not reselect grouping or hash the reference a second time. */
    @Test
    void aSingleHashRunsOnceWithoutEqualityWhenItAppendsOnlyToTheJournal() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        HashCallbackIdentity identity = new HashCallbackIdentity();
        EntityRef a = ref(identity);
        LogEntry first = hop(a, S.A, S.B);
        uow.append(first);
        ActionRecord action = new ActionRecord(a.key(), a.instance(), S.A, T.GO, S.B, "callback", 0,
                ActionStatus.OK);
        identity.onHash = () -> uow.appendJournal(action);
        CommitSummary summary = uow.commitSummary();
        assertEquals(1, identity.hashes);
        assertEquals(0, identity.equalities);
        assertEquals(List.of(action), uow.journal());
        assertEquals(1, summary.changes().size());
        assertSame(a, summary.changes().get(0).ref());
        assertSame(first, summary.changes().get(0).first());
        assertSame(first, summary.changes().get(0).last());
        assertSame(a, summary.movedEntities().get(0));
    }

    /** Appending and rolling back to one entry during hashing leaves the one-entry summary and its snapshot intact. */
    @Test
    void aSingleHashCanAppendAndRollBackToOne() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        HashCallbackIdentity identity = new HashCallbackIdentity();
        EntityRef a = ref(identity);
        LogEntry first = hop(a, S.A, S.B);
        uow.append(first);
        int mark = uow.savepoint();
        identity.onHash = () -> {
            uow.append(hop(ref(2), S.A, S.C));
            uow.rollbackTo(mark);
        };
        CommitSummary summary = uow.commitSummary();
        assertEquals(1, identity.hashes);
        assertEquals(0, identity.equalities);
        assertEquals(List.of(first), uow.entries());
        assertEquals(List.of(), uow.journal());
        uow.rollbackTo(0);
        assertEquals(1, summary.changes().size());
        assertSame(a, summary.changes().get(0).ref());
        assertSame(first, summary.changes().get(0).first());
        assertSame(first, summary.changes().get(0).last());
        assertSame(a, summary.movedEntities().get(0));
        assertThrows(UnsupportedOperationException.class, () -> summary.changes().clear());
        assertThrows(UnsupportedOperationException.class, () -> summary.movedEntities().clear());
    }
}
