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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Random;
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
     * The pending state after a rollback is the one an earlier entry gave the entity, B, not the origin of the
     * discarded entry, C. No lookup precedes the rollback, so it truncates the log and the first lookup indexes what
     * remains.
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
    void aRootThatNeverAppendsStillRejectsInvalidSavepointsAndAcceptsZero() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        assertThrows(IndexOutOfBoundsException.class, () -> uow.rollbackTo(-1));
        assertThrows(IllegalArgumentException.class, () -> uow.rollbackTo(1));
        uow.rollbackTo(0);
        assertEquals(Optional.empty(), uow.pendingState(ref(1)));
        assertEquals(0, uow.savepoint());
        assertEquals(List.of(), uow.entries());
    }

    @Test
    void theFirstAppendAfterARollbackToZeroStartsAFreshIndex() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        EntityRef a = ref(1);
        uow.append(hop(a, S.A, S.B));
        uow.rollbackTo(0);
        assertEquals(Optional.empty(), uow.pendingState(a));
        uow.append(hop(a, S.B, S.C));
        assertEquals(Optional.of(S.C), uow.pendingState(a));
        assertEquals(1, uow.savepoint());
        uow.rollbackTo(0);
        assertEquals(Optional.empty(), uow.pendingState(a));
        assertEquals(0, uow.savepoint());
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

    @Test
    void aRollbackBeforeAnyLookupOnlyTruncatesTheLog() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        EntityRef a = ref(1);
        EntityRef b = ref(2);
        LogEntry first = hop(a, S.A, S.B);
        uow.append(first);
        uow.append(hop(b, S.A, S.C));
        uow.append(hop(a, S.B, S.C));
        uow.rollbackTo(1);
        assertEquals(Optional.of(S.B), uow.pendingState(a));
        assertEquals(Optional.empty(), uow.pendingState(b));
        assertEquals(List.of(first), uow.entries());
        assertEquals(1, uow.savepoint());
    }

    /**
     * Hops appended through one reference and through an equal but distinct one move the same entity, both for
     * a first lookup that comes after all of them and for the appends and nested rollbacks that follow it.
     */
    @Test
    void theFirstLookupAfterSeveralAppendsSeesTheLastHopOfEachEntity() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        EntityRef a = new EntityRef(FIRST, 1, new Object());
        EntityRef sameAsA = new EntityRef(FIRST, 1, new Object());
        EntityRef b = new EntityRef(FIRST, 2, new Object());
        EntityRef c = new EntityRef(FIRST, 3, new Object());
        uow.append(hop(a, S.A, S.B));
        uow.append(hop(b, S.A, S.C));
        uow.append(hop(sameAsA, S.B, S.C));
        uow.append(hop(a, S.C, S.A));
        uow.append(hop(b, S.C, S.B));
        assertEquals(Optional.of(S.A), uow.pendingState(sameAsA));
        assertEquals(Optional.of(S.A), uow.pendingState(a));
        assertEquals(Optional.of(S.B), uow.pendingState(b));
        assertEquals(Optional.empty(), uow.pendingState(c));
        uow.append(hop(sameAsA, S.A, S.C));
        int outer = uow.savepoint();
        uow.append(hop(b, S.B, S.A));
        int inner = uow.savepoint();
        uow.append(hop(a, S.C, S.B));
        uow.append(hop(c, S.A, S.B));
        assertEquals(Optional.of(S.B), uow.pendingState(sameAsA));
        assertEquals(Optional.of(S.A), uow.pendingState(b));
        assertEquals(Optional.of(S.B), uow.pendingState(c));
        uow.rollbackTo(inner);
        assertEquals(Optional.of(S.C), uow.pendingState(a));
        assertEquals(Optional.of(S.A), uow.pendingState(b));
        assertEquals(Optional.empty(), uow.pendingState(c));
        uow.rollbackTo(outer);
        assertEquals(Optional.of(S.C), uow.pendingState(sameAsA));
        assertEquals(Optional.of(S.B), uow.pendingState(b));
        assertEquals(Optional.empty(), uow.pendingState(c));
        uow.rollbackTo(0);
        assertEquals(Optional.empty(), uow.pendingState(a));
        assertEquals(Optional.empty(), uow.pendingState(b));
        assertEquals(0, uow.savepoint());
    }

    @Test
    void invalidSavepointsAreRejectedBeforeAndAfterTheFirstLookup() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        EntityRef a = ref(1);
        EntityRef b = ref(2);
        LogEntry first = hop(a, S.A, S.B);
        LogEntry second = hop(b, S.A, S.C);
        uow.append(first);
        uow.append(second);
        assertThrows(IndexOutOfBoundsException.class, () -> uow.rollbackTo(-1));
        assertThrows(IllegalArgumentException.class, () -> uow.rollbackTo(3));
        assertEquals(List.of(first, second), uow.entries());
        assertEquals(2, uow.savepoint());
        assertEquals(Optional.of(S.B), uow.pendingState(a));
        assertEquals(Optional.of(S.C), uow.pendingState(b));
        assertThrows(IndexOutOfBoundsException.class, () -> uow.rollbackTo(-1));
        assertThrows(IllegalArgumentException.class, () -> uow.rollbackTo(3));
        assertEquals(List.of(first, second), uow.entries());
        assertEquals(2, uow.savepoint());
        assertEquals(Optional.of(S.B), uow.pendingState(a));
        assertEquals(Optional.of(S.C), uow.pendingState(b));
        uow.rollbackTo(1);
        assertEquals(Optional.of(S.B), uow.pendingState(a));
        assertEquals(Optional.empty(), uow.pendingState(b));
    }

    /**
     * An identity whose hashCode throws may do so while the first lookup builds the index or while an append
     * maintains it. Nothing half-done survives either way: the failed build publishes nothing, so the next lookup
     * rebuilds from the log, and the failed append leaves the log, the index and undo as they were.
     */
    @Test
    void anIdentityThatThrowsLeavesTheIndexConsistent() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        EntityRef a = ref(1);
        EntityRef fragile = ref(new FragileId(2));
        EntityRef b = ref(3);
        LogEntry first = hop(a, S.A, S.B);
        LogEntry second = hop(fragile, S.A, S.C);
        LogEntry third = hop(b, S.A, S.B);
        uow.append(first);
        uow.append(second);
        uow.append(third);
        try {
            FragileId.failing = true;
            assertThrows(IllegalStateException.class, () -> uow.pendingState(a));
            FragileId.failing = false;
            assertEquals(Optional.of(S.B), uow.pendingState(a));
            assertEquals(Optional.of(S.C), uow.pendingState(fragile));
            assertEquals(Optional.of(S.B), uow.pendingState(b));
            assertEquals(List.of(first, second, third), uow.entries());

            FragileId.failing = true;
            assertThrows(IllegalStateException.class, () -> uow.append(hop(fragile, S.C, S.A)));
            FragileId.failing = false;
            assertEquals(List.of(first, second, third), uow.entries());
            assertEquals(3, uow.savepoint());
            assertEquals(Optional.of(S.B), uow.pendingState(a));
            assertEquals(Optional.of(S.C), uow.pendingState(fragile));
            assertEquals(Optional.of(S.B), uow.pendingState(b));

            uow.append(hop(fragile, S.C, S.A));
            assertEquals(Optional.of(S.A), uow.pendingState(fragile));
            uow.rollbackTo(1);
            assertEquals(List.of(first), uow.entries());
            assertEquals(Optional.of(S.B), uow.pendingState(a));
            assertEquals(Optional.empty(), uow.pendingState(fragile));
            assertEquals(Optional.empty(), uow.pendingState(b));
        } finally {
            FragileId.failing = false;
        }
    }

    /**
     * A rollback undoes the log one entry at a time and an identity may throw at any of them, whether that entry is
     * undone by removing the entity's pending state or by putting the earlier one back. The entries already undone
     * stay undone, the failing one stays whole, and the same rollback succeeds once the identity recovers.
     */
    @Test
    void aRollbackInterruptedByAThrowingIdentityCanBeRetried() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        EntityRef a = ref(1);
        EntityRef fragile = ref(new FragileId(2));
        EntityRef b = ref(3);
        LogEntry first = hop(a, S.A, S.B);
        LogEntry second = hop(fragile, S.A, S.C);
        uow.append(first);
        uow.append(second);
        uow.append(hop(b, S.A, S.B));
        assertEquals(Optional.of(S.C), uow.pendingState(fragile));
        try {
            FragileId.failing = true;
            assertThrows(IllegalStateException.class, () -> uow.rollbackTo(0));
            FragileId.failing = false;
            assertEquals(List.of(first, second), uow.entries());
            assertEquals(2, uow.savepoint());
            assertEquals(Optional.of(S.B), uow.pendingState(a));
            assertEquals(Optional.of(S.C), uow.pendingState(fragile));
            assertEquals(Optional.empty(), uow.pendingState(b));
            uow.rollbackTo(0);
            assertEquals(List.of(), uow.entries());
            assertEquals(Optional.empty(), uow.pendingState(a));
            assertEquals(Optional.empty(), uow.pendingState(fragile));

            LogEntry fragileFirst = hop(fragile, S.A, S.C);
            LogEntry fragileSecond = hop(fragile, S.C, S.B);
            uow.append(fragileFirst);
            uow.append(fragileSecond);
            uow.append(hop(a, S.A, S.B));
            FragileId.failing = true;
            assertThrows(IllegalStateException.class, () -> uow.rollbackTo(1));
            FragileId.failing = false;
            assertEquals(List.of(fragileFirst, fragileSecond), uow.entries());
            assertEquals(2, uow.savepoint());
            assertEquals(Optional.of(S.B), uow.pendingState(fragile));
            assertEquals(Optional.empty(), uow.pendingState(a));
            uow.rollbackTo(1);
            assertEquals(List.of(fragileFirst), uow.entries());
            assertEquals(Optional.of(S.C), uow.pendingState(fragile));
        } finally {
            FragileId.failing = false;
        }
    }

    /**
     * The log is the specification: a pending state is the to of the last entry for an equal reference, found by
     * scanning the log from the end. Each fixed seed drives appends, lookups, savepoints and rollbacks, some to
     * savepoints that are no longer or never were valid, through equal but distinct references and identities
     * that share one hash. Lookups are withheld for a random prefix of each sequence, so the first lookup meets
     * logs of many lengths, rolled back or not.
     */
    @Test
    void theIndexMatchesAPlainLogScanOnRandomSequences() {
        Object[] ids = { 1, 2, new CollidingId(1), new CollidingId(2), new CollidingId(3) };
        S[] states = S.values();
        for (long seed = 0; seed < 200; seed++) {
            Random random = new Random(seed);
            UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
            List<LogEntry> log = new ArrayList<>();
            Deque<Integer> savepoints = new ArrayDeque<>();
            EntityRef[] canonical = new EntityRef[ids.length];
            for (int i = 0; i < ids.length; i++) {
                canonical[i] = new EntityRef(FIRST, ids[i], new Object());
            }
            int lookupsFrom = random.nextInt(150);
            for (int step = 0; step < 300; step++) {
                String where = "seed " + seed + ", step " + step;
                int which = random.nextInt(ids.length);
                EntityRef ref = random.nextBoolean()
                        ? canonical[which]
                        : new EntityRef(FIRST, ids[which], new Object());
                int dice = random.nextInt(10);
                if (dice < 4) {
                    S from = states[random.nextInt(states.length)];
                    S to = states[random.nextInt(states.length)];
                    LogEntry entry = hop(ref, from, to);
                    uow.append(entry);
                    log.add(entry);
                } else if (dice < 6) {
                    if (step >= lookupsFrom) {
                        assertEquals(lastHop(log, ref), uow.pendingState(ref), where);
                    }
                } else if (dice < 8) {
                    savepoints.push(uow.savepoint());
                } else {
                    int target = savepoints.isEmpty() || random.nextBoolean()
                            ? random.nextInt(log.size() + 3) - 1
                            : savepoints.pop();
                    assertEquals(truncate(log, target), thrownBy(uow, target), where + ", rollbackTo(" + target + ")");
                }
                assertEquals(log.size(), uow.savepoint(), where);
                assertEquals(log, uow.entries(), where);
            }
            for (Object id : ids) {
                EntityRef ref = new EntityRef(FIRST, id, new Object());
                assertEquals(lastHop(log, ref), uow.pendingState(ref), "seed " + seed + ", final lookup of " + id);
            }
        }
    }

    /** The answer of a plain log: the to of its last entry for an equal reference. */
    static Optional<Enum<?>> lastHop(List<LogEntry> log, EntityRef ref) {
        for (int i = log.size() - 1; i >= 0; i--) {
            if (log.get(i).ref().equals(ref)) {
                return Optional.of(log.get(i).to());
            }
        }
        return Optional.empty();
    }

    /** A plain log rolled back: the checks of subList, then the truncation. Null when the savepoint is valid. */
    static Class<? extends RuntimeException> truncate(List<LogEntry> log, int savepoint) {
        if (savepoint < 0) {
            return IndexOutOfBoundsException.class;
        }
        if (savepoint > log.size()) {
            return IllegalArgumentException.class;
        }
        log.subList(savepoint, log.size()).clear();
        return null;
    }

    /** The exact class of what rollbackTo threw, or null when it returned. */
    static Class<? extends RuntimeException> thrownBy(UnitOfWork uow, int savepoint) {
        try {
            uow.rollbackTo(savepoint);
            return null;
        } catch (RuntimeException e) {
            return e.getClass();
        }
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

    /** Identity value whose hashCode throws while failing is set, the way a broken identity might. */
    record FragileId(int value) {

        static boolean failing;

        @Override
        public int hashCode() {
            if (failing) {
                throw new IllegalStateException("hashCode of FragileId " + value + " failed");
            }
            return value;
        }
    }
}
