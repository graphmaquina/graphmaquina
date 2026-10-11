package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.FlowEntry;
import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.fixtures.TickingClock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Spec R12.2 and R12.3: the time marks of a call that records its trail, as its unit of work keeps them. Switching
 * the trail on creates the call's flow. Each entry of the log, a hop or a start, is marked right after it is in the
 * log, on the trail's clock, and its mark sits at its index. A failure route leaves its recovery on the mark of its
 * recovery hop, and a rollback truncates the marks to the savepoint on every branch of {@link UnitOfWork#rollbackTo}.
 * A unit of work that does not record its trail keeps no mark.
 */
class TrailMarksTest {

    /** States of the fixture machine. */
    enum S { A, B }

    /** Transition identifiers of the fixture machine. */
    enum T { GO }

    private static final MachineKey<Object, S, T> KEY = MachineKey.of(Object.class, S.class, T.class);

    private static EntityRef ref(Object id) {
        return new EntityRef(KEY, id, new Object());
    }

    private static LogEntry.Hop hop(EntityRef ref) {
        return new LogEntry.Hop(ref, new TransitionSpec<Object, S, T>(S.A, T.GO, S.B,
                Optional.empty(), List.of(), Optional.empty(), List.of(),
                Optional.empty(), Optional.empty()), 0);
    }

    /** The start of {@code child} in A, under {@code parent}, which entered B. */
    private static LogEntry.Start start(EntityRef child, EntityRef parent) {
        return new LogEntry.Start(child, S.A, S.A, parent, S.B, 0);
    }

    private static FlowEntry.Recovery recovery() {
        return new FlowEntry.Recovery(T.GO, "no reviewer", Optional.empty(), Optional.empty());
    }

    /** A unit of work recording its trail on a new {@link TickingClock}, with a root entity of its own. */
    private static UnitOfWork recording() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        uow.recordTrail(new Object(), new TickingClock());
        return uow;
    }

    /**
     * The marks of {@code uow} end where its log does, at {@code size}: the entry before has its mark, and no mark or
     * recovery is left at {@code size}.
     */
    private static void assertMarksEnd(UnitOfWork uow, int size) {
        CallFlow flow = uow.flow().orElseThrow();
        assertEquals(size, uow.savepoint());
        assertEquals(Optional.empty(), flow.markAt(size));
        assertEquals(Optional.empty(), flow.recoveryAt(size));
        if (size > 0) {
            assertTrue(flow.markAt(size - 1).isPresent());
        }
    }

    /**
     * Without the trail, appending creates no flow, a flow a write created keeps no mark, and the trail is the empty
     * list.
     */
    @Test
    void aUnitOfWorkThatDoesNotRecordItsTrailKeepsNoMark() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        assertFalse(uow.recordsTrail());
        uow.append(hop(ref(1)));
        assertFalse(uow.hasFlow());
        assertEquals(List.of(), uow.trail());
        CallFlow flow = uow.flow(new Object());
        uow.append(hop(ref(2)));
        uow.appendStart(start(ref(3), ref(2)));
        assertEquals(Optional.empty(), flow.markAt(0));
        assertEquals(Optional.empty(), flow.markAt(2));
        assertEquals(List.of(), uow.trail());
        uow.rollbackTo(1);
        assertEquals(List.of(), uow.trail());
    }

    /** Switching the trail on creates the call's flow at once, with the root entity, and reads no clock yet. */
    @Test
    void recordingTheTrailCreatesTheFlowWithTheRootEntity() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        TickingClock clock = new TickingClock();
        Object entity = new Object();
        uow.recordTrail(entity, clock);
        assertTrue(uow.recordsTrail());
        assertTrue(uow.hasFlow());
        assertSame(entity, uow.flow().orElseThrow().rootEntity());
        assertEquals(0, clock.reads());
        assertEquals(List.of(), uow.trail());
    }

    /**
     * Spec D10: each entry is marked right after it is in the log, a hop as a start, so the clock reads a log that
     * already holds the entry, and the mark sits at the entry's index.
     */
    @Test
    void eachEntryIsMarkedOnceItIsInTheLog() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        TickingClock clock = new TickingClock();
        List<Integer> logSizes = new ArrayList<>();
        clock.onRead = () -> logSizes.add(uow.savepoint());
        uow.recordTrail(new Object(), clock);
        uow.append(hop(ref(1)));
        uow.appendStart(start(ref(2), ref(1)));
        assertEquals(List.of(1, 2), logSizes);
        CallFlow flow = uow.flow().orElseThrow();
        assertEquals(Optional.of(TickingClock.at(1)), flow.markAt(0));
        assertEquals(Optional.of(TickingClock.at(2)), flow.markAt(1));
        assertEquals(Optional.empty(), flow.markAt(2));
        assertEquals(Optional.empty(), flow.recoveryAt(0));
    }

    /**
     * The trail has one entry per entry of the log, in order: a hop with the record {@code records()} gives for it and
     * a start with the one {@code starts()} gives, each at the instant of its mark. It is unmodifiable and does not
     * follow later entries.
     */
    @Test
    void theTrailHasAnEntryPerEntryOfTheLogAtItsMark() {
        UnitOfWork uow = recording();
        uow.append(hop(ref(1)));
        uow.appendStart(start(ref(2), ref(1)));
        uow.append(hop(ref(3)));
        List<FlowEntry> trail = uow.trail();
        assertEquals(List.of(
                new FlowEntry.Hop(uow.records().get(0), Optional.empty(), TickingClock.at(1)),
                new FlowEntry.Start(uow.starts().get(0), TickingClock.at(2)),
                new FlowEntry.Hop(uow.records().get(1), Optional.empty(), TickingClock.at(3))), trail);
        assertThrows(UnsupportedOperationException.class, () -> trail.add(trail.get(0)));
        uow.append(hop(ref(4)));
        assertEquals(3, trail.size());
        assertEquals(4, uow.trail().size());
    }

    /** Spec R12.2: a recovery stays on the mark of its recovery hop, which keeps its instant. */
    @Test
    void aRecoveryStaysOnTheMarkOfItsHop() {
        UnitOfWork uow = recording();
        uow.append(hop(ref(1)));
        uow.append(hop(ref(2)));
        CallFlow flow = uow.flow().orElseThrow();
        flow.recovery(1, recovery());
        assertEquals(Optional.of(recovery()), flow.recoveryAt(1));
        assertEquals(Optional.empty(), flow.recoveryAt(0));
        assertEquals(Optional.of(TickingClock.at(2)), flow.markAt(1));
        assertEquals(new FlowEntry.Hop(uow.records().get(1), Optional.of(recovery()), TickingClock.at(2)),
                uow.trail().get(1));
    }

    /**
     * Savepoint 0 before the pending-state index exists: the branch that clears the whole log drops every mark, the
     * recovery with it, and the next entry gets a mark of its own.
     */
    @Test
    void aRollbackToZeroWithoutAnIndexTruncatesTheMarks() {
        UnitOfWork uow = recording();
        uow.append(hop(ref(1)));
        uow.flow().orElseThrow().recovery(0, recovery());
        uow.rollbackTo(0);
        assertMarksEnd(uow, 0);
        uow.append(hop(ref(2)));
        assertEquals(List.of(new FlowEntry.Hop(uow.records().get(0), Optional.empty(), TickingClock.at(2))),
                uow.trail());
    }

    /** A savepoint above 0 before the pending-state index exists: the branch that truncates the log. */
    @Test
    void aRollbackWithoutAnIndexTruncatesTheMarks() {
        UnitOfWork uow = recording();
        uow.append(hop(ref(1)));
        uow.append(hop(ref(2)));
        uow.appendStart(start(ref(3), ref(2)));
        uow.rollbackTo(1);
        assertMarksEnd(uow, 1);
        uow.append(hop(ref(4)));
        assertEquals(List.of(TickingClock.at(1), TickingClock.at(4)),
                List.of(uow.trail().get(0).at(), uow.trail().get(1).at()));
    }

    /** Once a lookup built the pending-state index: the branch that undoes the index entry by entry. */
    @Test
    void aRollbackWithAnIndexTruncatesTheMarks() {
        UnitOfWork uow = recording();
        EntityRef first = ref(1);
        uow.append(hop(first));
        assertEquals(Optional.of(S.B), uow.pendingState(first));
        uow.append(hop(ref(2)));
        uow.flow().orElseThrow().recovery(1, recovery());
        uow.rollbackTo(1);
        assertMarksEnd(uow, 1);
        uow.append(hop(ref(3)));
        assertEquals(new FlowEntry.Hop(uow.records().get(1), Optional.empty(), TickingClock.at(3)),
                uow.trail().get(1));
    }

    /**
     * A rollback that an identity interrupts keeps the flow in step with the log it left: the writes and the marks of
     * the entries it already undid are gone, those of the entries still in the log stay, and the same rollback
     * finishes once the identity recovers.
     */
    @Test
    void anInterruptedRollbackKeepsTheFlowInStepWithTheLog() {
        UnitOfWork uow = recording();
        FlowKey<String> kept = FlowKey.of("kept", String.class);
        FlowKey<String> undone = FlowKey.of("undone", String.class);
        EntityRef fragile = ref(new PendingStateIndexTest.FragileId(2));
        uow.append(hop(ref(1)));
        uow.append(hop(fragile));
        CallFlow flow = uow.flow().orElseThrow();
        flow.put(kept, "written by the step of the fragile entity", uow.savepoint());
        uow.append(hop(ref(3)));
        flow.put(undone, "written by the last step", uow.savepoint());
        assertEquals(Optional.of(S.B), uow.pendingState(fragile));
        try {
            PendingStateIndexTest.FragileId.failing = true;
            assertThrows(IllegalStateException.class, () -> uow.rollbackTo(0));
            PendingStateIndexTest.FragileId.failing = false;
            assertMarksEnd(uow, 2);
            assertEquals(Optional.of("written by the step of the fragile entity"), flow.get(kept));
            assertEquals(Optional.empty(), flow.get(undone));
            uow.rollbackTo(0);
            assertMarksEnd(uow, 0);
            assertEquals(Optional.empty(), flow.get(kept));
        } finally {
            PendingStateIndexTest.FragileId.failing = false;
        }
    }

    /** A flow without marks answers none, and truncating them is harmless. */
    @Test
    void aFlowWithoutMarksAnswersNone() {
        CallFlow flow = new CallFlow(new Object());
        flow.truncateMarks(3);
        assertEquals(Optional.empty(), flow.markAt(0));
        assertEquals(Optional.empty(), flow.recoveryAt(0));
    }
}
