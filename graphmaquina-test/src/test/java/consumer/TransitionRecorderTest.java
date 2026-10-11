package consumer;

import static io.github.graphmaquina.api.WatchCondition.allIn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.FlowEntry;
import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.IgnoreReason;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.OutcomeStatus;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.runtime.StateMachines;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.OutcomeSequenceAssert;
import io.github.graphmaquina.test.RecordedTransition;
import io.github.graphmaquina.test.RecordedTransitionsAssert;
import io.github.graphmaquina.test.TransitionRecorder;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * Contract of the transition recorder. The recorder observes completed calls and nothing else, so a reaction
 * is one observation of its own and no nested reaction is ever walked; a projection is captured when the
 * call completes rather than read back later; selection is by entity identity rather than equality;
 * and every mismatch is read off the fields of the {@link GraphAssertionError} rather than off its type
 * alone.
 */
class TransitionRecorderTest {

    /** What the engine does when a call completes: it hands the recorder that call and its outcome. */
    private static void complete(TransitionRecorder recorder, MachineKey<?, ?, ?> key, Outcome<?, ?, ?> outcome) {
        recorder.onCompleted(new MachineCall(key, outcome.entity(), Optional.empty(), false), outcome, Duration.ZERO);
    }

    // --- the premise of this task: the projection freezes what the entity was then -------------------

    @Test
    void recorderCapturesProjectionAtNotificationTime() {
        TransitionRecorder recorder = TransitionRecorder.projecting(
                (MachineKey<?, ?, ?> key, Outcome<?, ?, ?> outcome) -> ((Entity) outcome.entity()).state());
        Entity entity = new Entity("e", State.READY);
        complete(recorder, TestDomain.KEY, new Outcome.Applied<>(entity, State.NEW, Id.PREPARE, State.READY,
                List.of(), List.of()));
        entity.setState(State.DONE);
        assertEquals(Optional.of(State.READY), recorder.snapshot().get(0).projection());
        recorder.assertRecorded().forEntity(TestDomain.KEY, entity).single()
                .isApplied().movedTo(State.READY);
    }

    @Test
    void theProjectionReceivesTheKeyOfTheCallbackItIsRecording() {
        TransitionRecorder recorder = TransitionRecorder.projecting(
                (MachineKey<?, ?, ?> key, Outcome<?, ?, ?> outcome) -> key + "=" + outcome.recordedState());
        Entity entity = new Entity("e", State.NEW);
        Twin twin = new Twin("t", State.NEW);
        complete(recorder, TestDomain.KEY, applied(entity));
        complete(recorder, TWINS, new Outcome.Applied<>(twin, State.NEW, TwinId.ADVANCE, State.READY,
                List.of(), List.of()));
        assertEquals(List.of(Optional.of("Entity/State=READY"), Optional.of("Twin/State=READY")),
                recorder.snapshot().stream().map(RecordedTransition::projection).toList());
    }

    @Test
    void aRecorderWithoutAProjectionRecordsNone() {
        TransitionRecorder recorder = TransitionRecorder.create();
        Entity entity = new Entity("e", State.NEW);
        complete(recorder, TestDomain.KEY, applied(entity));
        RecordedTransition recorded = recorder.snapshot().get(0);
        assertEquals(Optional.empty(), recorded.projection());
        assertEquals(0L, recorded.sequence());
        assertEquals(TestDomain.KEY, recorded.key());
        assertSame(entity, recorded.outcome().entity());
    }

    @Test
    void aProjectionThatReturnsNothingIsRejectedAndRecordsNothing() {
        TransitionRecorder recorder = TransitionRecorder.projecting(
                (MachineKey<?, ?, ?> key, Outcome<?, ?, ?> outcome) -> null);
        Entity entity = new Entity("e", State.NEW);
        assertThrows(NullPointerException.class,
                () -> complete(recorder, TestDomain.KEY, applied(entity)));
        assertEquals(List.of(), recorder.snapshot());
    }

    // --- the sequence and the snapshot ---------------------------------------------------------------

    @Test
    void sequencesNumberTheCallbacksInTheOrderObserved() {
        TransitionRecorder recorder = TransitionRecorder.create();
        Entity first = new Entity("first", State.NEW);
        Entity second = new Entity("second", State.NEW);
        complete(recorder, TestDomain.KEY, applied(first));
        complete(recorder, TestDomain.KEY, ignored(second));
        complete(recorder, TestDomain.KEY, applied(second));
        assertEquals(List.of(0L, 1L, 2L),
                recorder.snapshot().stream().map(RecordedTransition::sequence).toList());
        assertSame(second, recorder.snapshot().get(1).outcome().entity());
    }

