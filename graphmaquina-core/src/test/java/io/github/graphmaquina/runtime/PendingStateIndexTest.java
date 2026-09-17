package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.error.AmbiguousIdentityException;
import io.github.graphmaquina.error.StateMachineException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The pending state a UnitOfWork answers with, hop by hop and rollback by rollback. The log is built here by
 * hand, so absence and an earlier pending state stay distinguishable even where the runtime would make the
 * origin of the discarded edge coincide with the value to restore.
 */
class PendingStateIndexTest {

    /** States of the fixture machine. */
    enum S { A, B, C }

    /** Transition identifiers of the fixture machine. */
    enum T { GO }

    static final MachineKey<Object, S, T> FIRST = MachineKey.of(Object.class, S.class, T.class);
    static final MachineKey<String, S, T> SECOND = MachineKey.of(String.class, S.class, T.class);

    static EntityRef ref(Object id) {
        return new EntityRef(MachineKey.of(Object.class, S.class, T.class), id, new Object());
    }

    static LogEntry hop(EntityRef ref, S from, S to) {
        return new LogEntry(ref, new TransitionSpec<Object, S, T>(from, T.GO, to,
                Optional.empty(), List.of(), Optional.empty(), List.of(),
                Optional.empty(), Optional.empty()), 0);
    }

