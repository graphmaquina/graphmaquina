package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocGraphs.docs;
import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.StaleContextException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocMachines;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.GuardProbe;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What an eligibility check answers through the real engine: the refusal execute's admission would give, with its
 * kind, its reason and its guard, or an eligible answer once every guard passed, from the state the check read. Its
 * guards read the check's input, the confirmed state of a peer and an empty flow; it can be asked where a call may
 * start, from an after-commit hook or on the machine's executor; and it has no effect: no setter, action, hook, watch
 * or observer runs, and a context a guard kept is stale once the check ends.
 */
class EligibilityCheckTest {

    /** The doc machine of a registry of its own, whose SUBMIT edge {@code submit} shapes. */
    private static StateMachine<Doc, DocStatus, DocTransition> machine(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit) {
        return DocMachines.registry(docs(submit)).of(DOCS);
    }

    /** {@link #machine} with SUBMIT a plain move. */
    private static StateMachine<Doc, DocStatus, DocTransition> plain() {
        return machine(UnaryOperator.<TransitionBuilder<Doc, DocStatus, DocTransition>>identity());
    }

    /** The answer a GUARD refusal of SUBMIT from DRAFT gives for {@code doc}. */
    private static Eligibility<Doc, DocStatus, DocTransition> refusedBy(Doc doc, String guard, String reason) {
        return new Eligibility.Ineligible<>(doc, DRAFT, SUBMIT, reason, RejectionKind.GUARD, Optional.of(guard));
    }

    @ParameterizedTest
    @CsvSource({
        "DRAFT, PUBLISH, a draft is submitted or discarded",
        "ARCHIVED, SUBMIT, terminal state ARCHIVED accepts no transitions"
    })
    void noEdgeAnswersThePolicyReason(DocStatus state, DocTransition id, String reason) {
        Doc doc = new Doc("1", state);
        assertEquals(new Eligibility.Ineligible<>(doc, state, id, reason, RejectionKind.NO_TRANSITION,
                Optional.empty()), plain().eligibility(doc, id));
    }

