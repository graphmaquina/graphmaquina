package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SEND_BACK;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static io.github.graphmaquina.fixtures.Keys.ITEMS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeStatus;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.ReentrantTransitionException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.WatchChainExceededException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.Item;
import io.github.graphmaquina.fixtures.ItemStatus;
import io.github.graphmaquina.fixtures.ItemTransition;
import io.github.graphmaquina.fixtures.LibraryLog;
import io.github.graphmaquina.fixtures.Order;
import io.github.graphmaquina.fixtures.OrderGraphs;
import io.github.graphmaquina.fixtures.OrderStatus;
import io.github.graphmaquina.fixtures.OrderTransition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

/**
 * The registry's observers: every call starts once and ends once on its own thread, a watch reaction is a call nested
 * in the one that triggered it, the end event carries the very outcome or throwable the caller receives with the
 * phase and the states written, and an observer can neither change a call nor start one.
 */
class ObserverTest {

    /** Records every event as text, with the call, the thread and what the event delivered. */
    static final class Recorder implements MachineObserver {

        final List<String> events = new ArrayList<>();
        final List<MachineCall> calls = new ArrayList<>();
        final List<Thread> threads = new ArrayList<>();
        final List<Object> delivered = new ArrayList<>();
        final List<Duration> elapsed = new ArrayList<>();

        @Override
        public synchronized void onStarted(MachineCall call) {
            record(call, "started " + call, call);
        }

        @Override
        public synchronized void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
            this.elapsed.add(elapsed);
            record(call, "completed " + call + " " + outcome.status(), outcome);
        }

        @Override
        public synchronized void onFailed(MachineCall call, CallFailure failure, Duration elapsed) {
            this.elapsed.add(elapsed);
            record(call, "failed " + call + " " + failure.phase() + " " + failure.statesWritten() + " "
                    + failure.error().getClass().getSimpleName(), failure.error());
        }

