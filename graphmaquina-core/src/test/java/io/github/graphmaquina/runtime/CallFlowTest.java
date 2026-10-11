package io.github.graphmaquina.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.TransitionSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The flow of one call, as its unit of work holds it: a list of writes, each made at a position of the log. A read
 * answers the last write of the key; the keys are listed in the order of the write that gave each its current value;
 * a rollback to a savepoint drops the writes made strictly after it, on every branch of {@link UnitOfWork#rollbackTo}.
 */
class CallFlowTest {

    /** States of the fixture machine. */
    enum S { A, B }

    /** Transition identifiers of the fixture machine. */
    enum T { GO }

    private static final FlowKey<String> BATCH = FlowKey.of("batch", String.class);
    private static final FlowKey<Integer> COUNT = FlowKey.of("count", Integer.class);
    private static final FlowKey<String> NOTE = FlowKey.visible("note", String.class);

    private static EntityRef ref(Object id) {
        return new EntityRef(MachineKey.of(Object.class, S.class, T.class), id, new Object());
    }

    private static LogEntry.Hop hop(EntityRef ref) {
        return new LogEntry.Hop(ref, new TransitionSpec<Object, S, T>(S.A, T.GO, S.B,
                Optional.empty(), List.of(), Optional.empty(), List.of(),
                Optional.empty(), Optional.empty()), 0);
    }

    @Test
    void aFlowHoldsTheRootEntityItWasCreatedWith() {
        Object entity = new Object();
        assertSame(entity, new CallFlow(entity).rootEntity());
    }

    @Test
    void aReadAnswersTheLastWriteOfAnEqualKey() {
        CallFlow flow = new CallFlow(new Object());
        assertEquals(Optional.empty(), flow.get(BATCH));
        flow.put(BATCH, "b-1", 0);
        flow.put(COUNT, 1, 0);
        flow.put(FlowKey.visible("batch", String.class), "b-2", 1);
        assertEquals(Optional.of("b-2"), flow.get(BATCH));
        assertEquals(Optional.of(1), flow.get(COUNT));
        flow.remove(BATCH, 1);
        assertEquals(Optional.empty(), flow.get(BATCH));
        assertEquals(Optional.empty(), flow.get(FlowKey.of("batch", CharSequence.class)));
    }

    @Test
    void aValueIsStoredByReference() {
        CallFlow flow = new CallFlow(new Object());
        StringBuilder lines = new StringBuilder("one");
        FlowKey<StringBuilder> key = FlowKey.of("lines", StringBuilder.class);
        flow.put(key, lines, 0);
        lines.append(", two");
        assertSame(lines, flow.get(key).orElseThrow());
        assertEquals("one, two", flow.get(key).orElseThrow().toString());
    }

    /** D5: the keys that hold a value, each at the position of the write that gave it its current value. */
    @Test
    void theKeysFollowTheWriteThatGaveEachItsCurrentValue() {
        CallFlow flow = new CallFlow(new Object());
        assertEquals(List.of(), List.copyOf(flow.keys()));
        flow.put(BATCH, "b-1", 0);
        flow.put(COUNT, 1, 0);
        flow.put(NOTE, "n", 0);
        assertEquals(List.of(BATCH, COUNT, NOTE), List.copyOf(flow.keys()));
        flow.put(BATCH, "b-2", 1);
        assertEquals(List.of(COUNT, NOTE, BATCH), List.copyOf(flow.keys()));
        flow.remove(COUNT, 1);
        assertEquals(List.of(NOTE, BATCH), List.copyOf(flow.keys()));
        flow.remove(FlowKey.of("absent", String.class), 1);
        assertEquals(List.of(NOTE, BATCH), List.copyOf(flow.keys()));
        flow.put(COUNT, 2, 2);
        assertEquals(List.of(NOTE, BATCH, COUNT), List.copyOf(flow.keys()));
    }

    /** The key of the write that set the value is the one listed: a visible key replaces a hidden equal one. */
    @Test
    void theListedKeyIsTheOneOfTheLatestWrite() {
        CallFlow flow = new CallFlow(new Object());
        flow.put(BATCH, "b-1", 0);
        flow.put(FlowKey.visible("batch", String.class), "b-2", 0);
        assertTrue(flow.keys().iterator().next().visible());
        assertTrue(flow.values().keySet().iterator().next().visible());
    }

    @Test
    void theValuesFollowTheOrderOfTheKeys() {
        CallFlow flow = new CallFlow(new Object());
        flow.put(BATCH, "b-1", 0);
        flow.put(COUNT, 1, 0);
        flow.put(BATCH, "b-2", 1);
        Map<FlowKey<?>, Object> expected = new LinkedHashMap<>();
        expected.put(COUNT, 1);
        expected.put(BATCH, "b-2");
        assertEquals(expected, flow.values());
        assertEquals(List.copyOf(flow.keys()), List.copyOf(flow.values().keySet()));
    }

    @Test
    void theKeysAndTheValuesAreUnmodifiable() {
        CallFlow flow = new CallFlow(new Object());
        flow.put(BATCH, "b-1", 0);
        Set<FlowKey<?>> keys = flow.keys();
        Map<FlowKey<?>, Object> values = flow.values();
        assertThrows(UnsupportedOperationException.class, () -> keys.add(COUNT));
        assertThrows(UnsupportedOperationException.class, () -> keys.remove(BATCH));
        assertThrows(UnsupportedOperationException.class, () -> values.put(COUNT, 1));
        assertThrows(UnsupportedOperationException.class, values::clear);
        assertEquals(Optional.of("b-1"), flow.get(BATCH));
    }

    /**
     * Spec R12.4: a rollback to a savepoint drops the writes made at a log position greater than the savepoint, and
     * keeps the ones made at the savepoint itself, which a step before the savepoint made.
     */
    @Test
    void aRollbackDropsOnlyTheWritesMadeStrictlyAfterTheSavepoint() {
        CallFlow flow = new CallFlow(new Object());
        flow.put(BATCH, "b-1", 1);
        flow.put(COUNT, 1, 1);
        flow.put(BATCH, "b-2", 2);
        flow.remove(COUNT, 3);
        flow.rollbackTo(2);
        assertEquals(Optional.of("b-2"), flow.get(BATCH));
        assertEquals(Optional.of(1), flow.get(COUNT));
        assertEquals(List.of(COUNT, BATCH), List.copyOf(flow.keys()));
        flow.rollbackTo(1);
        assertEquals(Optional.of("b-1"), flow.get(BATCH));
        assertEquals(List.of(BATCH, COUNT), List.copyOf(flow.keys()));
        flow.rollbackTo(1);
        assertEquals(List.of(BATCH, COUNT), List.copyOf(flow.keys()));
        flow.rollbackTo(0);
        assertEquals(List.of(), List.copyOf(flow.keys()));
        assertEquals(Optional.empty(), flow.get(BATCH));
    }

    /** After a rollback, writes go on at the log's new positions, and a later rollback still drops only its own. */
    @Test
    void writesAfterARollbackAreRolledBackByTheirOwnSavepoint() {
        CallFlow flow = new CallFlow(new Object());
        flow.put(BATCH, "b-1", 1);
        flow.put(COUNT, 1, 3);
        flow.rollbackTo(1);
        flow.put(COUNT, 2, 1);
        flow.put(NOTE, "n", 2);
        flow.rollbackTo(1);
        assertEquals(List.of(BATCH, COUNT), List.copyOf(flow.keys()));
        assertEquals(Optional.of(2), flow.get(COUNT));
    }

    @Test
    void aUnitOfWorkHasNoFlowUntilTheFirstUse() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        assertFalse(uow.hasFlow());
        uow.append(hop(ref(1)));
        uow.rollbackTo(0);
        assertFalse(uow.hasFlow());
        Object entity = new Object();
        CallFlow flow = uow.flow(entity);
        assertTrue(uow.hasFlow());
        assertSame(entity, flow.rootEntity());
        assertSame(flow, uow.flow(new Object()));
    }

    /** Savepoint 0 before the pending-state index exists: the branch that clears the whole log. */
    @Test
    void aRollbackToZeroWithoutAnIndexRollsTheFlowBack() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        CallFlow flow = uow.flow(new Object());
        flow.put(BATCH, "before", uow.savepoint());
        uow.append(hop(ref(1)));
        flow.put(COUNT, 1, uow.savepoint());
        uow.rollbackTo(0);
        assertEquals(0, uow.savepoint());
        assertEquals(Optional.of("before"), flow.get(BATCH));
        assertEquals(Optional.empty(), flow.get(COUNT));
    }

    /** A savepoint above 0 before the pending-state index exists: the branch that truncates the log. */
    @Test
    void aRollbackWithoutAnIndexRollsTheFlowBack() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        CallFlow flow = uow.flow(new Object());
        uow.append(hop(ref(1)));
        flow.put(BATCH, "kept", uow.savepoint());
        int savepoint = uow.savepoint();
        uow.append(hop(ref(2)));
        flow.put(COUNT, 1, uow.savepoint());
        uow.rollbackTo(savepoint);
        assertEquals(1, uow.savepoint());
        assertEquals(Optional.of("kept"), flow.get(BATCH));
        assertEquals(Optional.empty(), flow.get(COUNT));
    }

    /** Once a lookup built the pending-state index: the branch that undoes the index entry by entry. */
    @Test
    void aRollbackWithAnIndexRollsTheFlowBack() {
        UnitOfWork uow = new UnitOfWork(100, Long.MAX_VALUE);
        CallFlow flow = uow.flow(new Object());
        EntityRef first = ref(1);
        uow.append(hop(first));
        assertEquals(Optional.of(S.B), uow.pendingState(first));
        flow.put(BATCH, "kept", uow.savepoint());
        int savepoint = uow.savepoint();
        uow.append(hop(ref(2)));
        flow.put(COUNT, 1, uow.savepoint());
        uow.rollbackTo(savepoint);
        assertEquals(1, uow.savepoint());
        assertEquals(Optional.of("kept"), flow.get(BATCH));
        assertEquals(Optional.empty(), flow.get(COUNT));
        uow.rollbackTo(0);
        assertEquals(List.of(), List.copyOf(flow.keys()));
    }
}