    @Test
    void perIdentifierRejectionReasonWins() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT)
                .terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted")
                .statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .reject(REVIEW, SUBMIT, "it is in review already")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        Doc doc = new Doc("1", REVIEW);
        assertEquals(new Eligibility.Ineligible<>(doc, REVIEW, SUBMIT, "it is in review already",
                RejectionKind.NO_TRANSITION, Optional.empty()),
                DocMachines.registry(graph).of(DOCS).eligibility(doc, SUBMIT));
    }

    @Test
    void firstRejectingGuardIsNamedAndLaterGuardsDoNotRun() {
        GuardProbe<Doc, DocStatus, DocTransition> a = new GuardProbe<>();
        GuardProbe<Doc, DocStatus, DocTransition> b = new GuardProbe<>();
        GuardProbe<Doc, DocStatus, DocTransition> c = new GuardProbe<>();
        b.verdict = (GuardContext<Doc, DocStatus, DocTransition> ctx) -> GuardResult.reject("missing document");
        Doc doc = new Doc("1", DRAFT);
        Eligibility<Doc, DocStatus, DocTransition> answer = machine(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("a", a).guard("b", b).guard("c", c))
                .eligibility(doc, SUBMIT);
        assertEquals(refusedBy(doc, "b", "missing document"), answer);
        assertEquals(List.of(1, 1, 0), List.of(a.calls, b.calls, c.calls));
    }

    @Test
    void allGuardsPassingIsEligible() {
        GuardProbe<Doc, DocStatus, DocTransition> a = new GuardProbe<>();
        GuardProbe<Doc, DocStatus, DocTransition> b = new GuardProbe<>();
        Doc doc = new Doc("1", DRAFT);
        Eligibility<Doc, DocStatus, DocTransition> answer = machine(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("a", a).guard("b", b))
                .eligibility(doc, SUBMIT);
        assertEquals(new Eligibility.Eligible<>(doc, DRAFT, SUBMIT), answer);
        assertEquals(List.of(1, 1), List.of(a.calls, b.calls));
    }

    @Test
    void inputReachesTheGuards() {
        List<Optional<String>> seen = new ArrayList<>();
        StateMachine<Doc, DocStatus, DocTransition> m = machine(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("reads", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            seen.add(ctx.input(String.class));
                            return GuardResult.pass();
                        }));
        Doc doc = new Doc("1", DRAFT);
        m.eligibility(doc, SUBMIT, "urgent");
        m.eligibility(doc, SUBMIT);
        assertEquals(List.of(Optional.of("urgent"), Optional.empty()), seen);
    }

    /** The guard's stage completes on another thread, later: the check waits for it under the machine's deadline. */
    @Test
    void asyncGuardIsAwaited() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .asyncGuard("remote", (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                                CompletableFuture.supplyAsync(() -> GuardResult.reject("later"),
                                        CompletableFuture.delayedExecutor(20, TimeUnit.MILLISECONDS))));
        Doc doc = new Doc("1", DRAFT);
        assertEquals(refusedBy(doc, "remote", "later"),
                DocMachines.registry(graph, Duration.ofSeconds(10)).of(DOCS).eligibility(doc, SUBMIT));
    }

    static Stream<Arguments> verdicts() {
        return Stream.of(Arguments.of(GuardResult.pass(), true), Arguments.of(GuardResult.reject("not yet"), false));
    }

    @ParameterizedTest
    @MethodSource("verdicts")
    void noEngineEffects(GuardResult verdict, boolean eligible) {
        List<String> effects = new ArrayList<>();
        Doc doc = new Doc("1", DRAFT);
        Eligibility<Doc, DocStatus, DocTransition> answer = instrumented(effects, verdict).eligibility(doc, SUBMIT);
        assertEquals(eligible, answer.isEligible());
        assertEquals(List.of(), effects);
        assertEquals(DRAFT, doc.status());
    }

    /** The control of {@link #noEngineEffects}: an execute of the same machine shows every effect it counts. */
    @Test
    void anExecuteShowsEveryEffectTheCheckLacks() {
        List<String> effects = new ArrayList<>();
        Doc doc = new Doc("1", DRAFT);
        instrumented(effects, GuardResult.pass()).execute(doc, SUBMIT);
        assertEquals(Set.of("setter", "action", "hook", "watch", "observer"), Set.copyOf(effects));
        assertEquals(REVIEW, doc.status());
    }

    /**
     * The doc machine of a registry with an observer, whose SUBMIT edge has a guard that answers {@code verdict}, an
     * action and an after-commit hook, and whose watch looks for the owner of every doc that moves. Each of them, and
     * the state setter, notes its run in {@code effects}.
     */
    private static StateMachine<Doc, DocStatus, DocTransition> instrumented(List<String> effects,
                                                                            GuardResult verdict) {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT)
                .terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted")
                .statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .guard("verdict", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> verdict)
                    .action("submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        effects.add("action");
                        return ActionOutcome.ok();
                    })
                    .afterCommit("submitted", (Doc doc) -> effects.add("hook"))
                    .endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(REVIEW, DOCS, (Doc doc) -> List.of(doc), (Doc moved) -> {
                    effects.add("watch");
                    return Optional.<Doc>empty();
                })
                    .when("always", (Doc doc, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> true).execute(PUBLISH)
                    .endWatch()
                .build();
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Doc::status, (Doc doc, DocStatus status) -> {
                    effects.add("setter");
                    doc.setStatus(status);
                }).identity(Doc::id))
                .observer(new Noting(effects))
                .build()
                .of(DOCS);
    }

    /** An observer that notes {@code observer} in its list for every event it hears. */
    private record Noting(List<String> effects) implements MachineObserver {

        @Override
        public void onStarted(MachineCall call) {
            effects.add("observer");
        }

        @Override
        public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
            effects.add("observer");
        }

        @Override
        public void onFailed(MachineCall call, CallFailure failure, Duration elapsed) {
            effects.add("observer");
        }
    }

    /** A guard reads a peer's confirmed state and the flow of the check, which nothing wrote. */
    @Test
    void guardReadsPeerAndFlow() {
        Doc peer = new Doc("2", PUBLISHED);
        List<Object> seen = new ArrayList<>();
        StateMachine<Doc, DocStatus, DocTransition> m = machine(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("reads", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            seen.add(ctx.peer(DOCS).stateOf(peer));
                            seen.add(ctx.flow().keys());
                            return GuardResult.pass();
                        }));
        Doc doc = new Doc("1", DRAFT);
        assertEquals(new Eligibility.Eligible<>(doc, DRAFT, SUBMIT), m.eligibility(doc, SUBMIT));
        assertEquals(List.of(PUBLISHED, Set.of()), seen);
    }

    /** An after-commit hook runs once the call released the thread, so it can check another entity. */
    @Test
    void checkFromAfterCommitHookWorks() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        List<Eligibility<Doc, DocStatus, DocTransition>> answers = new ArrayList<>();
        Doc other = new Doc("2", REVIEW);
        self.set(machine((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .afterCommit("asks", (Doc doc) -> answers.add(self.get().eligibility(other, PUBLISH)))));
        self.get().execute(new Doc("1", DRAFT), SUBMIT);
        assertEquals(List.of(new Eligibility.Eligible<>(other, REVIEW, PUBLISH)), answers);
    }

    @Test
    void checkThroughAsyncWorks() {
        try (MachineRegistry registry = DocMachines.registry(docs(
                UnaryOperator.<TransitionBuilder<Doc, DocStatus, DocTransition>>identity()))) {
            StateMachine<Doc, DocStatus, DocTransition> m = registry.of(DOCS);
            AtomicReference<Thread> ran = new AtomicReference<>();
            Doc doc = new Doc("1", DRAFT);
            Eligibility<Doc, DocStatus, DocTransition> answer = m.supplyAsync(() -> {
                ran.set(Thread.currentThread());
                return m.eligibility(doc, SUBMIT);
            }).join();
            assertEquals(new Eligibility.Eligible<>(doc, DRAFT, SUBMIT), answer);
            assertNotSame(Thread.currentThread(), ran.get());
        }
    }

    @Test
    void contextKeptByAGuardIsStaleAfterwards() {
        AtomicReference<GuardContext<Doc, DocStatus, DocTransition>> kept = new AtomicReference<>();
        StateMachine<Doc, DocStatus, DocTransition> m = machine(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("keeps", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            kept.set(ctx);
                            return GuardResult.pass();
                        }));
        m.eligibility(new Doc("1", DRAFT), SUBMIT);
        assertThrows(StaleContextException.class, () -> kept.get().flow());
    }
}
