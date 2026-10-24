package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.StateChange;
import io.github.graphmaquina.test.StateSnapshot;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.junit.jupiter.api.Test;

/**
 * Contract of the entity state snapshot. Tracking is explicit, so an entity nobody named can move
 * freely; a captured value is frozen while every later read goes back to the entity; a getter that
 * answers null observes no state, reported as an empty state and never as null, which is a value like
 * any other; and the failure of {@code assertUnchanged} is read off the fields of the
 * {@link GraphAssertionError} rather than off its type alone.
 */
class StateSnapshotTest {

    // --- the premise of this task: only what was tracked is reported --------------------------------

    @Test
    void snapshotReportsOnlyTrackedStateChanges() {
        Entity first = new Entity("first", State.NEW);
        Entity second = new Entity("second", State.NEW);
        StateSnapshot snapshot = StateSnapshot.builder()
                .track(TestDomain.KEY, first, Entity::state)
                .track(TestDomain.KEY, second, Entity::state)
                .capture();
        second.setState(State.READY);
        assertEquals(List.of(new StateChange(TestDomain.KEY, second, Optional.of(State.NEW),
                Optional.of(State.READY))), snapshot.changes());
        assertThrows(GraphAssertionError.class, snapshot::assertUnchanged);
    }

    @Test
    void anEntityThatWasNeverTrackedIsInvisible() {
        Entity tracked = new Entity("tracked", State.NEW);
        Entity untracked = new Entity("untracked", State.NEW);
        StateSnapshot snapshot = StateSnapshot.builder()
                .track(TestDomain.KEY, tracked, Entity::state).capture();
        untracked.setState(State.READY);
        assertEquals(List.of(), snapshot.changes());
        snapshot.assertUnchanged();
    }

    // --- when the values are read -------------------------------------------------------------------

    @Test
    void everyCallReadsTheEntityAgain() {
        Entity entity = new Entity("e", State.NEW);
        StateSnapshot snapshot = StateSnapshot.builder()
                .track(TestDomain.KEY, entity, Entity::state).capture();
        assertEquals(List.of(), snapshot.changes());
        entity.setState(State.READY);
        assertEquals(List.of(new StateChange(TestDomain.KEY, entity, Optional.of(State.NEW),
                Optional.of(State.READY))), snapshot.changes());
        entity.setState(State.NEW);
        assertEquals(List.of(), snapshot.changes());
        snapshot.assertUnchanged();
    }

    @Test
    void captureFreezesTheValueItRead() {
        Entity entity = new Entity("e", State.NEW);
        StateSnapshot before = StateSnapshot.builder()
                .track(TestDomain.KEY, entity, Entity::state).capture();
        entity.setState(State.READY);
        StateSnapshot after = StateSnapshot.builder()
                .track(TestDomain.KEY, entity, Entity::state).capture();
        assertEquals(List.of(new StateChange(TestDomain.KEY, entity, Optional.of(State.NEW),
                Optional.of(State.READY))), before.changes());
        assertEquals(List.of(), after.changes());
    }

    @Test
    void captureFreezesTheMembershipItWasGiven() {
        Entity first = new Entity("first", State.NEW);
        Entity second = new Entity("second", State.NEW);
        StateSnapshot.Builder builder = StateSnapshot.builder().track(TestDomain.KEY, first, Entity::state);
        StateSnapshot one = builder.capture();
        StateSnapshot two = builder.track(TestDomain.KEY, second, Entity::state).capture();
        second.setState(State.READY);
        assertEquals(List.of(), one.changes());
        assertEquals(List.of(new StateChange(TestDomain.KEY, second, Optional.of(State.NEW),
                Optional.of(State.READY))), two.changes());
    }

    @Test
    void changesAreImmutableAndInTrackingOrder() {
        Entity first = new Entity("first", State.NEW);
        Entity second = new Entity("second", State.NEW);
        StateSnapshot snapshot = StateSnapshot.builder()
                .track(TestDomain.KEY, second, Entity::state)
                .track(TestDomain.KEY, first, Entity::state)
                .capture();
        first.setState(State.READY);
        second.setState(State.DONE);
        List<StateChange> changes = snapshot.changes();
        assertEquals(List.of(
                new StateChange(TestDomain.KEY, second, Optional.of(State.NEW), Optional.of(State.DONE)),
                new StateChange(TestDomain.KEY, first, Optional.of(State.NEW), Optional.of(State.READY))), changes);
        assertThrows(UnsupportedOperationException.class, () -> changes.add(changes.get(0)));
        assertNotSame(changes, snapshot.changes());
    }