    @Test
    void aSnapshotIsAnImmutableCopyTakenWhenItWasAsked() {
        TransitionRecorder recorder = TransitionRecorder.create();
        Entity entity = new Entity("e", State.NEW);
        complete(recorder, TestDomain.KEY, applied(entity));
        List<RecordedTransition> taken = recorder.snapshot();
        complete(recorder, TestDomain.KEY, applied(entity));
        assertEquals(1, taken.size());
        assertEquals(2, recorder.snapshot().size());
        assertNotSame(taken, recorder.snapshot());
        assertThrows(UnsupportedOperationException.class, () -> taken.add(taken.get(0)));
    }

    @Test
    void theViewSeesOnlyWhatWasRecordedWhenItWasBuilt() {
        TransitionRecorder recorder = TransitionRecorder.create();
        Entity entity = new Entity("e", State.NEW);
        complete(recorder, TestDomain.KEY, applied(entity));
        RecordedTransitionsAssert view = recorder.assertRecorded();
        complete(recorder, TestDomain.KEY, applied(entity));
        view.hasSize(1);
        recorder.assertRecorded().hasSize(2);
    }

    @Test
    void nullCallbackArgumentsAreRejected() {
        TransitionRecorder recorder = TransitionRecorder.create();
        Entity entity = new Entity("e", State.NEW);
        MachineCall call = new MachineCall(TestDomain.KEY, entity, Optional.empty(), false);
        assertThrows(NullPointerException.class, () -> recorder.onCompleted(null, applied(entity), Duration.ZERO));
        assertThrows(NullPointerException.class, () -> recorder.onCompleted(call, null, Duration.ZERO));
        assertThrows(NullPointerException.class, () -> recorder.onCompleted(call, applied(entity), null));
        assertThrows(NullPointerException.class, () -> TransitionRecorder.projecting(null));
        assertEquals(List.of(), recorder.snapshot());
    }

    @Test
    void aRecordedTransitionRequiresItsKeyItsOutcomeAndItsProjection() {
        Entity entity = new Entity("e", State.NEW);
        assertThrows(NullPointerException.class,
                () -> new RecordedTransition(0L, null, applied(entity), Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> new RecordedTransition(0L, TestDomain.KEY, null, Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> new RecordedTransition(0L, TestDomain.KEY, applied(entity), null));
    }

    // --- the untyped view ----------------------------------------------------------------------------

    @Test
    void sizeIsReadOffEveryCallback() {
        TransitionRecorder recorder = TransitionRecorder.create();
        Entity entity = new Entity("e", State.NEW);
        complete(recorder, TestDomain.KEY, applied(entity));
        RecordedTransitionsAssert view = recorder.assertRecorded();
        assertSame(view, view.hasSize(1));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasSize(2)),
                "recorded transitions", "size", 2, 1);
    }

