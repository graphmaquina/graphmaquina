package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        return new LogEntry(ref, new TransitionSpec<Object, S, T>(from, T.GO, to,
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
        assertEquals(firsts, summary.changes().stream().map((CommitSummary.Change change) -> change.first().from()).toList());
        assertEquals(lasts, summary.changes().stream().map((CommitSummary.Change change) -> change.last().to()).toList());
        assertEquals(moved, summary.movedEntities());
        assertEquals(List.of(b), summary.movedEntities());
    }

    /** The summary is a reading of the log, not a second place to keep it: appending again answers a new one. */
    @Test
    void aLaterAppendIsNotVisibleInAnAlreadyBuiltSummary() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        EntityRef a = ref(1);
        uow.append(hop(a, S.A, S.B));
        CommitSummary summary = uow.commitSummary();
        uow.append(hop(a, S.B, S.C));
        assertEquals(S.B, summary.changes().get(0).last().to());
        assertEquals(S.C, uow.commitSummary().changes().get(0).last().to());
    }
}