    @Test
    void restoresPresenceAndEarlierStateAfterNestedRollback() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        EntityRef a = ref(1);
        EntityRef b = ref(2);
        uow.append(hop(a, S.A, S.B));
        int mark = uow.savepoint();
        uow.append(hop(b, S.A, S.C));
        uow.append(hop(a, S.B, S.C));
        uow.rollbackTo(mark);
        assertEquals(Optional.of(S.B), uow.pendingState(a));
        assertEquals(Optional.empty(), uow.pendingState(b));
        uow.rollbackTo(mark);
        uow.rollbackTo(0);
        assertEquals(Optional.empty(), uow.pendingState(a));
        uow.append(hop(a, S.A, S.A));
        assertEquals(Optional.of(S.A), uow.pendingState(a));
    }

    /**
     * The value half of the same invariant: the discarded entry leaves C and the value to restore is B, so an
     * undo that read {@code entry.from()} would answer C here while every other case in this file cannot tell.
     */
    @Test
    void theRestoredValueIsTheEarlierPendingStateNotTheDiscardedOrigin() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        EntityRef a = ref(1);
        uow.append(hop(a, S.A, S.B));
        int mark = uow.savepoint();
        uow.append(hop(a, S.C, S.A));
        uow.rollbackTo(mark);
        assertEquals(Optional.of(S.B), uow.pendingState(a));
    }

    @Test
    void noEntityIsPendingBeforeTheFirstAppend() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        assertEquals(Optional.empty(), uow.pendingState(ref(1)));
        assertEquals(List.of(), uow.entries());
        assertEquals(0, uow.savepoint());
    }

    @Test
    void theSameIdInTwoMachinesIsTwoEntities() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        EntityRef first = new EntityRef(FIRST, "shared", new Object());
        EntityRef second = new EntityRef(SECOND, "shared", new Object());
        uow.append(hop(first, S.A, S.B));
        assertEquals(Optional.of(S.B), uow.pendingState(first));
        assertEquals(Optional.empty(), uow.pendingState(second));
    }

    @Test
    void anEquivalentReferenceReadsTheSameEntityAndIdentityStaysUnambiguous() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        Object instance = new Object();
        EntityRef canonical = uow.register(new EntityRef(FIRST, 1, instance));
        uow.append(hop(canonical, S.A, S.B));
        assertEquals(Optional.of(S.B), uow.pendingState(new EntityRef(FIRST, 1, new Object())));
        assertSame(canonical, uow.register(new EntityRef(FIRST, 1, instance)));
        assertThrows(AmbiguousIdentityException.class, () -> uow.register(new EntityRef(FIRST, 1, new Object())));
    }

    @Test
    void interleavedEntitiesKeepTheirOwnPendingState() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        EntityRef a = ref(1);
        EntityRef b = ref(2);
        uow.append(hop(a, S.A, S.B));
        uow.append(hop(b, S.A, S.C));
        uow.append(hop(a, S.B, S.C));
        uow.append(hop(b, S.C, S.A));
        assertEquals(Optional.of(S.C), uow.pendingState(a));
        assertEquals(Optional.of(S.A), uow.pendingState(b));
    }

    @Test
    void aRoundTripEndsWhereItsLastHopEndsAndUnwindsHopByHop() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        EntityRef a = ref(1);
        uow.append(hop(a, S.A, S.B));
        int afterFirst = uow.savepoint();
        uow.append(hop(a, S.B, S.A));
        assertEquals(Optional.of(S.A), uow.pendingState(a));
        uow.rollbackTo(afterFirst);
        assertEquals(Optional.of(S.B), uow.pendingState(a));
        uow.rollbackTo(0);
        assertEquals(Optional.empty(), uow.pendingState(a));
    }

    @Test
    void aNewBranchAfterARollbackReplacesTheDiscardedOne() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        EntityRef a = ref(1);
        EntityRef b = ref(2);
        uow.append(hop(a, S.A, S.B));
        int mark = uow.savepoint();
        uow.append(hop(b, S.A, S.B));
        uow.append(hop(a, S.B, S.C));
        uow.rollbackTo(mark);
        uow.append(hop(a, S.B, S.A));
        assertEquals(Optional.of(S.A), uow.pendingState(a));
        assertEquals(Optional.empty(), uow.pendingState(b));
        assertEquals(2, uow.entries().size());
        assertEquals(S.A, uow.entries().get(1).to());
    }

    @Test
    void anInvalidSavepointLeavesTheStateUnaltered() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        EntityRef a = ref(1);
        uow.append(hop(a, S.A, S.B));
        assertThrows(IndexOutOfBoundsException.class, () -> uow.rollbackTo(-1));
        assertThrows(IllegalArgumentException.class, () -> uow.rollbackTo(2));
        assertEquals(Optional.of(S.B), uow.pendingState(a));
        assertEquals(1, uow.entries().size());
        uow.rollbackTo(1);
        assertEquals(Optional.of(S.B), uow.pendingState(a));
        uow.rollbackTo(0);
        assertEquals(Optional.empty(), uow.pendingState(a));
    }

    @Test
    void aRollbackTouchesNeitherIdentitiesNorJournalNorInFlightNorPoison() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        Object instance = new Object();
        EntityRef a = uow.register(new EntityRef(FIRST, 1, instance));
        uow.push(a, hop(a, S.A, S.B).spec());
        uow.appendJournal(new ActionRecord(FIRST, instance, S.A, T.GO, S.B, "act", 0, ActionStatus.RUNNING));
        uow.poison(new StateMachineException("boom"));
        int mark = uow.savepoint();
        uow.append(hop(a, S.A, S.B));
        uow.rollbackTo(mark);
        assertEquals(Optional.empty(), uow.pendingState(a));
        assertSame(a, uow.register(new EntityRef(FIRST, 1, instance)));
        assertEquals(1, uow.journal().size());
        assertTrue(uow.inFlight(a).isPresent());
        assertTrue(uow.isPoisoned());
    }

    @Test
    void aQueryComparesFewerThanThirtyTwoIdentities() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        for (int i = 0; i < 1000; i++) {
            uow.append(hop(ref(new CountedId(i)), S.A, S.B));
        }
        CountedId.comparisons = 0;
        Optional<Enum<?>> absent = uow.pendingState(ref(new CountedId(-1)));
        int absentComparisons = CountedId.comparisons;
        CountedId.comparisons = 0;
        Optional<Enum<?>> first = uow.pendingState(ref(new CountedId(0)));
        int firstComparisons = CountedId.comparisons;
        assertEquals(Optional.empty(), absent);
        assertEquals(Optional.of(S.B), first);
        assertTrue(absentComparisons < 32, "an absent id cost " + absentComparisons + " identity comparisons");
        assertTrue(firstComparisons < 32, "the first id cost " + firstComparisons + " identity comparisons");
    }

    @Test
    void identitiesThatShareOneHashStillResolveCorrectly() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        EntityRef first = ref(new CollidingId(1));
        EntityRef second = ref(new CollidingId(2));
        uow.append(hop(first, S.A, S.B));
        uow.append(hop(second, S.A, S.C));
        int mark = uow.savepoint();
        uow.append(hop(ref(new CollidingId(3)), S.A, S.B));
        uow.append(hop(second, S.C, S.A));
        uow.rollbackTo(mark);
        assertEquals(Optional.of(S.B), uow.pendingState(ref(new CollidingId(1))));
        assertEquals(Optional.of(S.C), uow.pendingState(ref(new CollidingId(2))));
        assertEquals(Optional.empty(), uow.pendingState(ref(new CollidingId(3))));
    }

    /** Identity value with one hash per value that counts every equality comparison made against it. */
    static final class CountedId {

        static int comparisons;

        private final int value;

        CountedId(int value) { this.value = value; }

        @Override
        public int hashCode() { return value; }

        @Override
        public boolean equals(Object other) {
            comparisons++;
            return other instanceof CountedId that && value == that.value;
        }
    }

    /** Identity value whose hash is the same for every value: the answer must stay correct, whatever it costs. */
    record CollidingId(int value) {

        @Override
        public int hashCode() { return 7; }
    }
}