    // --- a getter that answers null observes no state, which is a value -----------------------------

    @Test
    void aStateThatAppearsIsAChangeFromNoState() {
        Entity entity = new Entity("e", null);
        StateSnapshot snapshot = StateSnapshot.builder()
                .track(TestDomain.KEY, entity, Entity::state).capture();
        assertEquals(List.of(), snapshot.changes());
        entity.setState(State.READY);
        assertEquals(List.of(new StateChange(TestDomain.KEY, entity, Optional.empty(),
                Optional.of(State.READY))), snapshot.changes());
    }

    @Test
    void aStateThatAppearsOrDisappearsIsReportedAsEmptyNeverAsNull() {
        Entity appearing = new Entity("appearing", null);
        Entity disappearing = new Entity("disappearing", State.READY);
        StateSnapshot snapshot = StateSnapshot.builder()
                .track(TestDomain.KEY, appearing, Entity::state)
                .track(TestDomain.KEY, disappearing, Entity::state)
                .capture();
        appearing.setState(State.READY);
        disappearing.setState(null);
        List<StateChange> changes = snapshot.changes();
        assertEquals(Optional.empty(), changes.get(0).before());
        assertEquals(Optional.of(State.READY), changes.get(0).after());
        assertEquals(Optional.of(State.READY), changes.get(1).before());
        assertEquals(Optional.empty(), changes.get(1).after());
    }

    @Test
    void aStateThatDisappearsIsAChangeToNoState() {
        Entity entity = new Entity("e", State.READY);
        StateSnapshot snapshot = StateSnapshot.builder()
                .track(TestDomain.KEY, entity, Entity::state).capture();
        entity.setState(null);
        assertEquals(List.of(new StateChange(TestDomain.KEY, entity, Optional.of(State.READY),
                Optional.empty())), snapshot.changes());
        assertThrows(GraphAssertionError.class, snapshot::assertUnchanged);
    }

    // --- the failure ---------------------------------------------------------------------------------

    @Test
    void assertUnchangedReportsEveryEntityThatMoved() {
        Entity first = new Entity("first", State.NEW);
        Entity second = new Entity("second", State.NEW);
        Entity still = new Entity("still", State.NEW);
        StateSnapshot snapshot = StateSnapshot.builder()
                .track(TestDomain.KEY, first, Entity::state)
                .track(TestDomain.KEY, still, Entity::state)
                .track(TestDomain.KEY, second, Entity::state)
                .capture();
        first.setState(State.READY);
        second.setState(State.DONE);
        GraphAssertionError error = assertThrows(GraphAssertionError.class, snapshot::assertUnchanged);
        assertEquals("state snapshot", error.context());
        assertEquals("changes", error.property());
        assertEquals(List.of(), error.expected());
        assertEquals(List.of(
                new StateChange(TestDomain.KEY, first, Optional.of(State.NEW), Optional.of(State.READY)),
                new StateChange(TestDomain.KEY, second, Optional.of(State.NEW), Optional.of(State.DONE))),
                error.actual());
    }

    // --- tracking is per machine and by reference ----------------------------------------------------

