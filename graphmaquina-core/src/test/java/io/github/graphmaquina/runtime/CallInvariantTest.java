package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static io.github.graphmaquina.fixtures.Keys.ITEMS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.StateMachineException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.Item;
import io.github.graphmaquina.fixtures.ItemStatus;
import io.github.graphmaquina.fixtures.ItemTransition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The contract of a call: whatever ends it in an exception, it throws a CallFailedException of that very call, the
 * instance its observer saw start, never anything else, and the observer is told the same phase and states written.
 */
class CallInvariantTest {

    /** Keeps the calls that started and the failures it was told of, in order. */
    static final class Recorder implements MachineObserver {

        final List<MachineCall> started = new ArrayList<>();
        final List<CallFailure> failed = new ArrayList<>();

        @Override
        public synchronized void onStarted(MachineCall call) {
            started.add(call);
        }

        @Override
        public synchronized void onFailed(MachineCall call, CallFailure failure, Duration elapsed) {
            failed.add(failure);
        }
    }

    /** One way to end a call in an exception: the registry it runs on and the call it makes. */
    private interface Scenario {
        void run(Recorder recorder);
    }

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
                                            Function<Doc, DocStatus> getter, BiConsumer<Doc, DocStatus> setter,
                                            Function<Doc, Object> identity, MachineObserver observer) {
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(getter, setter).identity(identity)
                        .asyncTimeout(Duration.ofSeconds(5)))
                .observer(observer)
                .build();
    }

    private static void execute(Recorder recorder, GraphDefinition<Doc, DocStatus, DocTransition> graph,
                                Function<Doc, DocStatus> getter, BiConsumer<Doc, DocStatus> setter,
                                Function<Doc, Object> identity) {
        try (MachineRegistry registry = registry(graph, getter, setter, identity, recorder)) {
            registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT);
        }
    }

    @Test
    void everyExceptionACallThrowsIsAFailureOfThatCall() {
        IllegalStateException boom = new IllegalStateException("boom");
        Map<String, Scenario> scenarios = new LinkedHashMap<>();
        scenarios.put("identity throws", (Recorder recorder) -> execute(recorder, plain(), Doc::status, Doc::setStatus,
                (Doc doc) -> {
                    throw boom;
                }));
        scenarios.put("getter returns null", (Recorder recorder) -> execute(recorder, plain(), (Doc doc) -> null,
                Doc::setStatus, Doc::id));
        scenarios.put("condition throws", (Recorder recorder) -> {
            GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT)
                    .terminal(ARCHIVED)
                    .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                    .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                    .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                    .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                    .watch(PUBLISHED, DOCS, (Doc doc) -> List.of(doc), (Doc moved) -> Optional.<Doc>empty())
                        .when("rule", (Doc doc, Collection<? extends Doc> peers,
                                PeerView<Doc, DocStatus, DocTransition> view) -> {
                            throw boom;
                        }).execute(ARCHIVE)
                        .endWatch()
                    .build();
            try (MachineRegistry registry = registry(graph, Doc::status, Doc::setStatus, Doc::id, recorder)) {
                registry.of(DOCS).reevaluate(new Doc("1", PUBLISHED));
            }
        });
        scenarios.put("guard throws a library exception", (Recorder recorder) -> execute(recorder, docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("CanSubmit",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw new StateMachineException("not mine");
                        })), Doc::status, Doc::setStatus, Doc::id));
        scenarios.put("action starts a call", (Recorder recorder) -> {
            AtomicReference<MachineRegistry> self = new AtomicReference<>();
            GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                    (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                            (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                                self.get().of(DOCS).execute(new Doc("2", DRAFT), DISCARD);
                                return ActionOutcome.ok();
                            }));
            try (MachineRegistry registry = registry(graph, Doc::status, Doc::setStatus, Doc::id, recorder)) {
                self.set(registry);
                registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT);
            }
        });
        scenarios.put("cascade to an unknown machine", (Recorder recorder) -> execute(recorder, docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.cascade(ITEMS, new Item("i1", ItemStatus.PACKING, Optional.empty()),
                                    ItemTransition.PACK);
                            return ActionOutcome.ok();
                        })), Doc::status, Doc::setStatus, Doc::id));
        scenarios.put("pre-check getter throws", (Recorder recorder) -> {
            AtomicInteger reads = new AtomicInteger();
            execute(recorder, plain(), (Doc doc) -> {
                if (reads.incrementAndGet() == 2) {
                    throw boom;
                }
                return doc.status();
            }, Doc::setStatus, Doc::id);
        });
        scenarios.put("setter throws", (Recorder recorder) -> execute(recorder, plain(), Doc::status,
                (Doc doc, DocStatus status) -> {
                    throw boom;
                }, Doc::id));
        scenarios.put("hook throws", (Recorder recorder) -> execute(recorder, docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.afterCommit("Notify",
                        (Doc doc) -> {
                            throw boom;
                        })), Doc::status, Doc::setStatus, Doc::id));
        List<String> ends = new ArrayList<>();
        for (Map.Entry<String, Scenario> scenario : scenarios.entrySet()) {
            Recorder recorder = new Recorder();
            RuntimeException thrown = assertThrows(RuntimeException.class, () -> scenario.getValue().run(recorder));
            CallFailedException failure = assertInstanceOf(CallFailedException.class, thrown, scenario.getKey());
            assertEquals(1, recorder.started.size(), scenario.getKey());
            assertSame(recorder.started.get(0), failure.call(), scenario.getKey());
            CallFailure told = recorder.failed.get(0);
            assertSame(failure, told.error(), scenario.getKey());
            assertEquals(failure.phase(), told.phase(), scenario.getKey());
            assertEquals(failure.statesWritten(), told.statesWritten(), scenario.getKey());
            ends.add(scenario.getKey() + ": " + failure.getClass().getSimpleName() + " " + failure.phase() + " "
                    + failure.statesWritten());
        }
        assertEquals(List.of(
                "identity throws: TransitionAbortedException RESOLVE NONE",
                "getter returns null: TransitionAbortedException RESOLVE NONE",
                "condition throws: TransitionAbortedException RESOLVE NONE",
                "guard throws a library exception: TransitionAbortedException GUARD NONE",
                "action starts a call: TransitionAbortedException ACTION NONE",
                "cascade to an unknown machine: TransitionAbortedException ACTION NONE",
                "pre-check getter throws: CommitFailedException COMMIT NONE",
                "setter throws: CommitFailedException COMMIT PARTIAL",
                "hook throws: AfterCommitException HOOK ALL"), ends);
    }

    /** Through async(), the future fails with the very failure the observer received. */
    @Test
    void anAsyncCallFailsWithTheFailureItsObserverReceived() {
        IllegalStateException boom = new IllegalStateException("boom");
        Recorder recorder = new Recorder();
        try (MachineRegistry registry = registry(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw boom;
                        })), Doc::status, Doc::setStatus, Doc::id, recorder)) {
            CompletionException wrapped = assertThrows(CompletionException.class,
                    () -> registry.of(DOCS).async().execute(new Doc("1", DRAFT), SUBMIT).join());
            CallFailedException failure = assertInstanceOf(CallFailedException.class, wrapped.getCause());
            synchronized (recorder) {
                assertSame(failure, recorder.failed.get(0).error());
                assertSame(recorder.started.get(0), failure.call());
            }
        }
    }

    /**
     * A stage that throws when the engine registers its completion is user code the engine calls: the call ends in an
     * abort of phase ACTION that carries what it threw, and the action is journaled THREW.
     */
    @Test
    void aStageThatThrowsWhenItIsAwaitedAbortsTheCallInAction() {
        IllegalStateException refused = new IllegalStateException("no callbacks");
        CompletableFuture<ActionOutcome> stage = new CompletableFuture<>() {
            @Override
            public CompletableFuture<ActionOutcome> whenComplete(
                    BiConsumer<? super ActionOutcome, ? super Throwable> action) {
                throw refused;
            }
        };
        StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(docs(
                        (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                                .asyncAction("Submit",
                                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> stage)))
                .state(Doc::status, Doc::setStatus).asyncTimeout(Duration.ofSeconds(30)).build();
        Doc doc = new Doc("1", DRAFT);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(doc, SUBMIT));
        assertEquals(CallPhase.ACTION, error.phase());
        assertEquals(StatesWritten.NONE, error.statesWritten());
        assertSame(refused, error.getCause());
        assertEquals(List.of(ActionStatus.THREW),
                error.journal().stream().map((ActionRecord record) -> record.status()).toList());
        assertEquals(DRAFT, doc.status());
    }
}
