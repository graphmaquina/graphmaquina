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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.error.ReentrantTransitionException;
import io.github.graphmaquina.error.StateMachineException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.Facades;
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
import java.util.function.BiFunction;
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
        return registry(graph, getter, setter, identity, observer, Duration.ofMillis(300));
    }

    private static MachineRegistry registry(GraphDefinition<Doc, DocStatus, DocTransition> graph,
                                            Function<Doc, DocStatus> getter, BiConsumer<Doc, DocStatus> setter,
                                            Function<Doc, Object> identity, MachineObserver observer,
                                            Duration asyncTimeout) {
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(getter, setter).identity(identity)
                        .asyncTimeout(asyncTimeout))
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

    /** DRAFT -SUBMIT-> REVIEW runs {@code submit}; REVIEW -PUBLISH-> PUBLISHED is shaped by {@code publish}. */
    private static GraphDefinition<Doc, DocStatus, DocTransition> cascading(
            TransitionAction<Doc, DocStatus, DocTransition> submit,
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> publish) {
        return publish.apply(StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                        .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                        .transition(SUBMIT, DRAFT, REVIEW).action("Submit", submit).endTransition()
                        .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                        .transition(PUBLISH, REVIEW, PUBLISHED))
                .endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    /** The action of SUBMIT cascades PUBLISH to {@code peer}. */
    private static TransitionAction<Doc, DocStatus, DocTransition> cascadeTo(Doc peer) {
        return (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
            ctx.cascade(DOCS, peer, PUBLISH);
            return ActionOutcome.ok();
        };
    }

    private static void run(Recorder recorder, GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        execute(recorder, graph, Doc::status, Doc::setStatus, Doc::id);
    }

    /** Another machine whose SUBMIT action throws, which a call can join. */
    private static StateMachine<Doc, DocStatus, DocTransition> failingElsewhere(RuntimeException boom) {
        return StateMachines.from(docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw boom;
                        })))
                .state(Doc::status, Doc::setStatus).build();
    }

    /** Another machine whose SUBMIT commit fails: its setter throws. */
    private static StateMachine<Doc, DocStatus, DocTransition> failingCommitElsewhere(RuntimeException boom) {
        return StateMachines.from(plain()).state(Doc::status, (Doc doc, DocStatus status) -> {
            throw boom;
        }).build();
    }

    /** Another machine whose SUBMIT hook throws, after its states were written. */
    private static StateMachine<Doc, DocStatus, DocTransition> failingHookElsewhere(RuntimeException boom) {
        return StateMachines.from(docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .afterCommit("Notify", (Doc doc) -> {
                    throw boom;
                }))).state(Doc::status, Doc::setStatus).build();
    }

    /** A sync action of this call that joins a call of {@code other}, whose failure the join throws. */
    private static GraphDefinition<Doc, DocStatus, DocTransition> joiningAction(
            StateMachine<Doc, DocStatus, DocTransition> other) {
        return docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    other.async().execute(new Doc("other", DRAFT), SUBMIT).join();
                    return ActionOutcome.ok();
                }));
    }

    @Test
    void everyExceptionACallThrowsIsAFailureOfThatCall() {
        assertEquals(List.of(
                "identity throws: TransitionAbortedException RESOLVE NONE",
                "getter returns null: TransitionAbortedException RESOLVE NONE",
                "condition throws: TransitionAbortedException RESOLVE NONE",
                "guard throws a library exception: TransitionAbortedException GUARD NONE",
                "action starts a call: TransitionAbortedException ACTION NONE",
                "cascade to an unknown machine: TransitionAbortedException ACTION NONE",
                "pre-check getter throws: CommitFailedException COMMIT NONE",
                "setter throws: CommitFailedException COMMIT PARTIAL",
                "hook throws: AfterCommitException HOOK ALL",
                "reevaluate getter throws: TransitionAbortedException RESOLVE NONE",
                "reevaluate peersOf throws: TransitionAbortedException RESOLVE NONE",
                "sync guard throws: TransitionAbortedException GUARD NONE",
                "async guard throws: TransitionAbortedException GUARD NONE",
                "guard returns null: TransitionAbortedException GUARD NONE",
                "guard times out: TransitionAbortedException GUARD NONE",
                "action throws: TransitionAbortedException ACTION NONE",
                "action returns null: TransitionAbortedException ACTION NONE",
                "action times out: TransitionAbortedException ACTION NONE",
                "action fails with no route: UnhandledFailureException ACTION NONE",
                "cascaded peer's guard throws: TransitionAbortedException ACTION NONE",
                "cascaded peer's action throws: TransitionAbortedException ACTION NONE",
                "cascade cycle: CascadeCycleException ACTION NONE",
                "cascade depth exceeded: CascadeDepthExceededException ACTION NONE",
                "COLLECT aggregate: CascadeException ACTION NONE",
                "setter throws on the second entity: CommitFailedException COMMIT PARTIAL",
                "hook throws in a multi-hop cascade: AfterCommitException HOOK ALL",
                "another call's failure through an action's stage: TransitionAbortedException ACTION NONE",
                "PoisonedUnitOfWorkException the action lets through: TransitionAbortedException ACTION NONE",
                "unknown facade: TransitionAbortedException ACTION NONE",
                "ambiguous identity: TransitionAbortedException ACTION NONE",
                "null peer state: TransitionAbortedException ACTION NONE",
                "root identity returns null: TransitionAbortedException RESOLVE NONE",
                "root state getter throws: TransitionAbortedException RESOLVE NONE",
                "pre-check finds another state: CommitFailedException COMMIT NONE",
                "action joins an aborted call: TransitionAbortedException ACTION NONE",
                "guard joins an aborted call: TransitionAbortedException GUARD NONE",
                "action joins a call whose commit failed: TransitionAbortedException ACTION NONE",
                "action joins a call whose hook failed: TransitionAbortedException ACTION NONE",
                "action catches the abort that poisoned the call: TransitionAbortedException ACTION NONE",
                "guard starts a call: TransitionAbortedException GUARD NONE",
                "watch condition starts a call: TransitionAbortedException RESOLVE NONE"),
                assertEachScenarioFailsItsCallAndListTheEnds());
    }

    /**
     * Runs every scenario, asserts that its call failed as the contract says and that its observer was told the same,
     * and lists how each call ended, in the order the scenarios are declared.
     */
    private static List<String> assertEachScenarioFailsItsCallAndListTheEnds() {
        IllegalStateException boom = new IllegalStateException("boom");
        Map<String, Class<? extends Throwable>> causes = new LinkedHashMap<>();
        Map<String, AtomicReference<Throwable>> caughtFailures = new LinkedHashMap<>();
        Map<String, Scenario> scenarios = new LinkedHashMap<>();
        addRootCallbackFailures(scenarios, boom);
        addCommitHookAndReevaluationFailures(scenarios, boom);
        addGuardAndActionFailures(scenarios, boom);
        addCascadeAndStageFailures(scenarios, causes, boom);
        addWiringAndRootStateFailures(scenarios, boom);
        addJoinCatchAndReentrancyFailures(scenarios, causes, caughtFailures, boom);
        List<String> ends = new ArrayList<>();
        for (Map.Entry<String, Scenario> scenario : scenarios.entrySet()) {
            ends.add(assertTheScenarioFailsItsCallAndDescribeIt(scenario.getKey(), scenario.getValue(), boom,
                    causes, caughtFailures));
        }
        return ends;
    }

    /**
     * Runs the scenario and asserts that it throws a failure of its call, the one its observer saw start and was told
     * of with the same phase and states written, and the cause the scenario expects; returns how the call ended.
     */
    private static String assertTheScenarioFailsItsCallAndDescribeIt(String name, Scenario scenario,
            RuntimeException boom, Map<String, Class<? extends Throwable>> causes,
            Map<String, AtomicReference<Throwable>> caughtFailures) {
        Recorder recorder = new Recorder();
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> scenario.run(recorder));
        CallFailedException failure = assertInstanceOf(CallFailedException.class, thrown, name);
        assertEquals(1, recorder.started.size(), name);
        assertSame(recorder.started.get(0), failure.call(), name);
        assertEquals(1, recorder.failed.size(), name);
        CallFailure told = recorder.failed.get(0);
        assertSame(failure, told.error(), name);
        assertEquals(failure.phase(), told.phase(), name);
        assertEquals(failure.statesWritten(), told.statesWritten(), name);
        assertCauseOfTheFailure(name, failure, boom, causes, caughtFailures);
        return name + ": " + failure.getClass().getSimpleName() + " " + failure.phase() + " "
                + failure.statesWritten();
    }

    private static void assertCauseOfTheFailure(String name, CallFailedException failure, RuntimeException boom,
            Map<String, Class<? extends Throwable>> causes, Map<String, AtomicReference<Throwable>> caughtFailures) {
        if (causes.containsKey(name)) {
            assertInstanceOf(causes.get(name), failure.getCause(), name);
        }
        if (caughtFailures.containsKey(name)) {
            assertSame(caughtFailures.get(name).get(), failure, name);
        }
        if (name.equals("pre-check finds another state")) {
            assertNull(failure.getCause());
        }
        if (name.equals("another call's failure through an action's stage")) {
            assertSame(boom, failure.getCause().getCause());
        }
    }

    /**
     * Failures of the root call's identity, state getter, watch condition and guard, of an action that starts a call,
     * and of a cascade to an unknown machine.
     */
    private static void addRootCallbackFailures(Map<String, Scenario> scenarios, RuntimeException boom) {
        scenarios.put("identity throws", (Recorder recorder) -> execute(recorder, plain(), Doc::status, Doc::setStatus,
                (Doc doc) -> {
                    throw boom;
                }));
        scenarios.put("getter returns null", (Recorder recorder) -> execute(recorder, plain(), (Doc doc) -> null,
                Doc::setStatus, Doc::id));
        scenarios.put("condition throws", (Recorder recorder) -> conditionThrows(recorder, boom));
        scenarios.put("guard throws a library exception", (Recorder recorder) -> execute(recorder, docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("CanSubmit",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw new StateMachineException("not mine");
                        })), Doc::status, Doc::setStatus, Doc::id));
        scenarios.put("action starts a call", (Recorder recorder) -> actionStartsACall(recorder));
        scenarios.put("cascade to an unknown machine", (Recorder recorder) -> execute(recorder, docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.cascade(ITEMS, new Item("i1", ItemStatus.PACKING, Optional.empty()),
                                    ItemTransition.PACK);
                            return ActionOutcome.ok();
                        })), Doc::status, Doc::setStatus, Doc::id));
    }

    /** Failures of the commit's check and setter, of a hook, and of the getter and peersOf of a reevaluation. */
    private static void addCommitHookAndReevaluationFailures(Map<String, Scenario> scenarios,
            RuntimeException boom) {
        scenarios.put("pre-check getter throws", (Recorder recorder) -> preCheckGetterThrows(recorder, boom));
        scenarios.put("setter throws", (Recorder recorder) -> execute(recorder, plain(), Doc::status,
                (Doc doc, DocStatus status) -> {
                    throw boom;
                }, Doc::id));
        scenarios.put("hook throws", (Recorder recorder) -> execute(recorder, docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.afterCommit("Notify",
                        (Doc doc) -> {
                            throw boom;
                        })), Doc::status, Doc::setStatus, Doc::id));
        scenarios.put("reevaluate getter throws", (Recorder recorder) -> reevaluateGetterThrows(recorder, boom));
        scenarios.put("reevaluate peersOf throws", (Recorder recorder) -> reevaluatePeersOfThrows(recorder, boom));
    }

    /** Failures of guards and actions of the root call and of the peers it cascades to. */
    private static void addGuardAndActionFailures(Map<String, Scenario> scenarios, RuntimeException boom) {
        scenarios.put("sync guard throws", (Recorder recorder) -> run(recorder, docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("CanSubmit",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw boom;
                        }))));
        scenarios.put("async guard throws", (Recorder recorder) -> run(recorder, docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.asyncGuard("CanSubmit",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                                CompletableFuture.<GuardResult>failedFuture(boom)))));
        scenarios.put("guard returns null", (Recorder recorder) -> run(recorder, docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("CanSubmit",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> null))));
        scenarios.put("guard times out", (Recorder recorder) -> run(recorder, docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.asyncGuard("CanSubmit",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> new CompletableFuture<GuardResult>()))));
        scenarios.put("action throws", (Recorder recorder) -> run(recorder, docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw boom;
                        }))));
        scenarios.put("action returns null", (Recorder recorder) -> run(recorder, docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> null))));
        scenarios.put("action times out", (Recorder recorder) -> run(recorder, docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.asyncAction("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                                new CompletableFuture<ActionOutcome>()).timeout(Duration.ofMillis(100)))));
        scenarios.put("action fails with no route", (Recorder recorder) -> run(recorder, docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ActionOutcome.failed("no")))));
        scenarios.put("cascaded peer's guard throws", (Recorder recorder) -> run(recorder,
                cascading(cascadeTo(new Doc("peer", REVIEW)),
                        (TransitionBuilder<Doc, DocStatus, DocTransition> publish) -> publish.guard("CanPublish",
                                (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                                    throw boom;
                                }))));
        scenarios.put("cascaded peer's action throws", (Recorder recorder) -> run(recorder,
                cascading(cascadeTo(new Doc("peer", REVIEW)),
                        (TransitionBuilder<Doc, DocStatus, DocTransition> publish) -> publish.action("Publish",
                                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                                    throw boom;
                                }))));
    }

    /**
     * A cascade that cycles, runs too deep or aggregates under COLLECT, a setter and a hook that fail along a cascade,
     * and the failure of another call that crosses an action's stage.
     */
    private static void addCascadeAndStageFailures(
            Map<String, Scenario> scenarios,
            Map<String, Class<? extends Throwable>> causes,
            RuntimeException boom) {
        scenarios.put("cascade cycle", (Recorder recorder) -> run(recorder, cascading(
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    ctx.cascade(DOCS, ctx.entity(), PUBLISH);
                    return ActionOutcome.ok();
                }, UnaryOperator.<TransitionBuilder<Doc, DocStatus, DocTransition>>identity())));
        scenarios.put("cascade depth exceeded", (Recorder recorder) -> cascadeDepthExceeded(recorder));
        scenarios.put("COLLECT aggregate", (Recorder recorder) -> run(recorder, cascading(
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    ctx.cascadeAll(DOCS, List.of(new Doc("ready", REVIEW), new Doc("not ready", DRAFT)), PUBLISH,
                            CascadePolicy.COLLECT);
                    return ActionOutcome.ok();
                }, UnaryOperator.<TransitionBuilder<Doc, DocStatus, DocTransition>>identity())));
        scenarios.put("setter throws on the second entity", (Recorder recorder) -> execute(recorder,
                cascading(cascadeTo(new Doc("peer", REVIEW)),
                        UnaryOperator.<TransitionBuilder<Doc, DocStatus, DocTransition>>identity()),
                Doc::status, (Doc doc, DocStatus status) -> {
                    if (doc.id().equals("peer")) {
                        throw boom;
                    }
                    doc.setStatus(status);
                }, Doc::id));
        scenarios.put("hook throws in a multi-hop cascade", (Recorder recorder) -> run(recorder,
                cascading(cascadeTo(new Doc("peer", REVIEW)),
                        (TransitionBuilder<Doc, DocStatus, DocTransition> publish) -> publish.afterCommit(
                                "Published", (Doc doc) -> {
                                    throw boom;
                                }))));
        scenarios.put("another call's failure through an action's stage",
                (Recorder recorder) -> anotherCallsFailureThroughAStage(recorder, boom));
        causes.put("another call's failure through an action's stage", TransitionAbortedException.class);
    }

    /**
     * Wiring errors a cascade meets (a poisoned call, an unknown facade, an ambiguous identity, a null peer state), and
     * the root's null identity, throwing state getter and state changed under the call.
     */
    private static void addWiringAndRootStateFailures(Map<String, Scenario> scenarios, RuntimeException boom) {
        scenarios.put("PoisonedUnitOfWorkException the action lets through", (Recorder recorder) -> run(recorder,
                docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            try {
                                ctx.cascade(DOCS, new Doc("peer", DRAFT), PUBLISH);
                            } catch (TransitionAbortedException failure) {
                                ctx.peer(DOCS);
                            }
                            return ActionOutcome.ok();
                        }))));
        scenarios.put("unknown facade", (Recorder recorder) -> run(recorder, cascading(
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    ctx.cascade(Facades.Docs.class, new Doc("peer", REVIEW), PUBLISH);
                    return ActionOutcome.ok();
                }, UnaryOperator.<TransitionBuilder<Doc, DocStatus, DocTransition>>identity())));
        scenarios.put("ambiguous identity", (Recorder recorder) -> run(recorder, cascading(
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    ctx.cascadeAll(DOCS, List.of(new Doc("2", REVIEW), new Doc("2", REVIEW)), PUBLISH,
                            CascadePolicy.FAIL_FAST);
                    return ActionOutcome.ok();
                }, UnaryOperator.<TransitionBuilder<Doc, DocStatus, DocTransition>>identity())));
        scenarios.put("null peer state", (Recorder recorder) -> run(recorder,
                cascading(cascadeTo(new Doc("unset", null)),
                        UnaryOperator.<TransitionBuilder<Doc, DocStatus, DocTransition>>identity())));
        scenarios.put("root identity returns null", (Recorder recorder) -> execute(recorder, plain(), Doc::status,
                Doc::setStatus, (Doc doc) -> null));
        scenarios.put("root state getter throws", (Recorder recorder) -> execute(recorder, plain(),
                (Doc doc) -> {
                    throw boom;
                }, Doc::setStatus, Doc::id));
        scenarios.put("pre-check finds another state", (Recorder recorder) -> run(recorder, docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.entity().setStatus(PUBLISHED);
                            return ActionOutcome.ok();
                        }))));
    }

    /**
     * Calls that join the failed call of another machine, an action that catches the abort that poisoned its call, and
     * a guard and a watch condition that start a call.
     */
    private static void addJoinCatchAndReentrancyFailures(
            Map<String, Scenario> scenarios,
            Map<String, Class<? extends Throwable>> causes,
            Map<String, AtomicReference<Throwable>> caughtFailures,
            RuntimeException boom) {
        scenarios.put("action joins an aborted call", (Recorder recorder) -> run(recorder,
                joiningAction(failingElsewhere(boom))));
        causes.put("action joins an aborted call", TransitionAbortedException.class);
        scenarios.put("guard joins an aborted call", (Recorder recorder) -> guardJoinsAnAbortedCall(recorder, boom));
        causes.put("guard joins an aborted call", TransitionAbortedException.class);
        scenarios.put("action joins a call whose commit failed", (Recorder recorder) -> run(recorder,
                joiningAction(failingCommitElsewhere(boom))));
        causes.put("action joins a call whose commit failed", CommitFailedException.class);
        scenarios.put("action joins a call whose hook failed", (Recorder recorder) -> run(recorder,
                joiningAction(failingHookElsewhere(boom))));
        causes.put("action joins a call whose hook failed", AfterCommitException.class);
        AtomicReference<Throwable> caught = new AtomicReference<>();
        caughtFailures.put("action catches the abort that poisoned the call", caught);
        scenarios.put("action catches the abort that poisoned the call", (Recorder recorder) -> run(recorder,
                docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            try {
                                ctx.cascade(DOCS, new Doc("peer", DRAFT), PUBLISH);
                            } catch (TransitionAbortedException failure) {
                                caught.set(failure);
                            }
                            return ActionOutcome.ok();
                        }))));
        scenarios.put("guard starts a call", (Recorder recorder) -> guardStartsACall(recorder));
        causes.put("guard starts a call", ReentrantTransitionException.class);
        scenarios.put("watch condition starts a call", (Recorder recorder) -> watchConditionStartsACall(recorder));
        causes.put("watch condition starts a call", ReentrantTransitionException.class);
    }

    private static void conditionThrows(Recorder recorder, RuntimeException boom) {
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
    }

    private static void actionStartsACall(Recorder recorder) {
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
    }

    private static void preCheckGetterThrows(Recorder recorder, RuntimeException boom) {
        AtomicInteger reads = new AtomicInteger();
        execute(recorder, plain(), (Doc doc) -> {
            if (reads.incrementAndGet() == 2) {
                throw boom;
            }
            return doc.status();
        }, Doc::setStatus, Doc::id);
    }

    private static void reevaluateGetterThrows(Recorder recorder, RuntimeException boom) {
        try (MachineRegistry registry = registry(plain(), (Doc doc) -> {
            throw boom;
        }, Doc::setStatus, Doc::id, recorder)) {
            registry.of(DOCS).reevaluate(new Doc("1", PUBLISHED));
        }
    }

    private static void reevaluatePeersOfThrows(Recorder recorder, RuntimeException boom) {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT)
                .terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(PUBLISHED, DOCS, (Doc doc) -> {
                    throw boom;
                }, (Doc moved) -> Optional.<Doc>empty())
                    .when("rule", (Doc doc, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> true).execute(ARCHIVE)
                    .endWatch()
                .build();
        try (MachineRegistry registry = registry(graph, Doc::status, Doc::setStatus, Doc::id, recorder)) {
            registry.of(DOCS).reevaluate(new Doc("1", PUBLISHED));
        }
    }

    private static void cascadeDepthExceeded(Recorder recorder) {
        Doc second = new Doc("second", REVIEW);
        Doc third = new Doc("third", REVIEW);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = cascading(cascadeTo(second),
                (TransitionBuilder<Doc, DocStatus, DocTransition> publish) -> publish.action("Publish",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            if (ctx.entity() == second) {
                                ctx.cascade(DOCS, third, PUBLISH);
                            }
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .observer(recorder).maxCascadeDepth(1).build()) {
            registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT);
        }
    }

    private static void anotherCallsFailureThroughAStage(Recorder recorder, RuntimeException boom) {
        StateMachine<Doc, DocStatus, DocTransition> other = failingElsewhere(boom);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.asyncAction("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                                other.async().execute(new Doc("other", DRAFT), SUBMIT)
                                        .thenApply((Outcome<Doc, DocStatus, DocTransition> outcome) ->
                                                ActionOutcome.ok())));
        try (MachineRegistry registry = registry(graph, Doc::status, Doc::setStatus, Doc::id, recorder,
                Duration.ofSeconds(5))) {
            registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT);
        }
    }

    private static void guardJoinsAnAbortedCall(Recorder recorder, RuntimeException boom) {
        StateMachine<Doc, DocStatus, DocTransition> other = failingElsewhere(boom);
        run(recorder, docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("CanSubmit",
                (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                    other.async().execute(new Doc("other", DRAFT), SUBMIT).join();
                    return GuardResult.pass();
                })));
    }

    private static void guardStartsACall(Recorder recorder) {
        AtomicReference<MachineRegistry> self = new AtomicReference<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("CanSubmit",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            self.get().of(DOCS).execute(new Doc("2", DRAFT), DISCARD);
                            return GuardResult.pass();
                        }));
        try (MachineRegistry registry = registry(graph, Doc::status, Doc::setStatus, Doc::id, recorder)) {
            self.set(registry);
            registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT);
        }
    }

    private static void watchConditionStartsACall(Recorder recorder) {
        AtomicReference<MachineRegistry> self = new AtomicReference<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT)
                .terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(PUBLISHED, DOCS, (Doc doc) -> List.of(doc), (Doc moved) -> Optional.<Doc>empty())
                    .when("rule", (Doc doc, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> {
                        self.get().of(DOCS).execute(new Doc("2", DRAFT), SUBMIT);
                        return true;
                    }).execute(ARCHIVE)
                    .endWatch()
                .build();
        try (MachineRegistry registry = registry(graph, Doc::status, Doc::setStatus, Doc::id, recorder)) {
            self.set(registry);
            registry.of(DOCS).reevaluate(new Doc("1", PUBLISHED));
        }
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
                assertEquals(1, recorder.failed.size());
                assertSame(failure, recorder.failed.get(0).error());
                assertSame(recorder.started.get(0), failure.call());
            }
        }
    }

    /**
     * A stage that throws when the engine registers its completion is user code the engine calls: the call ends in an
     * abort of phase ACTION that carries what it threw, and the action is journaled THREW. The handle registration
     * runs under an independent watchdog; finally cleanup completes the stage if registration ever stops throwing.
     */
    @Test
    void aStageThatThrowsWhenItIsAwaitedAbortsTheCallInAction() {
        IllegalStateException refused = new IllegalStateException("no callbacks");
        CompletableFuture<ActionOutcome> stage = new CompletableFuture<>() {
            @Override
            public <U> CompletableFuture<U> handle(
                    BiFunction<? super ActionOutcome, Throwable, ? extends U> action) {
                throw refused;
            }
        };
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
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
            });
        } finally {
            stage.complete(ActionOutcome.ok());
        }
    }

    /**
     * A state getter, an identity function and a setter are callbacks of the call too, so one that starts a
     * transition is refused, and the refusal arrives as the cause of the call's failure: an abort in RESOLVE when the
     * identity or the getter reads the entity, a commit failure with nothing written when the getter does it during
     * the commit's check, and one that wrote some states when the setter does.
     */
    @Test
    void aReentrantCallFromAnAccessorArrivesAsTheCauseOfTheFailureOfTheCall() {
        Doc other = new Doc("2", DRAFT);
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        Runnable startsATransition = () -> self.get().execute(other, DISCARD);
        AtomicInteger reads = new AtomicInteger();
        List<String> ends = new ArrayList<>();
        for (String accessor : List.of("identity", "getter", "check", "setter")) {
            reads.set(0);
            StateMachine<Doc, DocStatus, DocTransition> machine =
                    machineWhoseAccessorStartsATransition(accessor, reads, startsATransition);
            self.set(machine);
            CallFailedException failure = assertThrows(CallFailedException.class,
                    () -> machine.execute(new Doc("1", DRAFT), SUBMIT));
            ends.add(accessor + ": " + failure.getClass().getSimpleName() + " " + failure.phase() + " "
                    + failure.statesWritten() + " caused by " + failure.getCause().getClass().getSimpleName());
        }
        assertEquals(List.of(
                "identity: TransitionAbortedException RESOLVE NONE caused by ReentrantTransitionException",
                "getter: TransitionAbortedException RESOLVE NONE caused by ReentrantTransitionException",
                "check: CommitFailedException COMMIT NONE caused by ReentrantTransitionException",
                "setter: CommitFailedException COMMIT PARTIAL caused by ReentrantTransitionException"), ends);
        assertEquals(DRAFT, other.status());
    }

    /** A machine whose {@code accessor} (identity, getter, check of the commit or setter) starts a transition. */
    private static StateMachine<Doc, DocStatus, DocTransition> machineWhoseAccessorStartsATransition(
            String accessor, AtomicInteger reads, Runnable startsATransition) {
        return StateMachines.from(docs(UnaryOperator.<TransitionBuilder<Doc, DocStatus, DocTransition>>identity()))
                .state((Doc doc) -> {
                    int read = reads.incrementAndGet();
                    if (startsOnThisRead(accessor, read)) {
                        startsATransition.run();
                    }
                    return doc.status();
                }, (Doc doc, DocStatus status) -> {
                    if (accessor.equals("setter")) {
                        startsATransition.run();
                    }
                    doc.setStatus(status);
                })
                .identity((Doc doc) -> {
                    if (accessor.equals("identity")) {
                        startsATransition.run();
                    }
                    return doc.id();
                }).build();
    }

    /** The getter starts a transition on its first read, the check of the commit on the second. */
    private static boolean startsOnThisRead(String accessor, int read) {
        return accessor.equals("getter") && read == 1 || accessor.equals("check") && read == 2;
    }
}