    @Test
    void oneEntityIsTrackedOncePerMachineAndOnceOnly() {
        Box box = new Box("b", Flag.OFF, Shelf.OUT);
        StateSnapshot snapshot = StateSnapshot.builder()
                .track(FLAGS, box, Box::flag)
                .track(SHELVES, box, Box::shelf)
                .capture();
        box.setShelf(Shelf.IN);
        assertEquals(List.of(new StateChange(SHELVES, box, Optional.of(Shelf.OUT), Optional.of(Shelf.IN))),
                snapshot.changes());

        StateSnapshot.Builder builder = StateSnapshot.builder().track(FLAGS, box, Box::flag);
        MachineKey<Box, Flag, Toggle> sameValue = MachineKey.of(Box.class, Flag.class, Toggle.class);
        assertEquals(FLAGS, sameValue);
        assertNotSame(FLAGS, sameValue);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> builder.track(sameValue, box, Box::flag));
        assertEquals("the entity is already tracked under " + FLAGS, error.getMessage());
    }

    @Test
    void twoEntitiesThatCompareEqualAreTrackedSeparately() {
        Box one = new Box("same", Flag.OFF, Shelf.OUT);
        Box other = new Box("same", Flag.OFF, Shelf.OUT);
        assertEquals(one, other);
        StateSnapshot snapshot = StateSnapshot.builder()
                .track(FLAGS, one, Box::flag).track(FLAGS, other, Box::flag).capture();
        other.setFlag(Flag.ON);
        List<StateChange> changes = snapshot.changes();
        assertEquals(1, changes.size());
        assertSame(other, changes.get(0).entity());
        assertEquals(Optional.of(Flag.ON), changes.get(0).after());
    }

    // --- configuration errors ------------------------------------------------------------------------

    @Test
    void nullConfigurationIsRejected() {
        Entity entity = new Entity("e", State.NEW);
        StateSnapshot.Builder builder = StateSnapshot.builder();
        assertThrows(NullPointerException.class, () -> builder.track(null, entity, Entity::state));
        assertThrows(NullPointerException.class, () -> builder.track(TestDomain.KEY, null, Entity::state));
        assertThrows(NullPointerException.class, () -> builder.track(TestDomain.KEY, entity, null));
        builder.capture().assertUnchanged();
    }

    @Test
    void aChangeRequiresEveryComponentAndTakesNoStateAsEmpty() {
        Entity entity = new Entity("e", State.NEW);
        assertThrows(NullPointerException.class,
                () -> new StateChange(null, entity, Optional.of(State.NEW), Optional.of(State.READY)));
        assertThrows(NullPointerException.class,
                () -> new StateChange(TestDomain.KEY, null, Optional.of(State.NEW), Optional.of(State.READY)));
        assertThrows(NullPointerException.class,
                () -> new StateChange(TestDomain.KEY, entity, null, Optional.of(State.READY)));
        assertThrows(NullPointerException.class,
                () -> new StateChange(TestDomain.KEY, entity, Optional.of(State.NEW), null));
        StateChange change = new StateChange(TestDomain.KEY, entity, Optional.empty(), Optional.empty());
        assertEquals(Optional.empty(), change.before());
        assertEquals(Optional.empty(), change.after());
    }

    @Test
    void anEmptySnapshotIsUnchanged() {
        StateSnapshot snapshot = StateSnapshot.builder().capture();
        assertEquals(List.of(), snapshot.changes());
        snapshot.assertUnchanged();
    }

    // --- fixtures ------------------------------------------------------------------------------------

    private static final MachineKey<Box, Flag, Toggle> FLAGS =
            MachineKey.of(Box.class, Flag.class, Toggle.class);
    private static final MachineKey<Box, Shelf, Move> SHELVES =
            MachineKey.of(Box.class, Shelf.class, Move.class);

    /** One state enum of {@link Box}. */
    enum Flag { OFF, ON }

    /** Transition identifiers of the machine over {@link Flag}. */
    enum Toggle { SWITCH }

    /**
     * A second state enum of {@link Box}, which makes it the entity of a second machine: a registry holds at most
     * one machine per entity class and state enum.
     */
    enum Shelf { OUT, IN }

    /** Transition identifiers of the machine over {@link Shelf}. */
    enum Move { PUT_AWAY }

    /** Entity of two machines at once, comparing equal to any other box carrying the same id. */
    @AllArgsConstructor(access = AccessLevel.PACKAGE)
    static final class Box {

        private final String id;
        @Getter(AccessLevel.PACKAGE)
        private Flag flag;
        @Getter(AccessLevel.PACKAGE)
        private Shelf shelf;

        void setFlag(Flag flag) { this.flag = flag; }

        void setShelf(Shelf shelf) { this.shelf = shelf; }

        @Override
        public boolean equals(Object other) {
            return other instanceof Box box && box.id.equals(id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }
}
