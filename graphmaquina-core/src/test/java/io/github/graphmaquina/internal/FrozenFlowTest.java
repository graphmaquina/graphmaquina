package io.github.graphmaquina.internal;

import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.FlowEntry;
import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.Item;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The flow a call hands out, frozen: the root entity, the values and the trail it was built from, copied in their
 * order and never changed afterwards, readable from any thread; it refuses what every flow refuses, and two frozen
 * flows are equal when their entities, their values and their trails are. {@link FlowView#empty()} is the frozen flow
 * without any of them.
 */
class FrozenFlowTest {

    private static final FlowKey<String> BATCH = FlowKey.of("batch", String.class);
    private static final FlowKey<Integer> COUNT = FlowKey.of("count", Integer.class);
    @SuppressWarnings("rawtypes")
    private static final FlowKey<List> LINES = FlowKey.visible("lines", List.class);

    /** A value, or an entity, whose {@code toString()} throws. */
    private static final class Odd {
        @Override
        public String toString() {
            throw new IllegalStateException("no text");
        }
    }

    private static final IllegalStateException FAILURE = new IllegalStateException("reviewer unavailable");

    /** A trail of one hop of {@code doc} at {@code second} seconds after the epoch, then a failure at the next. */
    private static List<FlowEntry> trail(Doc doc, int second) {
        TransitionRecord<Doc, ?, ?> hop = new TransitionRecord<>(DOCS, doc, DRAFT, SUBMIT, REVIEW, TransitionKind.MOVE,
                Optional.empty(), 0);
        CallFailure failure = new CallFailure(CallPhase.ACTION, StatesWritten.NONE, FAILURE);
        return List.of(new FlowEntry.Hop(hop, Optional.empty(), Instant.ofEpochSecond(second)),
                new FlowEntry.Failed(failure, Instant.ofEpochSecond(second + 1)));
    }

    private static Map<FlowKey<?>, Object> values(Object... keysAndValues) {
        Map<FlowKey<?>, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            values.put((FlowKey<?>) keysAndValues[index], keysAndValues[index + 1]);
        }
        return values;
    }

    /** Each value is the very object it was built with, under its key, in the order of the map it was built from. */
    @Test
    void aFrozenFlowAnswersItsValuesInOrderAndItsEntity() {
        Doc doc = new Doc("1", DRAFT);
        List<String> lines = new ArrayList<>(List.of("reserved"));
        FrozenFlow flow = new FrozenFlow(Optional.of(doc), values(COUNT, 2, BATCH, "b-1", LINES, lines));
        assertEquals(Optional.of(2), flow.get(COUNT));
        assertEquals(Optional.of("b-1"), flow.get(BATCH));
        assertSame(lines, flow.get(LINES).orElseThrow());
        assertEquals(Optional.empty(), flow.get(FlowKey.of("missing", String.class)));
        assertEquals(List.of(COUNT, BATCH, LINES), List.copyOf(flow.keys()));
        assertSame(doc, flow.entity().orElseThrow());
        assertSame(doc, flow.entity(Doc.class).orElseThrow());
        assertSame(doc, flow.entity(Object.class).orElseThrow());
    }

    /** The values are copied: a later change to the map it was built from changes nothing, and keys() is read-only. */
    @Test
    void aFrozenFlowCopiesItsValuesAndNeverChanges() {
        Map<FlowKey<?>, Object> source = values(BATCH, "b-1");
        FrozenFlow flow = new FrozenFlow(Optional.empty(), source);
        source.put(COUNT, 3);
        source.remove(BATCH);
        assertEquals(Optional.of("b-1"), flow.get(BATCH));
        assertEquals(Optional.empty(), flow.get(COUNT));
        Set<FlowKey<?>> keys = flow.keys();
        assertThrows(UnsupportedOperationException.class, () -> keys.add(COUNT));
        assertThrows(UnsupportedOperationException.class, () -> keys.remove(BATCH));
        assertEquals(List.of(BATCH), List.copyOf(flow.keys()));
    }

    @Test
    void aFrozenFlowRefusesWhatEveryFlowRefuses() {
        FrozenFlow flow = new FrozenFlow(Optional.of(new Doc("1", DRAFT)), values(BATCH, "b-1"));
        assertEquals("key", assertThrows(NullPointerException.class, () -> flow.get(null)).getMessage());
        assertEquals("type", assertThrows(NullPointerException.class, () -> flow.entity(null)).getMessage());
        assertEquals("entity type must be a reference type; use its wrapper class",
                assertThrows(IllegalArgumentException.class, () -> flow.entity(int.class)).getMessage());
        assertEquals("Cannot cast " + Doc.class.getName() + " to " + Item.class.getName(),
                assertThrows(ClassCastException.class, () -> flow.entity(Item.class)).getMessage());
    }

    /**
     * It is built from what a call's flow holds, so it refuses what no flow can hold: a null entity, map, key or value,
     * and a value that is not an instance of its key's class.
     */
    @Test
    void aFrozenFlowRefusesNullsAndAValueOfAnotherClass() {
        Map<FlowKey<?>, Object> nullKey = new HashMap<>();
        nullKey.put(null, "b-1");
        assertEquals("entity", assertThrows(NullPointerException.class,
                () -> new FrozenFlow(null, values(BATCH, "b-1"))).getMessage());
        assertEquals("values", assertThrows(NullPointerException.class,
                () -> new FrozenFlow(Optional.empty(), null)).getMessage());
        assertEquals("key", assertThrows(NullPointerException.class,
                () -> new FrozenFlow(Optional.empty(), nullKey)).getMessage());
        assertEquals("value", assertThrows(NullPointerException.class,
                () -> new FrozenFlow(Optional.empty(), values(BATCH, null))).getMessage());
        assertEquals("Cannot cast java.lang.Integer to java.lang.String", assertThrows(ClassCastException.class,
                () -> new FrozenFlow(Optional.empty(), values(BATCH, 5))).getMessage());
    }

    /** The empty view: no value, no entity, the same instance every time, and it still refuses a bad class token. */
    @Test
    void theEmptyViewHasNoValueAndNoEntity() {
        FlowView empty = FlowView.empty();
        assertSame(FrozenFlow.EMPTY, empty);
        assertSame(empty, FlowView.empty());
        assertEquals(Optional.empty(), empty.get(BATCH));
        assertEquals(Set.of(), empty.keys());
        assertEquals(Optional.empty(), empty.entity());
        assertEquals(Optional.empty(), empty.entity(Doc.class));
        assertEquals("type", assertThrows(NullPointerException.class, () -> empty.entity(null)).getMessage());
        assertEquals("entity type must be a reference type; use its wrapper class",
                assertThrows(IllegalArgumentException.class, () -> empty.entity(int.class)).getMessage());
    }

    /**
     * Spec D6 and R6.4: equal when the entities are equal and the values are, in any order, with the same hash code;
     * the empty view equals every frozen flow without entity and values. A flow of another class is never equal.
     */
    @Test
    void twoFrozenFlowsAreEqualWhenTheirEntitiesAndTheirValuesAre() {
        Doc doc = new Doc("1", DRAFT);
        FrozenFlow first = new FrozenFlow(Optional.of(doc), values(BATCH, "b-1", COUNT, 2));
        FrozenFlow second = new FrozenFlow(Optional.of(doc), values(COUNT, 2, BATCH, "b-1"));
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, new FrozenFlow(Optional.of(doc), values(BATCH, "b-1", COUNT, 3)));
        assertNotEquals(first, new FrozenFlow(Optional.of(doc), values(BATCH, "b-1")));
        assertNotEquals(first, new FrozenFlow(Optional.of(new Doc("1", DRAFT)), values(BATCH, "b-1", COUNT, 2)));
        assertNotEquals(first, new FrozenFlow(Optional.empty(), values(BATCH, "b-1", COUNT, 2)));
        assertEquals(FlowView.empty(), new FrozenFlow(Optional.empty(), Map.of()));
        assertEquals(FlowView.empty().hashCode(), new FrozenFlow(Optional.empty(), Map.of()).hashCode());
        assertNotEquals(FlowView.empty(), new FrozenFlow(Optional.of(doc), Map.of()));
        UnboundFlow unbound = new UnboundFlow();
        unbound.put(BATCH, "b-1");
        assertNotEquals(new FrozenFlow(Optional.empty(), values(BATCH, "b-1")), unbound);
    }

    /** No owner thread: another thread reads every value, the keys and the entity. */
    @Test
    void anyThreadCanReadAFrozenFlow() throws InterruptedException {
        Doc doc = new Doc("1", DRAFT);
        FrozenFlow flow = new FrozenFlow(Optional.of(doc), values(BATCH, "b-1"));
        List<Object> read = new ArrayList<>();
        Thread other = new Thread(() -> {
            read.add(flow.get(BATCH));
            read.add(List.copyOf(flow.keys()));
            read.add(flow.entity(Doc.class).orElseThrow());
        });
        other.start();
        other.join();
        assertEquals(List.of(Optional.of("b-1"), List.of(BATCH), doc), read);
    }

    /** toString() names the keys as name:Type and never prints a value or the entity, which may not print at all. */
    @Test
    void toStringNamesTheKeysAndNeverAValueOrTheEntity() {
        FlowKey<Odd> odd = FlowKey.visible("odd", Odd.class);
        FrozenFlow flow = new FrozenFlow(Optional.of(new Odd()), values(BATCH, "b-1", odd, new Odd()));
        assertEquals("FrozenFlow[keys=[batch:String, odd:Odd]]", flow.toString());
        assertEquals("FrozenFlow[keys=[]]", FlowView.empty().toString());
        assertEquals("FrozenFlow[keys=[batch:String]]",
                new FrozenFlow(Optional.of(new Odd()), values(BATCH, "b-1"), trail(new Doc("1", DRAFT), 1)).toString());
    }

    /**
     * The trail is copied, in its order, and unmodifiable; any thread reads it. A frozen flow built without a trail,
     * and the empty view, have an empty one.
     */
    @Test
    void aFrozenFlowHoldsACopyOfItsTrail() throws InterruptedException {
        Doc doc = new Doc("1", DRAFT);
        List<FlowEntry> source = new ArrayList<>(trail(doc, 1));
        FrozenFlow flow = new FrozenFlow(Optional.of(doc), values(BATCH, "b-1"), source);
        source.clear();
        assertEquals(trail(doc, 1), flow.trail());
        assertThrows(UnsupportedOperationException.class, () -> flow.trail().clear());
        List<Object> read = new ArrayList<>();
        Thread other = new Thread(() -> read.add(flow.trail()));
        other.start();
        other.join();
        assertEquals(List.of(trail(doc, 1)), read);
        assertEquals(List.of(), new FrozenFlow(Optional.of(doc), values(BATCH, "b-1")).trail());
        assertEquals(List.of(), FlowView.empty().trail());
    }

    @Test
    void aFrozenFlowRefusesANullTrailOrEntry() {
        List<FlowEntry> nullEntry = new ArrayList<>();
        nullEntry.add(null);
        assertEquals("trail", assertThrows(NullPointerException.class,
                () -> new FrozenFlow(Optional.empty(), Map.of(), null)).getMessage());
        assertEquals("entry", assertThrows(NullPointerException.class,
                () -> new FrozenFlow(Optional.empty(), Map.of(), nullEntry)).getMessage());
    }

    /**
     * Spec D6: two frozen flows with equal entities and values are equal only when their trails are, entry by entry,
     * and then their hash codes are too. The empty view equals no flow with a trail.
     */
    @Test
    void twoFrozenFlowsAreEqualOnlyWithEqualTrails() {
        Doc doc = new Doc("1", DRAFT);
        FrozenFlow first = new FrozenFlow(Optional.of(doc), values(BATCH, "b-1"), trail(doc, 1));
        FrozenFlow second = new FrozenFlow(Optional.of(doc), values(BATCH, "b-1"), trail(doc, 1));
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, new FrozenFlow(Optional.of(doc), values(BATCH, "b-1"), trail(doc, 2)));
        assertNotEquals(first, new FrozenFlow(Optional.of(doc), values(BATCH, "b-1")));
        assertNotEquals(first, new FrozenFlow(Optional.of(doc), values(BATCH, "b-1"), trail(doc, 1).subList(0, 1)));
        assertNotEquals(FlowView.empty(), new FrozenFlow(Optional.empty(), Map.of(), trail(doc, 1)));
        assertEquals(FlowView.empty(), new FrozenFlow(Optional.empty(), Map.of(), List.of()));
    }
}
