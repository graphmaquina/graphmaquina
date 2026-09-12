package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.EdgeKind;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.UnhandledFailureException;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.JournalAssert;
import io.github.graphmaquina.test.OutcomeAssert;
import io.github.graphmaquina.test.TransitionRecordAssert;
import io.github.graphmaquina.test.TransitionRecordsAssert;
import io.github.graphmaquina.test.TypedRecordsAssert;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import io.github.graphmaquina.test.internal.CheckedTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Contract of the transition-record and journal views. Selection is pinned to entity identity rather than
 * equality, order and multiplicity are pinned on every list expectation, each mismatch is read off the
 * fields of the {@link GraphAssertionError} rather than off its type alone, and every public expectation
 * whose subject can be null gets its own null-subject case.
 */
class RecordAssertionsTest {

    // --- the premise of this task: identity, not equality, selects a subject ------------------------

    @Test
    void recordSelectionKeepsEntityIdentityAndDepth() {
        Entity entity = new Entity("same-id", State.READY);
        Entity other = new Entity("same-id", State.READY);
        List<TransitionRecord<?, ?>> records = List.of(
                new TransitionRecord<>(TestDomain.KEY, entity, State.NEW, State.READY,
                        EdgeKind.ACTION, Optional.of("prepare"), 1, false));
        TransitionRecordsAssert.assertThat(records).forEntity(TestDomain.KEY, entity).single()
                .from(State.NEW).to(State.READY).hasDepth(1).isNotConvergent();
        assertThrows(GraphAssertionError.class,
                () -> TransitionRecordsAssert.assertThat(records).forEntity(TestDomain.KEY, other).single());
    }

    // --- the untyped list --------------------------------------------------------------------------