        private void record(MachineCall call, String event, Object payload) {
            events.add(event);
            calls.add(call);
            threads.add(Thread.currentThread());
            delivered.add(payload);
        }
    }

    /** A graph and the setter its machine writes with. */
    private record Scenario(GraphDefinition<Doc, DocStatus, DocTransition> graph, BiConsumer<Doc, DocStatus> setter) { }

    /** DRAFT -SUBMIT-> REVIEW, shaped by {@code submit}; DISCARD, PUBLISH and ARCHIVE are plain moves. */
    private static GraphDefinition<Doc, DocStatus, DocTransition> docs(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit) {
        return submit.apply(StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                        .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                        .transition(SUBMIT, DRAFT, REVIEW))
                .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    private static GraphDefinition<Doc, DocStatus, DocTransition> plain() {
        return docs(UnaryOperator.<TransitionBuilder<Doc, DocStatus, DocTransition>>identity());
    }

    private static MachineRegistry registry(GraphDefinition<Doc, DocStatus, DocTransition> graph,
                                            BiConsumer<Doc, DocStatus> setter, MachineObserver... observers) {
        MachineRegistry.Builder builder = MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Doc::status, setter).identity(Doc::id)
                        .asyncTimeout(Duration.ofSeconds(5)));
        for (MachineObserver observer : observers) {
            builder.observer(observer);
        }
        return builder.build();
    }

    /** Orders watch their items from PAID and ship once every item is packed. */
    private static MachineRegistry orders(MachineObserver observer) {
        return MachineRegistry.builder()
                .add(StateMachines.from(OrderGraphs.order(
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                                        CompletableFuture.completedFuture(ActionOutcome.ok()),
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass(),
                                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass()))
                        .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5)))
                .add(StateMachines.from(OrderGraphs.item(
                                (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> ActionOutcome.ok()))
                        .state(Item::status, Item::setStatus).identity(Item::id))
                .observer(observer)
                .build();
    }

    /** What the library logged, each record as {@code LEVEL: message | thrown}. */
    private static List<String> logged(LibraryLog log) {
        List<String> records = new ArrayList<>();
        for (LogRecord record : log.records()) {
            records.add(record.getLevel() + ": " + record.getMessage() + " | " + record.getThrown());
        }
        return records;
    }

    /** The warning the library logs when {@code observer} throws {@code thrown} in {@code callback} of DOCS SUBMIT. */
    private static String warning(MachineObserver observer, String callback, Throwable thrown) {
        return "WARNING: the observer " + observer.getClass().getName() + " threw in " + callback
                + " for Doc/DocStatus SUBMIT | " + thrown;
    }

    @Test
    void everyCallStartsOnceAndEndsOnceOnTheCallingThread() {
        Recorder recorder = new Recorder();
        try (MachineRegistry registry = registry(plain(), Doc::setStatus, recorder)) {
            Doc doc = new Doc("1", DRAFT);
            registry.of(DOCS).execute(doc, SUBMIT);
            registry.of(DOCS).execute(doc, SUBMIT);
            registry.of(DOCS).reevaluate(doc);
        }
        assertEquals(List.of(
                "started Doc/DocStatus SUBMIT", "completed Doc/DocStatus SUBMIT APPLIED",
                "started Doc/DocStatus SUBMIT", "completed Doc/DocStatus SUBMIT REJECTED",
                "started Doc/DocStatus reevaluate", "completed Doc/DocStatus reevaluate IGNORED"), recorder.events);
        for (int event = 0; event < recorder.calls.size(); event += 2) {
            assertSame(recorder.calls.get(event), recorder.calls.get(event + 1));
        }
        assertNotSame(recorder.calls.get(0), recorder.calls.get(2));
        for (Thread thread : recorder.threads) {
            assertSame(Thread.currentThread(), thread);
        }
    }

    /** A null or foreign argument, or a call made where no call may start, is refused before it starts. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aRequestRefusedBeforeItStartsNotifiesNothing() {
        Recorder recorder = new Recorder();
        AtomicReference<MachineRegistry> self = new AtomicReference<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            self.get().of(DOCS).execute(new Doc("2", DRAFT), DISCARD);
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph, Doc::setStatus, recorder)) {
            self.set(registry);
            StateMachine raw = registry.of(DOCS);
            assertThrows(NullPointerException.class, () -> registry.of(DOCS).execute(null, SUBMIT));
            assertThrows(IllegalArgumentException.class, () -> raw.execute(new Doc("3", DRAFT), ItemTransition.PACK));
            assertEquals(List.of(), recorder.events);
            assertThrows(RuntimeException.class, () -> registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT));
        }
        assertEquals(2, recorder.events.size());
        assertEquals("started Doc/DocStatus SUBMIT", recorder.events.get(0));
        assertTrue(recorder.events.get(1).startsWith("failed Doc/DocStatus SUBMIT ACTION NONE "),
                recorder.events.get(1));
    }

    @Test
    void aWatchReactionIsACallNestedInTheCallThatTriggeredIt() {
        Recorder recorder = new Recorder();
        try (MachineRegistry registry = orders(recorder)) {
            Order order = new Order("o1", OrderStatus.PAID);
            Item item = new Item("i1", ItemStatus.PACKING, Optional.of(order));
            registry.of(ITEMS).execute(item, ItemTransition.PACK);
        }
        assertEquals(List.of(
                "started Item/ItemStatus PACK",
                "started Order/OrderStatus reevaluate (watch reaction)",
                "completed Order/OrderStatus reevaluate (watch reaction) APPLIED",
                "completed Item/ItemStatus PACK APPLIED"), recorder.events);
        assertTrue(recorder.calls.get(1).reaction());
        assertEquals(Optional.empty(), recorder.calls.get(1).transition());
    }

    @Test
    void onCompletedReceivesTheVeryOutcomeTheCallerReceives() {
        Recorder recorder = new Recorder();
        try (MachineRegistry registry = registry(plain(), Doc::setStatus, recorder)) {
            Outcome<Doc, DocStatus, DocTransition> outcome = registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT);
            assertSame(outcome, recorder.delivered.get(1));
        }
    }

    @Test
    void onFailedReceivesTheVeryFailureWithItsPhaseAndTheStatesWritten() {
        IllegalStateException boom = new IllegalStateException("boom");
        Map<String, Scenario> scenarios = new LinkedHashMap<>();
        scenarios.put("guard", new Scenario(docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .guard("CanSubmit", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                    throw boom;
                })), Doc::setStatus));
        scenarios.put("action", new Scenario(docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    throw boom;
                })), Doc::setStatus));
        scenarios.put("pre-check", new Scenario(docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    ctx.entity().setStatus(PUBLISHED);
                    return ActionOutcome.ok();
                })), Doc::setStatus));
        scenarios.put("setter", new Scenario(plain(), (Doc doc, DocStatus status) -> {
            throw boom;
        }));
        scenarios.put("hook", new Scenario(docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .afterCommit("Notify", (Doc doc) -> {
                    throw boom;
                })), Doc::setStatus));
        List<String> ends = new ArrayList<>();
        for (Map.Entry<String, Scenario> scenario : scenarios.entrySet()) {
            Recorder recorder = new Recorder();
            try (MachineRegistry registry = registry(scenario.getValue().graph(), scenario.getValue().setter(),
                    recorder)) {
                RuntimeException thrown = assertThrows(RuntimeException.class,
                        () -> registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT));
                assertSame(thrown, recorder.delivered.get(1));
                ends.add(scenario.getKey() + ": " + recorder.events.get(1));
            }
        }
        assertEquals(List.of(
                "guard: failed Doc/DocStatus SUBMIT GUARD NONE TransitionAbortedException",
                "action: failed Doc/DocStatus SUBMIT ACTION NONE TransitionAbortedException",
                "pre-check: failed Doc/DocStatus SUBMIT COMMIT NONE CommitFailedException",
                "setter: failed Doc/DocStatus SUBMIT COMMIT PARTIAL CommitFailedException",
                "hook: failed Doc/DocStatus SUBMIT HOOK ALL AfterCommitException"), ends);
    }

    @Test
    void anErrorIsObservedWithItsPhaseAndPropagatesUnchanged() {
        AssertionError broken = new AssertionError("broken");
        Map<String, Scenario> scenarios = new LinkedHashMap<>();
        scenarios.put("action", new Scenario(docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    throw broken;
                })), Doc::setStatus));
        scenarios.put("setter", new Scenario(plain(), (Doc doc, DocStatus status) -> {
            throw broken;
        }));
        scenarios.put("hook", new Scenario(docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .afterCommit("Notify", (Doc doc) -> {
                    throw broken;
                })), Doc::setStatus));
        List<String> ends = new ArrayList<>();
        for (Map.Entry<String, Scenario> scenario : scenarios.entrySet()) {
            Recorder recorder = new Recorder();
            try (MachineRegistry registry = registry(scenario.getValue().graph(), scenario.getValue().setter(),
                    recorder)) {
                assertSame(broken, assertThrows(AssertionError.class,
                        () -> registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT)));
                assertSame(broken, recorder.delivered.get(1));
                ends.add(scenario.getKey() + ": " + recorder.events.get(1));
            }
        }
        assertEquals(List.of(
                "action: failed Doc/DocStatus SUBMIT ACTION NONE AssertionError",
                "setter: failed Doc/DocStatus SUBMIT COMMIT PARTIAL AssertionError",
                "hook: failed Doc/DocStatus SUBMIT HOOK ALL AssertionError"), ends);
    }

    /** The reaction ends in RESOLVE and the call that triggered it in REACTIONS, after its own commit. */
    @Test
    void anErrorFromAReactionEndsTheReactionAndTheCallThatTriggeredIt() {
        AssertionError broken = new AssertionError("condition broken");
        Doc observer = new Doc("observer", PUBLISHED);
        Doc watched = new Doc("watched", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(PUBLISHED, DOCS, (Doc doc) -> List.of(watched),
                        (Doc moved) -> moved == watched ? Optional.of(observer) : Optional.<Doc>empty())
                    .when("broken", (Doc doc, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> {
                        throw broken;
                    }).execute(ARCHIVE)
                    .endWatch()
                .build();
        Recorder recorder = new Recorder();
        try (MachineRegistry registry = registry(graph, Doc::setStatus, recorder)) {
            assertSame(broken, assertThrows(AssertionError.class, () -> registry.of(DOCS).execute(watched, SUBMIT)));
        }
        assertEquals(List.of(
                "started Doc/DocStatus SUBMIT",
                "started Doc/DocStatus reevaluate (watch reaction)",
                "failed Doc/DocStatus reevaluate (watch reaction) RESOLVE NONE AssertionError",
                "failed Doc/DocStatus SUBMIT REACTIONS ALL AssertionError"), recorder.events);
        assertEquals(REVIEW, watched.status());
        assertEquals(PUBLISHED, observer.status());
    }

    /** The reaction that went over maxWatchChain ends in RESOLVE with the very abort its Reaction.Aborted carries. */
    @Test
    void aReactionOverTheWatchChainLimitFailsWithTheAbortItsReactionCarries() {
        GraphDefinition<Doc, DocStatus, DocTransition> pingPong = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(DRAFT, DOCS, (Doc doc) -> List.of(doc), (Doc moved) -> Optional.of(moved))
                    .when("always", (Doc doc, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> true).execute(SUBMIT)
                    .endWatch()
                .watch(REVIEW, DOCS, (Doc doc) -> List.of(doc), (Doc moved) -> Optional.of(moved))
                    .when("always", (Doc doc, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> true).execute(SEND_BACK)
                    .endWatch()
                .build();
        Recorder recorder = new Recorder();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(pingPong).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .maxWatchChain(1)
                .observer(recorder)
                .build()) {
            Outcome.Applied<?, ?, ?> root = assertInstanceOf(Outcome.Applied.class,
                    registry.of(DOCS).execute(new Doc("d1", DRAFT), SUBMIT));
            Reaction.Done first = assertInstanceOf(Reaction.Done.class, root.reactions().get(0));
            Outcome.Applied<?, ?, ?> sentBack = assertInstanceOf(Outcome.Applied.class, first.outcome());
            Reaction.Aborted second = assertInstanceOf(Reaction.Aborted.class, sentBack.reactions().get(0));
            WatchChainExceededException cause = assertInstanceOf(WatchChainExceededException.class, second.cause());
            assertTrue(recorder.calls.get(2).reaction());
            assertEquals("failed Doc/DocStatus reevaluate (watch reaction) RESOLVE NONE WatchChainExceededException",
                    recorder.events.get(3));
            assertSame(cause, recorder.delivered.get(3));
            assertSame(cause.call(), recorder.calls.get(3));
            assertSame(recorder.calls.get(2), recorder.calls.get(3));
        }
    }

    /** The reaction whose selected action throws ends in ACTION, and the call that triggered it still completes. */
    @Test
    void aReactionWhoseActionThrowsFailsInActionWithTheAbortItsReactionCarries() {
        IllegalStateException boom = new IllegalStateException("archive down");
        Doc observer = new Doc("observer", PUBLISHED);
        Doc watched = new Doc("watched", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED)
                    .action("Archive", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        throw boom;
                    })
                    .endTransition()
                .watch(PUBLISHED, DOCS, (Doc doc) -> List.of(watched),
                        (Doc moved) -> moved == watched ? Optional.of(observer) : Optional.<Doc>empty())
                    .when("always", (Doc doc, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> true).execute(ARCHIVE)
                    .endWatch()
                .build();
        Recorder recorder = new Recorder();
        try (MachineRegistry registry = registry(graph, Doc::setStatus, recorder)) {
            Outcome.Applied<?, ?, ?> applied = assertInstanceOf(Outcome.Applied.class,
                    registry.of(DOCS).execute(watched, SUBMIT));
            Reaction.Aborted aborted = assertInstanceOf(Reaction.Aborted.class, applied.reactions().get(0));
            TransitionAbortedException cause = assertInstanceOf(TransitionAbortedException.class, aborted.cause());
            assertSame(boom, cause.getCause());
            assertEquals(List.of(
                    "started Doc/DocStatus SUBMIT",
                    "started Doc/DocStatus reevaluate (watch reaction)",
                    "failed Doc/DocStatus reevaluate (watch reaction) ACTION NONE TransitionAbortedException",
                    "completed Doc/DocStatus SUBMIT APPLIED"), recorder.events);
            assertSame(cause, recorder.delivered.get(2));
            assertSame(cause.call(), recorder.calls.get(2));
        }
    }

    @Test
    void anExceptionFromAnObserverChangesNeitherTheCallNorTheOtherObservers() {
        IllegalStateException boom = new IllegalStateException("metrics down");
        MachineObserver failing = new MachineObserver() {
            @Override
            public void onStarted(MachineCall call) {
                throw boom;
            }

            @Override
            public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                throw boom;
            }
        };
        Recorder after = new Recorder();
        Doc doc = new Doc("1", DRAFT);
        try (LibraryLog log = LibraryLog.capture();
             MachineRegistry registry = registry(plain(), Doc::setStatus, failing, after)) {
            assertEquals(OutcomeStatus.APPLIED, registry.of(DOCS).execute(doc, SUBMIT).status());
            assertEquals(List.of(warning(failing, "onStarted", boom), warning(failing, "onCompleted", boom)),
                    logged(log));
        }
        assertEquals(REVIEW, doc.status());
        assertEquals(List.of("started Doc/DocStatus SUBMIT", "completed Doc/DocStatus SUBMIT APPLIED"), after.events);
    }

    @Test
    void anObserverWhoseOnStartedThrewStillReceivesItsEndEvent() {
        List<String> seen = new ArrayList<>();
        IllegalStateException notReady = new IllegalStateException("not ready");
        MachineObserver observer = new MachineObserver() {
            @Override
            public void onStarted(MachineCall call) {
                seen.add("started");
                throw notReady;
            }

            @Override
            public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                seen.add("completed");
            }
        };
        try (LibraryLog log = LibraryLog.capture();
             MachineRegistry registry = registry(plain(), Doc::setStatus, observer)) {
            registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT);
            assertEquals(List.of(warning(observer, "onStarted", notReady)), logged(log));
        }
        assertEquals(List.of("started", "completed"), seen);
    }

    @Test
    void anErrorFromAnObserverPropagatesAfterTheCommitAndFreesTheThread() {
        AssertionError broken = new AssertionError("observer invariant");
        MachineObserver observer = new MachineObserver() {
            @Override
            public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                if (((Doc) call.entity()).id().equals("1")) {
                    throw broken;
                }
            }
        };
        Doc doc = new Doc("1", DRAFT);
        try (MachineRegistry registry = registry(plain(), Doc::setStatus, observer)) {
            assertSame(broken, assertThrows(AssertionError.class, () -> registry.of(DOCS).execute(doc, SUBMIT)));
            assertEquals(REVIEW, doc.status());
            assertEquals(OutcomeStatus.APPLIED, registry.of(DOCS).execute(new Doc("2", DRAFT), SUBMIT).status());
        }
    }

    @Test
    void anObserverCannotStartATransition() {
        Doc other = new Doc("2", DRAFT);
        AtomicReference<MachineRegistry> self = new AtomicReference<>();
        List<ReentrantTransitionException> refusals = new ArrayList<>();
        MachineObserver observer = new MachineObserver() {
            @Override
            public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                try {
                    self.get().of(DOCS).execute(other, SUBMIT);
                } catch (ReentrantTransitionException refused) {
                    refusals.add(refused);
                    throw refused;
                }
            }
        };
        try (LibraryLog log = LibraryLog.capture();
             MachineRegistry registry = registry(plain(), Doc::setStatus, observer)) {
            self.set(registry);
            assertEquals(OutcomeStatus.APPLIED, registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT).status());
            assertEquals(1, refusals.size());
            assertEquals(List.of(warning(observer, "onCompleted", refusals.get(0))), logged(log));
        }
        assertEquals(DRAFT, other.status());
    }

    @Test
    void observersAreNotifiedInTheOrderTheyWereAdded() {
        List<String> seen = new ArrayList<>();
        try (MachineRegistry registry = registry(plain(), Doc::setStatus, named("first", seen),
                named("second", seen))) {
            registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT);
        }
        assertEquals(List.of("first started", "second started", "first completed", "second completed"), seen);
    }

    private static MachineObserver named(String name, List<String> seen) {
        return new MachineObserver() {
            @Override
            public void onStarted(MachineCall call) {
                seen.add(name + " started");
            }

            @Override
            public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                seen.add(name + " completed");
            }
        };
    }

    @Test
    void anAsyncCallIsObservedOnTheExecutorThreadBeforeItsFutureCompletes() {
        Recorder recorder = new Recorder();
        try (MachineRegistry registry = registry(plain(), Doc::setStatus, recorder)) {
            Outcome<Doc, DocStatus, DocTransition> outcome =
                    registry.of(DOCS).async().execute(new Doc("1", DRAFT), SUBMIT).join();
            synchronized (recorder) {
                assertEquals(List.of("started Doc/DocStatus SUBMIT", "completed Doc/DocStatus SUBMIT APPLIED"),
                        recorder.events);
                assertSame(recorder.threads.get(0), recorder.threads.get(1));
                assertNotSame(Thread.currentThread(), recorder.threads.get(0));
                assertSame(outcome, recorder.delivered.get(1));
            }
        }
    }

    @Test
    void elapsedCoversTheWholeCall() {
        long[] inside = new long[1];
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            long start = System.nanoTime();
                            LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
                            inside[0] = System.nanoTime() - start;
                            return ActionOutcome.ok();
                        }));
        Recorder recorder = new Recorder();
        try (MachineRegistry registry = registry(graph, Doc::setStatus, recorder)) {
            registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT);
        }
        assertTrue(recorder.elapsed.get(0).toNanos() >= inside[0],
                recorder.elapsed.get(0) + " must cover the action's " + inside[0] + " ns");
    }

    /** One observer, two calls at once on two threads; each thread sees its own start and end. */
    @Test
    void concurrentCallsAreObservedEachOnItsOwnThread() throws Exception {
        CountDownLatch bothInside = new CountDownLatch(2);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            bothInside.countDown();
                            try {
                                bothInside.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return ActionOutcome.ok();
                        }));
        Recorder recorder = new Recorder();
        try (MachineRegistry registry = registry(graph, Doc::setStatus, recorder);
             ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Outcome<Doc, DocStatus, DocTransition>> first =
                    pool.submit(() -> registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT));
            Future<Outcome<Doc, DocStatus, DocTransition>> second =
                    pool.submit(() -> registry.of(DOCS).execute(new Doc("2", DRAFT), SUBMIT));
            assertEquals(OutcomeStatus.APPLIED, first.get(10, TimeUnit.SECONDS).status());
            assertEquals(OutcomeStatus.APPLIED, second.get(10, TimeUnit.SECONDS).status());
        }
        synchronized (recorder) {
            assertEquals(4, recorder.events.size());
            Map<Thread, List<Integer>> byThread = new LinkedHashMap<>();
            for (int event = 0; event < recorder.threads.size(); event++) {
                byThread.computeIfAbsent(recorder.threads.get(event), (Thread thread) -> new ArrayList<>()).add(event);
            }
            assertEquals(2, byThread.size());
            for (List<Integer> events : byThread.values()) {
                assertEquals(2, events.size());
                assertTrue(recorder.events.get(events.get(0)).startsWith("started "));
                assertTrue(recorder.events.get(events.get(1)).startsWith("completed "));
                assertSame(recorder.calls.get(events.get(0)), recorder.calls.get(events.get(1)));
            }
        }
    }
}