    @Test
    void keysArePinnedInOrderAndBySize() {
        TransitionRecorder recorder = TransitionRecorder.create();
        Entity entity = new Entity("e", State.NEW);
        Twin twin = new Twin("t", State.NEW);
        complete(recorder, TestDomain.KEY, applied(entity));
        complete(recorder, TWINS, new Outcome.Applied<>(twin, State.NEW, TwinId.ADVANCE, State.READY,
                List.of(), List.of()));
        RecordedTransitionsAssert view = recorder.assertRecorded();
        assertSame(view, view.hasKeysExactly(TestDomain.KEY, TWINS));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> view.hasKeysExactly(TWINS, TestDomain.KEY)),
                "recorded transitions[0]", "key", TWINS, TestDomain.KEY);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasKeysExactly(TestDomain.KEY)),
                "recorded transitions", "size", 1, 2);
        assertThrows(NullPointerException.class, () -> view.hasKeysExactly(TestDomain.KEY, null));
    }

    @Test
    void statusesArePinnedInOrderAndBySize() {
        TransitionRecorder recorder = TransitionRecorder.create();
        Entity entity = new Entity("e", State.NEW);
        complete(recorder, TestDomain.KEY, applied(entity));
        complete(recorder, TestDomain.KEY, ignored(entity));
        RecordedTransitionsAssert view = recorder.assertRecorded();
        assertSame(view, view.hasStatusesExactly(OutcomeStatus.APPLIED, OutcomeStatus.IGNORED));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> view.hasStatusesExactly(OutcomeStatus.APPLIED, OutcomeStatus.APPLIED)),
                "recorded transitions[1]", "status", OutcomeStatus.APPLIED, OutcomeStatus.IGNORED);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> view.hasStatusesExactly(OutcomeStatus.APPLIED)),
                "recorded transitions", "size", 1, 2);
        assertThrows(NullPointerException.class, () -> view.hasStatusesExactly(OutcomeStatus.APPLIED, null));
    }

    @Test
    void anEmptyRecorderSatisfiesTheEmptyExpectations() {
        RecordedTransitionsAssert view = TransitionRecorder.create().assertRecorded();
        view.hasSize(0).hasKeysExactly().hasStatusesExactly();
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasSize(1)),
                "recorded transitions", "size", 1, 0);
    }

    // --- selection ------------------------------------------------------------------------------------

    @Test
    void selectionIsByKeyAndByEntityIdentity() {
        TransitionRecorder recorder = TransitionRecorder.create();
        Twin one = new Twin("same", State.NEW);
        Twin other = new Twin("same", State.NEW);
        assertEquals(one, other);
        complete(recorder, TWINS, new Outcome.Applied<>(one, State.NEW, TwinId.ADVANCE, State.READY,
                List.of(), List.of()));
        complete(recorder, TWINS, new Outcome.Applied<>(other, State.NEW, TwinId.ADVANCE, State.DONE,
                List.of(), List.of()));
        complete(recorder, TWIN_FLAGS, new Outcome.Applied<>(one, Flag.OFF, TwinId.ADVANCE, Flag.ON,
                List.of(), List.of()));
        complete(recorder, TestDomain.KEY, applied(new Entity("e", State.NEW)));
        RecordedTransitionsAssert view = recorder.assertRecorded();
        view.hasSize(4);
        view.forEntity(TWINS, one).hasSize(1).single().isApplied()
                .movedTo(State.READY).hasEntitySameAs(one);
        view.forEntity(TWINS, other).single().isApplied().movedTo(State.DONE);
    }

    @Test
    void aKeyThatEqualsTheRecordedKeyMatchesEvenAsAFreshInstance() {
        TransitionRecorder recorder = TransitionRecorder.create();
        Twin one = new Twin("one", State.NEW);
        MachineKey<Twin, State, TwinId> sameValue = MachineKey.of(Twin.class, State.class, TwinId.class);
        assertEquals(TWINS, sameValue);
        assertNotSame(TWINS, sameValue);
        complete(recorder, TWINS, new Outcome.Applied<>(one, State.NEW, TwinId.ADVANCE, State.READY,
                List.of(), List.of()));
        RecordedTransitionsAssert view = recorder.assertRecorded();
        assertSame(view, view.hasKeysExactly(sameValue));
        view.forEntity(sameValue, one).single().isApplied().movedTo(State.READY);
    }

    @Test
    void anEntityWithNoCallbackIsAnEmptyView() {
        TransitionRecorder recorder = TransitionRecorder.create();
        Entity entity = new Entity("e", State.NEW);
        Entity absent = new Entity("absent", State.NEW);
        complete(recorder, TestDomain.KEY, applied(entity));
        OutcomeSequenceAssert<Entity, State, Id> view =
                recorder.assertRecorded().forEntity(TestDomain.KEY, absent);
        assertSame(view, view.hasSize(0));
        String context = "recorded transitions for " + TestDomain.KEY;
        assertFailure(assertThrows(GraphAssertionError.class, view::single), context, "size", 1, 0);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.atIndex(0)),
                context, "index 0 present", Boolean.TRUE, Boolean.FALSE);
        assertEquals("index must not be negative: -1",
                assertThrows(IllegalArgumentException.class, () -> view.atIndex(-1)).getMessage());
    }

    @Test
    void aSequenceIsReadByPositionAndBySize() {
        TransitionRecorder recorder = TransitionRecorder.create();
        Entity entity = new Entity("e", State.NEW);
        complete(recorder, TestDomain.KEY, applied(entity));
        complete(recorder, TestDomain.KEY, ignored(entity));
        OutcomeSequenceAssert<Entity, State, Id> view =
                recorder.assertRecorded().forEntity(TestDomain.KEY, entity);
        view.hasSize(2).atIndex(0).isApplied().movedTo(State.READY);
        view.atIndex(1).isIgnored().hasRecordedState(State.NEW);
        String context = "recorded transitions for " + TestDomain.KEY;
        assertFailure(assertThrows(GraphAssertionError.class, view::single), context, "size", 1, 2);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasSize(1)),
                context, "size", 1, 2);
    }

    @Test
    void aNarrowedOutcomeKeepsTheSequenceContextAndItsPosition() {
        TransitionRecorder recorder = TransitionRecorder.create();
        Entity entity = new Entity("e", State.NEW);
        complete(recorder, TestDomain.KEY, applied(entity));
        complete(recorder, TestDomain.KEY, ignored(entity));
        OutcomeSequenceAssert<Entity, State, Id> view =
                recorder.assertRecorded().forEntity(TestDomain.KEY, entity);
        String context = "recorded transitions for " + TestDomain.KEY;
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.atIndex(1).isApplied()),
                context + "[1]", "status", OutcomeStatus.APPLIED, OutcomeStatus.IGNORED);
        assertFailure(
                assertThrows(GraphAssertionError.class,
                        () -> view.atIndex(0).hasRecordedState(State.NEW)),
                context + "[0]", "recorded state", State.NEW, State.READY);
    }

    /**
     * Narrowing keeps where the outcome came from: a failure after it names the position in the sequence as
     * well as the variant, so two outcomes of one entity that fail the same expectation are told apart.
     */
    @Test
    void aFailureAfterNarrowingNamesThePositionAndTheVariantItCameFrom() {
        TransitionRecorder recorder = TransitionRecorder.create();
        Entity entity = new Entity("e", State.NEW);
        complete(recorder, TestDomain.KEY, applied(entity));
        complete(recorder, TestDomain.KEY, new Outcome.Failed<>(entity, State.NEW, Id.PREPARE, State.READY,
                Id.RECOVER, State.FAILED, "boom", Optional.empty(), List.of(), List.of()));
        complete(recorder, TestDomain.KEY, new Outcome.Rejected<>(entity, State.NEW, Id.PREPARE, "not ready",
                RejectionKind.GUARD, Optional.of("first")));
        complete(recorder, TestDomain.KEY, ignored(entity));
        OutcomeSequenceAssert<Entity, State, Id> view =
                recorder.assertRecorded().forEntity(TestDomain.KEY, entity);
        String context = "recorded transitions for " + TestDomain.KEY;
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> view.atIndex(0).isApplied().movedTo(State.NEW)),
                context + "[0] (applied)", "moved to", State.NEW, State.READY);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> view.atIndex(1).isFailed().movedTo(State.READY)),
                context + "[1] (failed)", "moved to", State.READY, State.FAILED);
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> view.atIndex(2).isRejected().hasReason("other")),
                context + "[2] (rejected)", "reason", "other", "not ready");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> view.atIndex(3).isIgnored().hasReason(IgnoreReason.NO_RULE_MATCHED)),
                context + "[3] (ignored)", "reason", IgnoreReason.NO_RULE_MATCHED, IgnoreReason.NO_WATCH);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.atIndex(0).isApplied().records().hasSize(1)),
                context + "[0] (applied).records", "size", 1, 0);
    }

    @Test
    void nullSelectionArgumentsAreRejected() {
        TransitionRecorder recorder = TransitionRecorder.create();
        Entity entity = new Entity("e", State.NEW);
        complete(recorder, TestDomain.KEY, applied(entity));
        RecordedTransitionsAssert view = recorder.assertRecorded();
        assertThrows(NullPointerException.class, () -> view.forEntity(null, entity));
        assertThrows(NullPointerException.class, () -> view.forEntity(TestDomain.KEY, null));
    }

    @Test
    void anOutcomeThatDoesNotBelongToItsKeyIsAProgrammingError() {
        TransitionRecorder recorder = TransitionRecorder.create();
        Twin twin = new Twin("t", State.NEW);
        complete(recorder, TWINS, new Outcome.Applied<>(twin, Flag.OFF, TwinId.ADVANCE, Flag.ON, List.of(), List.of()));
        RecordedTransitionsAssert view = recorder.assertRecorded();
        assertTrue(assertThrows(IllegalArgumentException.class, () -> view.forEntity(TWINS, twin))
                .getMessage().startsWith("outcome does not belong to the machine: states"));
    }

    // --- concurrent calls ---------------------------------------------------------------------------

    @Test
    void everyConcurrentCallIsRecordedOnceWithItsOwnSequence() throws Exception {
        TransitionRecorder recorder = TransitionRecorder.create();
        int workers = 8;
        int perWorker = 64;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                Entity entity = new Entity("e" + worker, State.NEW);
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int call = 0; call < perWorker; call++) {
                        complete(recorder, TestDomain.KEY, applied(entity));
                    }
                    return entity;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        List<RecordedTransition> snapshot = recorder.snapshot();
        assertEquals(workers * perWorker, snapshot.size());
        assertEquals(LongStream.range(0, workers * perWorker).boxed().toList(),
                snapshot.stream().map(RecordedTransition::sequence).sorted().toList());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(snapshot.get(0)));
    }

    // --- against the real engine --------------------------------------------------------------------------

    /**
     * A reaction completes in its own call, before the call that triggered it, and each call is
     * recorded exactly once: the recorder never descends into the reactions the root outcome carries.
     */
    @Test
    void aReactionIsOneObservationOfItsOwnBeforeTheRootCall() {
        TransitionRecorder recorder = TransitionRecorder.create();
        Shipment shipment = new Shipment("s1", ShipmentStatus.OPEN);
        Parcel parcel = new Parcel("p1", ParcelStatus.LOADING, Optional.of(shipment));
        try (MachineRegistry registry = registry(recorder)) {
            registry.of(PARCELS).execute(parcel, Loading.LOAD);
        }
        RecordedTransitionsAssert recorded = recorder.assertRecorded();
        recorded.hasSize(2).hasKeysExactly(SHIPMENTS, PARCELS)
                .hasStatusesExactly(OutcomeStatus.APPLIED, OutcomeStatus.APPLIED);
        recorded.forEntity(SHIPMENTS, shipment).single().isApplied()
                .movedTo(ShipmentStatus.SENT).hasEntitySameAs(shipment);
        recorded.forEntity(PARCELS, parcel).single().isApplied().movedTo(ParcelStatus.LOADED);
    }

    /** The projection is what survives the run: the entities moved on afterwards, the recorded values did not. */
    @Test
    void aProjectionSurvivesTheTransitionsThatFollowIt() {
        TransitionRecorder recorder = TransitionRecorder.projecting(
                (MachineKey<?, ?, ?> key, Outcome<?, ?, ?> outcome) -> outcome.recordedState());
        Shipment shipment = new Shipment("s1", ShipmentStatus.OPEN);
        Parcel parcel = new Parcel("p1", ParcelStatus.LOADING, Optional.of(shipment));
        try (MachineRegistry registry = registry(recorder)) {
            registry.of(PARCELS).execute(parcel, Loading.LOAD);
        }
        shipment.setStatus(ShipmentStatus.OPEN);
        assertEquals(List.of(Optional.of(ShipmentStatus.SENT), Optional.of(ParcelStatus.LOADED)),
                recorder.snapshot().stream().map(RecordedTransition::projection).toList());
    }

    /**
     * Each recorded outcome keeps the call's flow: the recorder retains the values the call's steps put, by reference,
     * and the root entity, also when it projects the outcome.
     */
    @Test
    void theRecorderRetainsTheFlowOfEveryOutcomeItRecords() {
        FlowKey<StringBuilder> notes = FlowKey.of("notes", StringBuilder.class);
        StringBuilder prepared = new StringBuilder("prepared");
        TransitionRecorder recorder = TransitionRecorder.projecting(
                (MachineKey<?, ?, ?> key, Outcome<?, ?, ?> outcome) -> outcome.code());
        Entity entity = new Entity("e", State.NEW);
        GraphDefinition<Entity, State, Id> graph = TestDomain.graph(Duration.ofSeconds(5),
                (TransitionContext<Entity, State, Id> ctx) -> {
                    ctx.flow().put(notes, prepared);
                    return CompletableFuture.completedFuture(ActionOutcome.ok());
                });
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Entity::state, Entity::setState).identity(Entity::id)
                        .asyncTimeout(Duration.ofSeconds(5)))
                .observer(recorder)
                .build()) {
            registry.of(TestDomain.KEY).execute(entity, Id.PREPARE);
        }
        RecordedTransition recorded = recorder.snapshot().get(0);
        assertEquals(Optional.of(OutcomeCode.APPLIED), recorded.projection());
        assertSame(prepared, recorded.outcome().flow().get(notes).orElseThrow());
        assertSame(entity, recorded.outcome().flow().entity().orElseThrow());
    }

    /**
     * A call whose root machine records its trail: each recorded outcome keeps the trail of its flow, whose hop is the
     * very record the outcome lists and holds the entity and the throwable the failure route took, by reference.
     */
    @Test
    void theRecorderRetainsTheTrailOfEveryOutcomeItRecords() {
        IOException down = new IOException("gateway down");
        TransitionRecorder recorder = TransitionRecorder.create();
        Entity entity = new Entity("e", State.NEW);
        GraphDefinition<Entity, State, Id> graph = TestDomain.graph(Duration.ofSeconds(5),
                (TransitionContext<Entity, State, Id> ctx) -> CompletableFuture.failedFuture(down));
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Entity::state, Entity::setState).identity(Entity::id)
                        .asyncTimeout(Duration.ofSeconds(5)).recordTrail())
                .observer(recorder)
                .build()) {
            registry.of(TestDomain.KEY).execute(entity, Id.PREPARE);
        }
        Outcome<?, ?, ?> outcome = recorder.snapshot().get(0).outcome();
        FlowEntry.Hop hop = (FlowEntry.Hop) outcome.flow().trail().get(0);
        assertSame(outcome.records().get(0), hop.hop());
        assertSame(entity, hop.hop().entity());
        assertEquals(Id.RECOVER, hop.hop().transition());
        assertSame(down, hop.recovery().orElseThrow().cause().orElseThrow());
    }

    /**
     * An observer that throws beside the recorder changes neither the call nor what the recorder kept. The library
     * logs the exception as a warning, which the test captures from the {@code java.util.logging} logger the library
     * writes to, so that nothing reaches the console.
     */
    @Test
    void anObserverThatThrowsBesideTheRecorderChangesNothing() {
        TransitionRecorder recorder = TransitionRecorder.create();
        IllegalStateException down = new IllegalStateException("audit down");
        MachineObserver failing = new MachineObserver() {
            @Override
            public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                throw down;
            }
        };
        Parcel parcel = new Parcel("p1", ParcelStatus.LOADING, Optional.empty());
        List<LogRecord> logged = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        Logger library = Logger.getLogger("io.github.graphmaquina");
        boolean useParentHandlers = library.getUseParentHandlers();
        library.addHandler(capture);
        library.setUseParentHandlers(false);
        try (MachineRegistry registry = registry(failing, recorder)) {
            assertEquals(OutcomeStatus.APPLIED, registry.of(PARCELS).execute(parcel, Loading.LOAD).status());
        } finally {
            library.removeHandler(capture);
            library.setUseParentHandlers(useParentHandlers);
        }
        recorder.assertRecorded().hasSize(1).forEntity(PARCELS, parcel).single()
                .isApplied().movedTo(ParcelStatus.LOADED);
        assertEquals(1, logged.size());
        assertEquals(Level.WARNING, logged.get(0).getLevel());
        assertEquals("the observer " + failing.getClass().getName() + " threw in onCompleted for " + PARCELS + " "
                + Loading.LOAD.name(), logged.get(0).getMessage());
        assertSame(down, logged.get(0).getThrown());
    }

    // --- fixtures -------------------------------------------------------------------------------------------

    private static final MachineKey<Twin, State, TwinId> TWINS =
            MachineKey.of(Twin.class, State.class, TwinId.class);
    private static final MachineKey<Twin, Flag, TwinId> TWIN_FLAGS =
            MachineKey.of(Twin.class, Flag.class, TwinId.class);
    private static final MachineKey<Shipment, ShipmentStatus, Sending> SHIPMENTS =
            MachineKey.of(Shipment.class, ShipmentStatus.class, Sending.class);
    private static final MachineKey<Parcel, ParcelStatus, Loading> PARCELS =
            MachineKey.of(Parcel.class, ParcelStatus.class, Loading.class);

    /**
     * A second state enum of {@link Twin}, which makes it the entity of a second machine: a registry holds at most
     * one machine per entity class and state enum.
     */
    enum Flag { OFF, ON }

    /** Transition identifiers of the twin machines. */
    enum TwinId { ADVANCE }

    /** States of {@link Shipment}: the machine that watches. */
    enum ShipmentStatus { OPEN, SENT }

    /** Transition identifiers of the shipment machine. */
    enum Sending { SEND }

    /** States of {@link Parcel}: the machine that is watched. */
    enum ParcelStatus { LOADING, LOADED }

    /** Transition identifiers of the parcel machine. */
    enum Loading { LOAD }

    /** Entity that compares equal to any other twin carrying the same id, so identity and equality differ. */
    static final class Twin {

        private final String id;
        private State state;

        Twin(String id, State state) {
            this.id = id;
            this.state = state;
        }

        State state() { return state; }

        void setState(State state) { this.state = state; }

        @Override
        public boolean equals(Object other) {
            return other instanceof Twin twin && twin.id.equals(id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    /** Owner entity of the engine cases; its parcels register themselves as they are built. */
    static final class Shipment {

        private final String id;
        private final List<Parcel> parcels = new ArrayList<>();
        private ShipmentStatus status;

        Shipment(String id, ShipmentStatus status) {
            this.id = id;
            this.status = status;
        }

        String id() { return id; }

        List<Parcel> parcels() { return parcels; }

        ShipmentStatus status() { return status; }

        void setStatus(ShipmentStatus status) { this.status = status; }
    }

    /** Peer entity of the engine cases: moving one re-evaluates its shipment. */
    static final class Parcel {

        private final String id;
        private final Optional<Shipment> shipment;
        private ParcelStatus status;

        Parcel(String id, ParcelStatus status, Optional<Shipment> shipment) {
            this.id = id;
            this.status = status;
            this.shipment = shipment;
            shipment.ifPresent((Shipment owner) -> owner.parcels().add(this));
        }

        String id() { return id; }

        Optional<Shipment> shipment() { return shipment; }

        ParcelStatus status() { return status; }

        void setStatus(ParcelStatus status) { this.status = status; }
    }

    private static Outcome<Entity, State, Id> applied(Entity entity) {
        return new Outcome.Applied<>(entity, State.NEW, Id.PREPARE, State.READY, List.of(), List.of());
    }

    private static Outcome<Entity, State, Id> ignored(Entity entity) {
        return new Outcome.Ignored<>(entity, State.NEW, IgnoreReason.NO_WATCH);
    }

    private static MachineRegistry registry(MachineObserver... observers) {
        GraphDefinition<Shipment, ShipmentStatus, Sending> shipments = StateGraph.define(SHIPMENTS)
                .initial(ShipmentStatus.OPEN).terminal(ShipmentStatus.SENT)
                .statePolicy(ShipmentStatus.OPEN, "an open shipment only sends")
                .transition(Sending.SEND, ShipmentStatus.OPEN, ShipmentStatus.SENT)
                    .action("SendShipment",
                            (TransitionContext<Shipment, ShipmentStatus, Sending> ctx) -> ActionOutcome.ok())
                    .endTransition()
                .watch(ShipmentStatus.OPEN, PARCELS, Shipment::parcels, Parcel::shipment)
                    .when(allIn(ParcelStatus.LOADED)).execute(Sending.SEND)
                    .endWatch()
                .build();
        GraphDefinition<Parcel, ParcelStatus, Loading> parcels = StateGraph.define(PARCELS)
                .initial(ParcelStatus.LOADING).terminal(ParcelStatus.LOADED)
                .statePolicy(ParcelStatus.LOADING, "a loading parcel only loads")
                .transition(Loading.LOAD, ParcelStatus.LOADING, ParcelStatus.LOADED).endTransition()
                .build();
        MachineRegistry.Builder builder = MachineRegistry.builder()
                .add(StateMachines.from(shipments)
                        .state(Shipment::status, Shipment::setStatus).identity(Shipment::id))
                .add(StateMachines.from(parcels)
                        .state(Parcel::status, Parcel::setStatus).identity(Parcel::id));
        for (MachineObserver observer : observers) {
            builder.observer(observer);
        }
        return builder.build();
    }

    private static void assertFailure(
            GraphAssertionError error, String context, String property, Object expected, Object actual) {
        assertEquals(context, error.context());
        assertEquals(property, error.property());
        assertEquals(expected, error.expected());
        assertEquals(actual, error.actual());
    }
}