    @Test
    void sizeIsReadOffTheWholeList() {
        Entity entity = new Entity("e", State.NEW);
        TransitionRecordsAssert view = TransitionRecordsAssert.assertThat(
                List.of(hop(entity, State.NEW, State.READY, 0), hop(entity, State.READY, State.DONE, 0)));
        assertSame(view, view.hasSize(2));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasSize(1)),
                "transition records", "size", 1, 2);
    }

    @Test
    void anEmptyListSatisfiesOnlyIsEmpty() {
        Entity entity = new Entity("e", State.NEW);
        TransitionRecordsAssert empty = TransitionRecordsAssert.assertThat(List.of());
        assertSame(empty, empty.isEmpty());
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionRecordsAssert.assertThat(List.of(hop(entity, State.NEW, State.READY, 0))).isEmpty()),
                "transition records", "size", 0, 1);
    }

    @Test
    void containsExactlyKeepsOrderAndMultiplicity() {
        Entity entity = new Entity("e", State.NEW);
        List<TransitionRecord<?, ?>> twice = List.of(
                hop(entity, State.NEW, State.READY, 0), hop(entity, State.NEW, State.READY, 0));
        TransitionRecordsAssert view = TransitionRecordsAssert.assertThat(twice);
        assertSame(view, view.containsExactly(List.of(
                hop(entity, State.NEW, State.READY, 0), hop(entity, State.NEW, State.READY, 0))));
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> view.containsExactly(List.of(hop(entity, State.NEW, State.READY, 0)))),
                "transition records", "size", 1, 2);
    }

    @Test
    void containsExactlyRejectsTheReverseOrder() {
        Entity entity = new Entity("e", State.NEW);
        List<TransitionRecord<?, ?>> forwards = List.of(
                hop(entity, State.NEW, State.READY, 0), hop(entity, State.READY, State.DONE, 1));
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionRecordsAssert.assertThat(forwards).containsExactly(List.of(
                        hop(entity, State.READY, State.DONE, 1), hop(entity, State.NEW, State.READY, 0)))),
                "transition records[0]", "from", State.READY, State.NEW);
    }

    @Test
    void containsExactlyComparesEntitiesByReferenceNotByEquality() {
        Other other = new Other("o");
        Other twin = new Other("o");
        assertEquals(other, twin);
        assertNotSame(other, twin);
        TransitionRecordsAssert view = TransitionRecordsAssert.assertThat(List.of(foreign(other)));
        assertSame(view, view.containsExactly(List.of(foreign(other))));
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> view.containsExactly(List.of(foreign(twin))));
        assertEquals("transition records[0]", error.context());
        assertEquals("entity", error.property());
        assertSame(twin, error.expected());
        assertSame(other, error.actual());
    }

    @Test
    void aKeyThatEqualsTheRecordsKeyMatchesEvenAsAFreshInstance() {
        Entity entity = new Entity("e", State.NEW);
        MachineKey<Entity, State> sameValue = MachineKey.of(Entity.class, State.class);
        assertEquals(TestDomain.KEY, sameValue);
        assertNotSame(TestDomain.KEY, sameValue);
        List<TransitionRecord<?, ?>> records = List.of(hop(entity, State.NEW, State.READY, 0));
        TransitionRecordsAssert.assertThat(records).forEntity(sameValue, entity).single().from(State.NEW);
        TransitionRecordsAssert.assertThat(records).containsExactly(List.of(
                new TransitionRecord<>(sameValue, entity, State.NEW, State.READY, EdgeKind.ACTION,
                        Optional.of("prepare"), 0, false)));
        JournalAssert.assertThat(List.of(entry(entity, ActionStatus.OK)))
                .hasEntry(sameValue, entity, State.NEW, State.READY, "prepare", 1, ActionStatus.OK);
    }

    @Test
    void containsExactlyReportsEveryFieldItCompares() {
        Entity entity = new Entity("e", State.NEW);
        TransitionRecord<Entity, State> mine = hop(entity, State.NEW, State.READY, 0);
        TransitionRecordsAssert view = TransitionRecordsAssert.assertThat(List.of(mine));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.containsExactly(List.of(
                new TransitionRecord<>(TestDomain.KEY, entity, State.NEW, State.DONE, EdgeKind.ACTION,
                        Optional.of("prepare"), 0, false)))),
                "transition records[0]", "to", State.DONE, State.READY);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.containsExactly(List.of(
                new TransitionRecord<>(TestDomain.KEY, entity, State.NEW, State.READY, EdgeKind.MOVE,
                        Optional.of("prepare"), 0, false)))),
                "transition records[0]", "kind", EdgeKind.MOVE, EdgeKind.ACTION);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.containsExactly(List.of(
                new TransitionRecord<>(TestDomain.KEY, entity, State.NEW, State.READY, EdgeKind.ACTION,
                        Optional.empty(), 0, false)))),
                "transition records[0]", "action", Optional.empty(), Optional.of("prepare"));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.containsExactly(List.of(
                new TransitionRecord<>(TestDomain.KEY, entity, State.NEW, State.READY, EdgeKind.ACTION,
                        Optional.of("prepare"), 3, false)))),
                "transition records[0]", "depth", 3, 0);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.containsExactly(List.of(
                new TransitionRecord<>(TestDomain.KEY, entity, State.NEW, State.READY, EdgeKind.ACTION,
                        Optional.of("prepare"), 0, true)))),
                "transition records[0]", "convergent", true, false);
    }

    @Test
    void containsExactlyComparesTheMachineKey() {
        Entity entity = new Entity("e", State.NEW);
        Other other = new Other("o");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionRecordsAssert.assertThat(List.of(foreign(other)))
                        .containsExactly(List.of(hop(entity, State.NEW, State.READY, 0)))),
                "transition records[0]", "key", TestDomain.KEY, OTHERS);
    }

    @Test
    void theViewCopiesTheListItWasGiven() {
        Entity entity = new Entity("e", State.NEW);
        List<TransitionRecord<?, ?>> mutable = new ArrayList<>();
        mutable.add(hop(entity, State.NEW, State.READY, 0));
        TransitionRecordsAssert view = TransitionRecordsAssert.assertThat(mutable);
        mutable.add(hop(entity, State.READY, State.DONE, 0));
        mutable.clear();
        view.hasSize(1);
        view.forEntity(TestDomain.KEY, entity).hasSize(1);
    }

    // --- selection ---------------------------------------------------------------------------------

    @Test
    void selectionSkipsRecordsOfAnotherMachine() {
        Entity entity = new Entity("e", State.NEW);
        Other other = new Other("o");
        List<TransitionRecord<?, ?>> mixed = List.of(foreign(other), hop(entity, State.NEW, State.READY, 0));
        TransitionRecordsAssert view = TransitionRecordsAssert.assertThat(mixed);
        view.hasSize(2);
        view.forEntity(TestDomain.KEY, entity).single().from(State.NEW).to(State.READY);
        view.forEntity(OTHERS, other).single().from(Flag.OFF).to(Flag.ON);
    }

    @Test
    void selectionSeparatesTwoMachinesOverTheSameEntityInstance() {
        Entity entity = new Entity("e", State.NEW);
        List<TransitionRecord<?, ?>> both = List.of(
                hop(entity, State.NEW, State.READY, 0),
                new TransitionRecord<>(SHELVES, entity, Shelf.OUT, Shelf.IN, EdgeKind.MOVE,
                        Optional.empty(), 0, false));
        TransitionRecordsAssert view = TransitionRecordsAssert.assertThat(both);
        view.forEntity(TestDomain.KEY, entity).single().from(State.NEW).to(State.READY);
        view.forEntity(SHELVES, entity).single().from(Shelf.OUT).to(Shelf.IN);
    }

    @Test
    void selectionIsByReferenceNotByEquality() {
        Other other = new Other("o");
        Other twin = new Other("o");
        assertEquals(other, twin);
        assertNotSame(other, twin);
        TransitionRecordsAssert view = TransitionRecordsAssert.assertThat(List.of(foreign(other)));
        view.forEntity(OTHERS, other).single().from(Flag.OFF).to(Flag.ON);
        view.forEntity(OTHERS, twin).hasSize(0);
    }

    @Test
    void anEntityWithNoRecordsSelectsAnEmptyViewRatherThanFailing() {
        Entity entity = new Entity("e", State.NEW);
        Entity absent = new Entity("absent", State.NEW);
        TypedRecordsAssert<Entity, State> selected = TransitionRecordsAssert
                .assertThat(List.of(hop(entity, State.NEW, State.READY, 0)))
                .forEntity(TestDomain.KEY, absent);
        assertSame(selected, selected.hasSize(0));
        assertFailure(assertThrows(GraphAssertionError.class, selected::single),
                "transition records for " + TestDomain.KEY, "size", 1, 0);
    }

    @Test
    void selectionKeepsDuplicatesAndTheirOrder() {
        Entity entity = new Entity("e", State.NEW);
        TypedRecordsAssert<Entity, State> selected = TransitionRecordsAssert.assertThat(List.of(
                        hop(entity, State.NEW, State.READY, 0),
                        hop(entity, State.NEW, State.READY, 0),
                        hop(entity, State.READY, State.DONE, 1)))
                .forEntity(TestDomain.KEY, entity);
        selected.hasSize(3);
        selected.atIndex(0).hasDepth(0);
        selected.atIndex(1).hasDepth(0);
        selected.atIndex(2).hasDepth(1).from(State.READY).to(State.DONE);
        assertFailure(assertThrows(GraphAssertionError.class, selected::single),
                "transition records for " + TestDomain.KEY, "size", 1, 3);
    }

    @Test
    void anIndexBeyondTheSelectionFailsAsAnAssertion() {
        Entity entity = new Entity("e", State.NEW);
        TypedRecordsAssert<Entity, State> selected = TransitionRecordsAssert
                .assertThat(List.of(hop(entity, State.NEW, State.READY, 0)))
                .forEntity(TestDomain.KEY, entity);
        assertFailure(assertThrows(GraphAssertionError.class, () -> selected.atIndex(1)),
                "transition records for " + TestDomain.KEY, "index 1 present", true, false);
        assertFailure(assertThrows(GraphAssertionError.class, () -> selected.hasSize(2)),
                "transition records for " + TestDomain.KEY, "size", 2, 1);
    }

    @Test
    void aNegativeIndexIsAnArgumentError() {
        Entity entity = new Entity("e", State.NEW);
        TypedRecordsAssert<Entity, State> selected = TransitionRecordsAssert
                .assertThat(List.of(hop(entity, State.NEW, State.READY, 0)))
                .forEntity(TestDomain.KEY, entity);
        assertThrows(IllegalArgumentException.class, () -> selected.atIndex(-1));
    }

    // --- one record --------------------------------------------------------------------------------

    @Test
    void everyFieldOfARecordIsReadable() {
        Entity entity = new Entity("e", State.NEW);
        TransitionRecordAssert<Entity, State> view = TransitionRecordsAssert
                .assertThat(List.of(hop(entity, State.NEW, State.READY, 2)))
                .forEntity(TestDomain.KEY, entity).single();
        String where = "transition records for " + TestDomain.KEY + "[0]";
        assertSame(view, view.from(State.NEW).to(State.READY).hasKind(EdgeKind.ACTION)
                .hasActionNamed("prepare").hasDepth(2).isNotConvergent());
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.from(State.READY)),
                where, "from", State.READY, State.NEW);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.to(State.DONE)),
                where, "to", State.DONE, State.READY);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasKind(EdgeKind.STAY)),
                where, "kind", EdgeKind.STAY, EdgeKind.ACTION);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasActionNamed("audit")),
                where, "action", Optional.of("audit"), Optional.of("prepare"));
        assertFailure(assertThrows(GraphAssertionError.class, view::hasNoAction),
                where, "action", Optional.empty(), Optional.of("prepare"));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasDepth(0)),
                where, "depth", 0, 2);
        assertFailure(assertThrows(GraphAssertionError.class, view::isConvergent),
                where, "convergent", true, false);
    }

    @Test
    void aHopWithoutAnActionSatisfiesOnlyHasNoAction() {
        Entity entity = new Entity("e", State.NEW);
        TransitionRecordAssert<Entity, State> view = TransitionRecordsAssert
                .assertThat(List.of(new TransitionRecord<>(TestDomain.KEY, entity, State.NEW, State.NEW,
                        EdgeKind.STAY, Optional.empty(), 1, true)))
                .forEntity(TestDomain.KEY, entity).single();
        String where = "transition records for " + TestDomain.KEY + "[0]";
        assertSame(view, view.hasNoAction().isConvergent().hasKind(EdgeKind.STAY));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasActionNamed("prepare")),
                where, "action", Optional.of("prepare"), Optional.empty());
        assertFailure(assertThrows(GraphAssertionError.class, view::isNotConvergent),
                where, "convergent", false, true);
    }

    // --- the localized cast ------------------------------------------------------------------------

    @Test
    void aRecordOfAnotherMachineIsNeverCastIntoThisOne() {
        Other other = new Other("o");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CheckedTypes.record(TestDomain.KEY, foreign(other)));
        assertEquals("record does not belong to the machine: key expected " + TestDomain.KEY
                + " but was " + OTHERS, error.getMessage());
    }

    @Test
    void aRecordWhoseEntityDoesNotMatchTheKeyIsNeverCast() {
        @SuppressWarnings("unchecked")
        MachineKey<Other, State> disguised = (MachineKey<Other, State>) (MachineKey<?, ?>) TestDomain.KEY;
        TransitionRecord<Other, State> corrupt = new TransitionRecord<>(disguised, new Other("o"),
                State.NEW, State.READY, EdgeKind.MOVE, Optional.empty(), 0, false);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CheckedTypes.record(TestDomain.KEY, corrupt));
        assertEquals("record does not belong to the machine: entity expected " + Entity.class
                + " but was " + corrupt.entity(), error.getMessage());
    }

    @Test
    void aRecordWhoseStatesDoNotMatchTheKeyIsNeverCast() {
        @SuppressWarnings("unchecked")
        MachineKey<Entity, Flag> disguised = (MachineKey<Entity, Flag>) (MachineKey<?, ?>) TestDomain.KEY;
        TransitionRecord<Entity, Flag> corrupt = new TransitionRecord<>(disguised, new Entity("e", State.NEW),
                Flag.OFF, Flag.ON, EdgeKind.MOVE, Optional.empty(), 0, false);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CheckedTypes.record(TestDomain.KEY, corrupt));
        assertEquals("record does not belong to the machine: states expected " + State.class
                + " but was " + List.of(Flag.OFF, Flag.ON), error.getMessage());
    }

    // --- the journal -------------------------------------------------------------------------------

    @Test
    void journalSizeAndEmptinessAreReadOffTheWholeList() {
        Entity entity = new Entity("e", State.NEW);
        JournalAssert view = JournalAssert.assertThat(List.of(entry(entity, ActionStatus.OK)));
        assertSame(view, view.hasSize(1));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasSize(2)), "journal", "size", 2, 1);
        assertFailure(assertThrows(GraphAssertionError.class, view::isEmpty), "journal", "size", 0, 1);
        JournalAssert empty = JournalAssert.assertThat(List.of());
        assertSame(empty, empty.isEmpty());
    }

    @Test
    void journalActionsKeepOrderAndMultiplicity() {
        Entity entity = new Entity("e", State.NEW);
        JournalAssert view = JournalAssert.assertThat(List.of(
                entry(entity, ActionStatus.OK), entry(entity, ActionStatus.THREW)));
        assertSame(view, view.hasActionsExactly("prepare", "prepare"));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasActionsExactly("prepare")),
                "journal", "actions", List.of("prepare"), List.of("prepare", "prepare"));
    }

    @Test
    void journalStatusesAreReportedExactlyAsTheEngineWroteThem() {
        Entity entity = new Entity("e", State.NEW);
        JournalAssert view = JournalAssert.assertThat(List.of(
                entry(entity, ActionStatus.OK), entry(entity, ActionStatus.THREW)));
        assertSame(view, view.hasStatusesExactly(ActionStatus.OK, ActionStatus.THREW));
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> view.hasStatusesExactly(ActionStatus.THREW, ActionStatus.OK)),
                "journal", "statuses", List.of(ActionStatus.THREW, ActionStatus.OK),
                List.of(ActionStatus.OK, ActionStatus.THREW));
    }

    @Test
    void journalEntryMatchesEveryFieldAtOnce() {
        Entity entity = new Entity("e", State.NEW);
        List<ActionRecord> journal = List.of(entry(entity, ActionStatus.FAILED));
        JournalAssert view = JournalAssert.assertThat(journal);
        assertSame(view, view.hasEntry(TestDomain.KEY, entity, State.NEW, State.READY, "prepare", 1,
                ActionStatus.FAILED));
        assertEntryMiss(view, entity, State.READY, State.READY, "prepare", 1, ActionStatus.FAILED, journal);
        assertEntryMiss(view, entity, State.NEW, State.DONE, "prepare", 1, ActionStatus.FAILED, journal);
        assertEntryMiss(view, entity, State.NEW, State.READY, "audit", 1, ActionStatus.FAILED, journal);
        assertEntryMiss(view, entity, State.NEW, State.READY, "prepare", 0, ActionStatus.FAILED, journal);
        assertEntryMiss(view, entity, State.NEW, State.READY, "prepare", 1, ActionStatus.THREW, journal);
    }

    @Test
    void journalEntrySelectsTheEntityByReferenceNotByEquality() {
        Other other = new Other("o");
        Other twin = new Other("o");
        assertEquals(other, twin);
        assertNotSame(other, twin);
        JournalAssert view = JournalAssert.assertThat(List.of(
                new ActionRecord(OTHERS, other, Flag.OFF, Flag.ON, "flip", 0, ActionStatus.OK)));
        view.hasEntry(OTHERS, other, Flag.OFF, Flag.ON, "flip", 0, ActionStatus.OK);
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> view.hasEntry(OTHERS, twin, Flag.OFF, Flag.ON, "flip", 0, ActionStatus.OK));
        assertEquals("journal", error.context());
        assertEquals("entry", error.property());
        assertSame(twin, ((ActionRecord) error.expected()).entity());
    }

    @Test
    void journalEntryComparesTheMachineKey() {
        Other other = new Other("o");
        List<ActionRecord> journal = List.of(
                new ActionRecord(OTHERS, other, Flag.OFF, Flag.ON, "flip", 0, ActionStatus.OK));
        JournalAssert view = JournalAssert.assertThat(journal);
        view.hasEntry(OTHERS, other, Flag.OFF, Flag.ON, "flip", 0, ActionStatus.OK);
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> view.hasEntry(FLIPPABLES, other, Flag.OFF, Flag.ON, "flip", 0, ActionStatus.OK));
        assertEquals("journal", error.context());
        assertEquals("entry", error.property());
        assertEquals(new ActionRecord(FLIPPABLES, other, Flag.OFF, Flag.ON, "flip", 0, ActionStatus.OK),
                error.expected());
        assertEquals(journal, error.actual());
    }

    @Test
    void theJournalViewCopiesTheListItWasGiven() {
        Entity entity = new Entity("e", State.NEW);
        List<ActionRecord> mutable = new ArrayList<>();
        mutable.add(entry(entity, ActionStatus.OK));
        JournalAssert view = JournalAssert.assertThat(mutable);
        mutable.add(entry(entity, ActionStatus.THREW));
        mutable.clear();
        view.hasSize(1);
        view.hasActionsExactly("prepare");
        view.hasStatusesExactly(ActionStatus.OK);
    }

    // --- the two outcome views that open these ones ------------------------------------------------

    @Test
    void aFailedOutcomeExposesTheHopsItCommitted() {
        Entity entity = new Entity("e", State.NEW);
        Outcome<Entity, State> outcome = new Outcome.Failed<>(entity, State.NEW, State.DONE, State.FAILED,
                "boom", Optional.empty(), List.of(hop(entity, State.NEW, State.FAILED, 0)), List.of());
        TransitionRecordsAssert records = OutcomeAssert.assertThat(outcome).isFailed().records();
        assertSame(records, records.hasSize(1));
        records.forEntity(TestDomain.KEY, entity).single().to(State.FAILED).hasDepth(0);
    }

    @Test
    void anAppliedOutcomeWithoutHopsExposesAnEmptyView() {
        Entity entity = new Entity("e", State.NEW);
        Outcome<Entity, State> outcome = new Outcome.Applied<>(entity, State.NEW, State.READY,
                List.of(), List.of());
        OutcomeAssert.assertThat(outcome).isApplied().records().isEmpty();
    }

    // --- null subjects: one case per expectation whose subject can be absent ------------------------

    @Test
    void nullSubjectFailsRecordsHasSize() {
        List<TransitionRecord<?, ?>> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionRecordsAssert.assertThat(none).hasSize(1)),
                "transition records", "size", 1, null);
    }

    @Test
    void nullSubjectFailsRecordsIsEmpty() {
        List<TransitionRecord<?, ?>> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionRecordsAssert.assertThat(none).isEmpty()),
                "transition records", "size", 0, null);
    }

    @Test
    void nullSubjectFailsRecordsContainsExactly() {
        List<TransitionRecord<?, ?>> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionRecordsAssert.assertThat(none).containsExactly(List.of())),
                "transition records", "size", 0, null);
    }

    @Test
    void nullSubjectFailsForEntity() {
        List<TransitionRecord<?, ?>> none = null;
        Entity entity = new Entity("e", State.NEW);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> TransitionRecordsAssert.assertThat(none).forEntity(TestDomain.KEY, entity)),
                "transition records", "entity records", entity, null);
    }

    @Test
    void nullSubjectFailsJournalHasSize() {
        List<ActionRecord> none = null;
        assertFailure(assertThrows(GraphAssertionError.class, () -> JournalAssert.assertThat(none).hasSize(1)),
                "journal", "size", 1, null);
    }

    @Test
    void nullSubjectFailsJournalIsEmpty() {
        List<ActionRecord> none = null;
        assertFailure(assertThrows(GraphAssertionError.class, () -> JournalAssert.assertThat(none).isEmpty()),
                "journal", "size", 0, null);
    }

    @Test
    void nullSubjectFailsJournalHasActionsExactly() {
        List<ActionRecord> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> JournalAssert.assertThat(none).hasActionsExactly("prepare")),
                "journal", "actions", List.of("prepare"), null);
    }

    @Test
    void nullSubjectFailsJournalHasStatusesExactly() {
        List<ActionRecord> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> JournalAssert.assertThat(none).hasStatusesExactly(ActionStatus.OK)),
                "journal", "statuses", List.of(ActionStatus.OK), null);
    }

    @Test
    void nullSubjectFailsJournalHasEntry() {
        List<ActionRecord> none = null;
        Entity entity = new Entity("e", State.NEW);
        GraphAssertionError error = assertThrows(GraphAssertionError.class, () -> JournalAssert.assertThat(none)
                .hasEntry(TestDomain.KEY, entity, State.NEW, State.READY, "prepare", 1, ActionStatus.OK));
        assertEquals("journal", error.context());
        assertEquals("entry", error.property());
        assertEquals(entry(entity, ActionStatus.OK), error.expected());
        assertEquals(null, error.actual());
    }

    // --- null arguments stay programming errors ----------------------------------------------------

    @Test
    void aNullRequiredArgumentIsAnArgumentErrorEvenWhenTheSubjectIsNull() {
        List<TransitionRecord<?, ?>> noRecords = null;
        List<ActionRecord> noJournal = null;
        Entity entity = new Entity("e", State.NEW);
        assertThrows(NullPointerException.class,
                () -> TransitionRecordsAssert.assertThat(noRecords).containsExactly(null));
        assertThrows(NullPointerException.class,
                () -> TransitionRecordsAssert.assertThat(noRecords).forEntity(null, entity));
        assertThrows(NullPointerException.class,
                () -> TransitionRecordsAssert.assertThat(noRecords).forEntity(TestDomain.KEY, null));
        assertThrows(NullPointerException.class, () -> JournalAssert.assertThat(noJournal)
                .hasEntry(TestDomain.KEY, entity, State.NEW, State.READY, null, 1, ActionStatus.OK));
        assertThrows(NullPointerException.class, () -> JournalAssert.assertThat(noJournal)
                .hasEntry(TestDomain.KEY, entity, State.NEW, State.READY, "prepare", 1, null));
    }

    @Test
    void aNullExpectedStateIsAnArgumentError() {
        Entity entity = new Entity("e", State.NEW);
        TransitionRecordAssert<Entity, State> view = TransitionRecordsAssert
                .assertThat(List.of(hop(entity, State.NEW, State.READY, 0)))
                .forEntity(TestDomain.KEY, entity).single();
        assertThrows(NullPointerException.class, () -> view.from(null));
        assertThrows(NullPointerException.class, () -> view.to(null));
        assertThrows(NullPointerException.class, () -> view.hasKind(null));
        assertThrows(NullPointerException.class, () -> view.hasActionNamed(null));
    }

    // --- against the real engine -------------------------------------------------------------------

    /**
     * A convergent cascade: sealing the box sticks its tag, and sticking the tag asks the box for the very
     * state the transaction is already heading to. The engine answers with the idempotent no-op, which it
     * records as a convergent hop rather than as a second move.
     */
    @Test
    void aConvergentCascadeIsRecordedAsAnIdempotentNoOp() {
        Box box = new Box("b1", Crate.OPEN);
        Tag tag = new Tag("t1", TagState.LOOSE, box);
        sealBox = (TransitionContext<Box, Crate> ctx) -> {
            ctx.cascade(TAGS, tag, TagState.STUCK);
            return ActionOutcome.ok();
        };
        stickTag = (TransitionContext<Tag, TagState> ctx) -> {
            ctx.cascade(BOXES, ctx.entity().box(), Crate.SEALED);
            return ActionOutcome.ok();
        };

        try (MachineRegistry registry = registry()) {
            Outcome<Box, Crate> outcome = registry.of(BOXES).transitionTo(box, Crate.SEALED);
            TransitionRecordsAssert records = OutcomeAssert.assertThat(outcome).isApplied()
                    .movedTo(Crate.SEALED).records();
            records.hasSize(3);
            records.forEntity(TAGS, tag).single()
                    .from(TagState.LOOSE).to(TagState.STUCK).hasKind(EdgeKind.ACTION)
                    .hasActionNamed("StickTag").hasDepth(1).isNotConvergent();
            TypedRecordsAssert<Box, Crate> boxRecords = records.forEntity(BOXES, box);
            boxRecords.hasSize(2);
            boxRecords.atIndex(0).from(Crate.OPEN).to(Crate.SEALED).hasKind(EdgeKind.ACTION)
                    .hasActionNamed("SealBox").hasDepth(0).isNotConvergent();
            boxRecords.atIndex(1).from(Crate.SEALED).to(Crate.SEALED).hasKind(EdgeKind.STAY)
                    .hasNoAction().hasDepth(2).isConvergent();
        }
    }

    /**
     * A parent that fails after cascading: nothing commits, so there are no transition records to read at
     * all, while the abort journal still names every action that ran and the status it ended on.
     */
    @Test
    void aParentThatFailsAfterACascadeJournalsWhatItNeverCommitted() {
        Box box = new Box("b1", Crate.OPEN);
        Tag tag = new Tag("t1", TagState.LOOSE, box);
        dropBox = (TransitionContext<Box, Crate> ctx) -> {
            ctx.cascadeAll(TAGS, List.of(tag), TagState.STUCK, CascadePolicy.FAIL_FAST);
            return ActionOutcome.failed("change of mind");
        };
        stickTag = (TransitionContext<Tag, TagState> ctx) -> ActionOutcome.ok();

        try (MachineRegistry registry = registry()) {
            UnhandledFailureException error = assertThrows(UnhandledFailureException.class,
                    () -> registry.of(BOXES).transitionTo(box, Crate.DISCARDED));
            assertEquals(Crate.OPEN, box.state());
            assertEquals(TagState.LOOSE, tag.state());
            JournalAssert journal = JournalAssert.assertThat(error.journal());
            journal.hasActionsExactly("DropBox", "StickTag");
            journal.hasEntry(TAGS, tag, TagState.LOOSE, TagState.STUCK, "StickTag", 1, ActionStatus.OK);
            assertThrows(GraphAssertionError.class, () -> journal.hasEntry(BOXES, box, Crate.OPEN,
                    Crate.DISCARDED, "DropBox", 0, ActionStatus.OK));
        }
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private static final MachineKey<Other, Flag> OTHERS = MachineKey.of(Other.class, Flag.class);
    private static final MachineKey<Flippable, Flag> FLIPPABLES = MachineKey.of(Flippable.class, Flag.class);
    private static final MachineKey<Entity, Shelf> SHELVES = MachineKey.of(Entity.class, Shelf.class);
    private static final MachineKey<Box, Crate> BOXES = MachineKey.of(Box.class, Crate.class);
    private static final MachineKey<Tag, TagState> TAGS = MachineKey.of(Tag.class, TagState.class);

    /** States of {@link Other}, a second machine whose records must never be selected by the first. */
    enum Flag { OFF, ON }

    /** A second state enum of {@link Entity}: one entity class belongs to as many machines as it has states. */
    enum Shelf { OUT, IN }

    /** Carried by {@link Other} so two keys can name the same instance and differ in nothing but their key. */
    interface Flippable { }

    /** Entity of the second machine; its records share a list with {@link Entity}'s. */
    record Other(String id) implements Flippable { }

    /** States of {@link Box}. */
    enum Crate { OPEN, SEALED, DISCARDED }

    /** States of {@link Tag}. */
    enum TagState { LOOSE, STUCK }

    /** Parent entity of the engine cases, written by the machine and read back by the assertions. */
    static final class Box {

        private final String id;
        private Crate state;

        Box(String id, Crate state) {
            this.id = id;
            this.state = state;
        }

        String id() { return id; }

        Crate state() { return state; }

        void setState(Crate state) { this.state = state; }
    }

    /** Peer entity of the engine cases: the cascade target that can ask its box to converge. */
    static final class Tag {

        private final String id;
        private final Box box;
        private TagState state;

        Tag(String id, TagState state, Box box) {
            this.id = id;
            this.state = state;
            this.box = box;
        }

        String id() { return id; }

        Box box() { return box; }

        TagState state() { return state; }

        void setState(TagState state) { this.state = state; }
    }

    private TransitionAction<Box, Crate> sealBox = (TransitionContext<Box, Crate> ctx) -> ActionOutcome.ok();
    private TransitionAction<Box, Crate> dropBox = (TransitionContext<Box, Crate> ctx) -> ActionOutcome.ok();
    private TransitionAction<Tag, TagState> stickTag =
            (TransitionContext<Tag, TagState> ctx) -> ActionOutcome.ok();

    private MachineRegistry registry() {
        GraphDefinition<Box, Crate> boxes = StateGraph.define(BOXES)
                .initial(Crate.OPEN).terminal(Crate.SEALED).terminal(Crate.DISCARDED)
                .from(Crate.OPEN)
                    .to(Crate.SEALED).action("SealBox", (TransitionContext<Box, Crate> ctx) -> sealBox.process(ctx))
                    .to(Crate.DISCARDED).action("DropBox", (TransitionContext<Box, Crate> ctx) -> dropBox.process(ctx))
                    .stay().forbidOthers("an open box either seals or is discarded")
                .from(Crate.SEALED).stay().forbidOthers("sealed is final")
                .from(Crate.DISCARDED).stay().forbidOthers("discarded is final")
                .build();
        GraphDefinition<Tag, TagState> tags = StateGraph.define(TAGS)
                .initial(TagState.LOOSE).terminal(TagState.STUCK)
                .from(TagState.LOOSE)
                    .to(TagState.STUCK).action("StickTag",
                            (TransitionContext<Tag, TagState> ctx) -> stickTag.process(ctx))
                    .stay().forbidOthers("a loose tag only sticks")
                .from(TagState.STUCK).stay().forbidOthers("stuck is final")
                .build();
        return MachineRegistry.builder()
                .add(StateMachines.from(boxes).state(Box::state, Box::setState).identity(Box::id))
                .add(StateMachines.from(tags).state(Tag::state, Tag::setState).identity(Tag::id))
                .build();
    }

    private static TransitionRecord<Entity, State> hop(Entity entity, State from, State to, int depth) {
        return new TransitionRecord<>(TestDomain.KEY, entity, from, to, EdgeKind.ACTION,
                Optional.of("prepare"), depth, false);
    }

    private static TransitionRecord<Other, Flag> foreign(Other other) {
        return new TransitionRecord<>(OTHERS, other, Flag.OFF, Flag.ON, EdgeKind.MOVE,
                Optional.empty(), 0, false);
    }

    private static ActionRecord entry(Entity entity, ActionStatus status) {
        return new ActionRecord(TestDomain.KEY, entity, State.NEW, State.READY, "prepare", 1, status);
    }

    private static void assertEntryMiss(JournalAssert view, Entity entity, State from, State to, String action,
                                        int depth, ActionStatus status, List<ActionRecord> journal) {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> view.hasEntry(TestDomain.KEY, entity, from, to, action, depth, status));
        assertEquals("journal", error.context());
        assertEquals("entry", error.property());
        assertEquals(new ActionRecord(TestDomain.KEY, entity, from, to, action, depth, status), error.expected());
        assertEquals(journal, error.actual());
    }

    private static void assertFailure(
            GraphAssertionError error, String context, String property, Object expected, Object actual) {
        assertEquals(context, error.context());
        assertEquals(property, error.property());
        assertEquals(expected, error.expected());
        assertEquals(actual, error.actual());
    }
}
