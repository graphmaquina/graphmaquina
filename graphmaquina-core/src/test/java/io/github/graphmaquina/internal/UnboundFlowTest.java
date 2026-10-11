package io.github.graphmaquina.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.FlowKey;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A flow that belongs to no call, as the five-argument constructor of {@code CommitContext} gives it to a hook called
 * by hand: it starts empty, has no entity, works on any thread, refuses what every flow refuses, and two of them are
 * equal when they hold equal values.
 */
class UnboundFlowTest {

    private static final FlowKey<String> BATCH = FlowKey.of("batch", String.class);
    private static final FlowKey<Integer> COUNT = FlowKey.of("count", Integer.class);

    @Test
    void anUnboundFlowStartsEmptyAndHasNoEntity() {
        UnboundFlow flow = new UnboundFlow();
        assertEquals(Optional.empty(), flow.get(BATCH));
        assertEquals(Set.of(), flow.keys());
        assertEquals(Optional.empty(), flow.entity());
        assertEquals(Optional.empty(), flow.entity(Object.class));
        assertEquals(Optional.empty(), flow.entity(String.class));
    }

    /** As in a call's flow, keys() follows the order of the write that gave each key its current value. */
    @Test
    void anUnboundFlowPutsReadsAndRemovesValues() {
        UnboundFlow flow = new UnboundFlow();
        flow.put(BATCH, "b-1");
        flow.put(COUNT, 2);
        assertEquals(Optional.of("b-1"), flow.get(BATCH));
        assertEquals(Optional.of(2), flow.get(COUNT));
        assertEquals(List.of(BATCH, COUNT), List.copyOf(flow.keys()));
        flow.put(BATCH, "b-2");
        Set<FlowKey<?>> before = flow.keys();
        assertEquals(List.of(COUNT, BATCH), List.copyOf(before));
        flow.remove(COUNT);
        assertEquals(Optional.empty(), flow.get(COUNT));
        assertEquals(List.of(BATCH), List.copyOf(flow.keys()));
        assertEquals(List.of(COUNT, BATCH), List.copyOf(before));
        assertThrows(UnsupportedOperationException.class, () -> before.add(COUNT));
        assertFalse(flow.keys().iterator().next().visible());
        flow.put(FlowKey.visible("batch", String.class), "b-3");
        assertTrue(flow.keys().iterator().next().visible());
        assertEquals(Optional.of("b-3"), flow.get(BATCH));
    }

    @Test
    void anUnboundFlowRefusesWhatEveryFlowRefuses() {
        UnboundFlow flow = new UnboundFlow();
        @SuppressWarnings("unchecked")
        FlowKey<Object> loose = (FlowKey<Object>) (FlowKey<?>) BATCH;
        assertEquals("key", assertThrows(NullPointerException.class, () -> flow.get(null)).getMessage());
        assertEquals("key", assertThrows(NullPointerException.class, () -> flow.put(null, "b-1")).getMessage());
        assertEquals("value", assertThrows(NullPointerException.class, () -> flow.put(BATCH, null)).getMessage());
        assertEquals("key", assertThrows(NullPointerException.class, () -> flow.remove(null)).getMessage());
        assertEquals("Cannot cast java.lang.Integer to java.lang.String",
                assertThrows(ClassCastException.class, () -> flow.put(loose, 5)).getMessage());
        assertEquals("type", assertThrows(NullPointerException.class, () -> flow.entity(null)).getMessage());
        assertEquals("entity type must be a reference type; use its wrapper class",
                assertThrows(IllegalArgumentException.class, () -> flow.entity(int.class)).getMessage());
        assertEquals(Set.of(), flow.keys());
    }

    /** No owner thread: a write made on one thread is read on another once that thread was joined. */
    @Test
    void anyThreadCanUseAnUnboundFlow() throws InterruptedException {
        UnboundFlow flow = new UnboundFlow();
        Thread other = new Thread(() -> flow.put(BATCH, "b-1"));
        other.start();
        other.join();
        assertEquals(Optional.of("b-1"), flow.get(BATCH));
        flow.remove(BATCH);
        assertEquals(Set.of(), flow.keys());
    }

    /** Equal values under equal keys, in any order: so contexts built by hand compare by their components. */
    @Test
    void twoUnboundFlowsAreEqualWhenTheyHoldEqualValues() {
        UnboundFlow first = new UnboundFlow();
        UnboundFlow second = new UnboundFlow();
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        first.put(BATCH, "b-1");
        assertNotEquals(first, second);
        second.put(COUNT, 1);
        second.put(BATCH, "b-1");
        first.put(COUNT, 1);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        second.put(BATCH, "b-2");
        assertNotEquals(first, second);
        assertNotEquals(new UnboundFlow(), new Object());
    }
}
