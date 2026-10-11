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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.FlowContext;
import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.CascadeException;
import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.error.StaleContextException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UnhandledFailureException;
import io.github.graphmaquina.error.WatchChainExceededException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.internal.FrozenFlow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Spec R15 and R16: the root call freezes its flow after its hooks and its watch reactions and hands it out, in the
 * outcome it returns or in the failure it throws. The frozen flow holds the root entity and the values present then;
 * reading never creates the flow, only a write does, so a call that wrote nothing hands out {@link FlowView#empty()}
 * (spec R11.1 and D18), and so do a cascade's outcome and an ignored one. Each watch reaction hands out its own flow,
 * a failure of another call keeps its own, a reevaluation that fails before it runs an edge carries the empty flow,
 * and an Error carries none. The handed-out view is read from any thread, and nothing reaches the live flow once the
 * call returned.
 */
class FlowDeliveryTest {

    private static final FlowKey<String> BATCH = FlowKey.of("batch", String.class);
    private static final FlowKey<String> NOTE = FlowKey.visible("note", String.class);
    private static final FlowKey<Integer> COUNT = FlowKey.of("count", Integer.class);
    private static final FlowKey<String> PARENT = FlowKey.of("parent", String.class);
    private static final FlowKey<String> CHILD = FlowKey.of("child", String.class);
    private static final FlowKey<String> RECOVERED = FlowKey.of("recovered", String.class);
    @SuppressWarnings("rawtypes")
    private static final FlowKey<List> LINES = FlowKey.of("lines", List.class);

    /** An Error of its own, so the test can tell it from anything the engine could throw. */
    private static final class Fatal extends Error {
        private static final long serialVersionUID = 1L;

        Fatal() {
            super("fatal");
        }
    }

    /**
     * DRAFT -SUBMIT-> REVIEW, shaped by {@code submit}; DRAFT -DISCARD-> ARCHIVED, shaped by {@code discard};
     * REVIEW -PUBLISH-> PUBLISHED and PUBLISHED -ARCHIVE-> ARCHIVED are plain moves.
     */
    private static GraphDefinition<Doc, DocStatus, DocTransition> docs(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit,
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> discard) {
        GraphBuilder<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded")
                .statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived");
        graph = submit.apply(graph.transition(SUBMIT, DRAFT, REVIEW)).endTransition();
        graph = discard.apply(graph.transition(DISCARD, DRAFT, ARCHIVED)).endTransition();
        return graph.transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    private static GraphDefinition<Doc, DocStatus, DocTransition> docs(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit) {
        return docs(submit, UnaryOperator.<TransitionBuilder<Doc, DocStatus, DocTransition>>identity());
    }

    private static MachineRegistry registry(GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .build();
    }

    private static StateMachine<Doc, DocStatus, DocTransition> standalone(
            GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        return StateMachines.from(graph).state(Doc::status, Doc::setStatus).asyncTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** A frozen flow of {@code entity} with these keys and values, in this order. */
    private static FlowView frozen(Object entity, Object... keysAndValues) {
        Map<FlowKey<?>, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            values.put((FlowKey<?>) keysAndValues[index], keysAndValues[index + 1]);
        }
        return new FrozenFlow(Optional.of(entity), values);
    }

    /**
     * An applied outcome hands out the flow as the hooks left it: the root entity, the values the action put, each
     * the very object it put, and the hook's writes. It compares by value, so the outcome equals one built with an
     * equal flow (spec D6).
     */
    @Test
    void anAppliedOutcomeHandsOutTheFlowAsTheHooksLeftIt() {
        Doc doc = new Doc("1", DRAFT);
        List<String> lines = new ArrayList<>(List.of("reserved"));
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(BATCH, "b-1");
                            ctx.flow().put(LINES, lines);
                            ctx.flow().put(COUNT, 1);
                            return ActionOutcome.ok();
                        })
                        .afterCommitWith("Persist", (Doc committed, CommitContext<DocStatus, DocTransition> commit) -> {
                            commit.flow().remove(COUNT);
                            commit.flow().put(NOTE, "n-1");
                        }));
        Outcome<Doc, DocStatus, DocTransition> outcome;
        try (MachineRegistry registry = registry(graph)) {
            outcome = registry.of(DOCS).execute(doc, SUBMIT);
        }
        Outcome.Applied<?, ?, ?> applied = assertInstanceOf(Outcome.Applied.class, outcome);
        FlowView flow = outcome.flow();
        assertEquals(List.of(BATCH, LINES, NOTE), List.copyOf(flow.keys()));
        assertEquals(Optional.of("b-1"), flow.get(BATCH));
        assertSame(lines, flow.get(LINES).orElseThrow());
        assertEquals(Optional.empty(), flow.get(COUNT));
        assertSame(doc, flow.entity().orElseThrow());
        assertSame(flow, applied.flow());
        FlowView expected = frozen(doc, BATCH, "b-1", LINES, lines, NOTE, "n-1");
        assertEquals(expected, flow);
        assertEquals(new Outcome.Applied<>(doc, DRAFT, SUBMIT, REVIEW, outcome.records(), outcome.reactions(),
                outcome.starts(), expected), outcome);
        assertNotEquals(new Outcome.Applied<>(doc, DRAFT, SUBMIT, REVIEW, outcome.records(), outcome.reactions(),
                outcome.starts()), outcome);
    }

    /** A failed outcome holds the recovery action's writes and none of the failed action's, which its route dropped. */
    @Test
    void aFailedOutcomeHandsOutTheRecoveryWritesAndNoneOfTheFailedAction() {
        Doc doc = new Doc("1", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(PARENT, "p");
                            return ActionOutcome.failed("no reviewer");
                        })
                        .onFailure(DISCARD),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.action("Archive",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(RECOVERED, "r");
                            return ActionOutcome.ok();
                        }));
        Outcome<Doc, DocStatus, DocTransition> outcome;
        try (MachineRegistry registry = registry(graph)) {
            outcome = registry.of(DOCS).execute(doc, SUBMIT);
        }
        assertInstanceOf(Outcome.Failed.class, outcome);
        assertEquals(frozen(doc, RECOVERED, "r"), outcome.flow());
    }

    /**
     * A rejected outcome is the empty flow whatever its guards read: reading creates no flow, and a guard cannot
     * write. Two otherwise identical rejections are equal, with the same hash code, whether or not a guard read.
     */
    @Test
    void aRejectedOutcomeHandsOutTheEmptyFlowWhateverItsGuardsRead() {
        Doc doc = new Doc("1", DRAFT);
        List<Object> read = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("Ready",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            read.add(ctx.flow().get(BATCH));
                            read.add(ctx.flow().keys());
                            read.add(ctx.flow().entity(Doc.class).orElseThrow());
                            return GuardResult.reject("nothing to review");
                        }),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.guard("Closed",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> GuardResult.reject("closed")));
        try (MachineRegistry registry = registry(graph)) {
            StateMachine<Doc, DocStatus, DocTransition> machine = registry.of(DOCS);
            Outcome<Doc, DocStatus, DocTransition> first = machine.execute(doc, SUBMIT);
            Outcome<Doc, DocStatus, DocTransition> second = machine.execute(doc, SUBMIT);
            assertEquals(RejectionKind.GUARD, first.asRejected().orElseThrow().kind());
            assertSame(FlowView.empty(), first.flow());
            assertEquals(first, second);
            assertEquals(first.hashCode(), second.hashCode());
            assertEquals(new Outcome.Rejected<>(doc, DRAFT, SUBMIT, "nothing to review", RejectionKind.GUARD,
                    Optional.of("Ready")), first);
            Outcome<Doc, DocStatus, DocTransition> unread = machine.execute(doc, DISCARD);
            assertEquals(RejectionKind.GUARD, unread.asRejected().orElseThrow().kind());
            assertSame(FlowView.empty(), unread.flow());
            Outcome<Doc, DocStatus, DocTransition> noEdge = machine.execute(doc, PUBLISH);
            assertEquals(RejectionKind.NO_TRANSITION, noEdge.asRejected().orElseThrow().kind());
            assertSame(FlowView.empty(), noEdge.flow());
        }
        assertEquals(List.of(Optional.empty(), Set.of(), doc, Optional.empty(), Set.of(), doc), read);
    }

    @Test
    void anIgnoredOutcomeHandsOutTheEmptyFlow() {
        Doc doc = new Doc("1", DRAFT);
        try (MachineRegistry registry = registry(docs(UnaryOperator.identity()))) {
            Outcome<Doc, DocStatus, DocTransition> outcome = registry.of(DOCS).reevaluate(doc);
            assertInstanceOf(Outcome.Ignored.class, outcome);
            assertSame(FlowView.empty(), outcome.flow());
        }
    }

    /**
     * Spec R11.1 and D18: reading never creates the flow. A call whose guard, action and hook only read it, the
     * values, the keys and the entity, hands out the empty view, without the entity, which {@code entity()} of the
     * outcome holds; the outcome stays equal to one built without a flow. A call that fails after its action only read
     * the flow carries the empty view too.
     */
    @Test
    void aCallThatWritesNothingHandsOutTheEmptyFlow() {
        Doc doc = new Doc("1", DRAFT);
        List<Object> read = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("Ready", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            read.add(ctx.flow().keys());
                            return GuardResult.pass();
                        })
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            read.add(ctx.flow().get(BATCH));
                            read.add(ctx.flow().keys());
                            read.add(ctx.flow().entity().orElseThrow());
                            if (ctx.entity().id().equals("2")) {
                                throw new IllegalStateException("reviewer unavailable");
                            }
                            return ActionOutcome.ok();
                        })
                        .afterCommitWith("Persist", (Doc committed, CommitContext<DocStatus, DocTransition> commit) -> {
                            read.add(commit.flow().get(NOTE));
                            read.add(commit.flow().keys());
                            read.add(commit.flow().entity().orElseThrow());
                        }));
        Doc failing = new Doc("2", DRAFT);
        Outcome<Doc, DocStatus, DocTransition> outcome;
        TransitionAbortedException error;
        try (MachineRegistry registry = registry(graph)) {
            outcome = registry.of(DOCS).execute(doc, SUBMIT);
            error = assertThrows(TransitionAbortedException.class, () -> registry.of(DOCS).execute(failing, SUBMIT));
        }
        assertEquals(List.of(Set.of(), Optional.empty(), Set.of(), doc, Optional.empty(), Set.of(), doc,
                Set.of(), Optional.empty(), Set.of(), failing), read);
        assertSame(FlowView.empty(), outcome.flow());
        assertEquals(Optional.empty(), outcome.flow().entity());
        assertSame(doc, outcome.entity());
        assertEquals(new Outcome.Applied<>(doc, DRAFT, SUBMIT, REVIEW, outcome.records(), outcome.reactions(),
                outcome.starts()), outcome);
        assertSame(FlowView.empty(), error.flow());
    }

    /**
     * Only a write creates the flow. An action's reads see nothing until it writes, and the outcome holds the root
     * entity once a write created the flow, even when that write was a remove of a key nothing held.
     */
    @Test
    void onlyTheFirstWriteCreatesTheFlow() {
        Doc doc = new Doc("2", DRAFT);
        Doc removing = new Doc("3", DRAFT);
        List<Object> seen = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            seen.add(ctx.flow().keys());
                            if (ctx.entity() == removing) {
                                ctx.flow().remove(BATCH);
                            } else {
                                ctx.flow().put(BATCH, "b-2");
                            }
                            seen.add(ctx.flow().keys());
                            return ActionOutcome.ok();
                        }));
        Outcome<Doc, DocStatus, DocTransition> written;
        Outcome<Doc, DocStatus, DocTransition> removed;
        try (MachineRegistry registry = registry(graph)) {
            written = registry.of(DOCS).execute(doc, SUBMIT);
            removed = registry.of(DOCS).execute(removing, SUBMIT);
        }
        assertEquals(List.of(Set.of(), Set.of(BATCH), Set.of(), Set.of()), seen);
        assertEquals(frozen(doc, BATCH, "b-2"), written.flow());
        assertEquals(frozen(removing), removed.flow());
        assertNotEquals(FlowView.empty(), removed.flow());
    }

    /** A hook may create the flow after the commit, when no step did: the outcome hands out what the hook wrote. */
    @Test
    void aFlowThatOnlyAHookCreatedIsHandedOut() {
        Doc doc = new Doc("1", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.afterCommitWith("Persist",
                        (Doc committed, CommitContext<DocStatus, DocTransition> commit) ->
                                commit.flow().put(NOTE, "n-1")));
        Outcome<Doc, DocStatus, DocTransition> outcome;
        try (MachineRegistry registry = registry(graph)) {
            outcome = registry.of(DOCS).execute(doc, SUBMIT);
        }
        assertEquals(frozen(doc, NOTE, "n-1"), outcome.flow());
    }

    /** Spec R7.3: the outcome a cascade returns carries the empty flow; the flow belongs to the root call. */
    @Test
    void aCascadesOutcomeCarriesTheEmptyFlow() {
        Doc parent = new Doc("1", DRAFT);
        Doc child = new Doc("2", DRAFT);
        AtomicReference<Outcome<Doc, DocStatus, DocTransition>> cascaded = new AtomicReference<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(CHILD, "c");
                            return ActionOutcome.ok();
                        }),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard
                        .action("Collect", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(PARENT, "p");
                            cascaded.set(ctx.cascade(DOCS, child, SUBMIT));
                            return ActionOutcome.ok();
                        }));
        Outcome<Doc, DocStatus, DocTransition> outcome;
        try (MachineRegistry registry = registry(graph)) {
            outcome = registry.of(DOCS).execute(parent, DISCARD);
        }
        assertInstanceOf(Outcome.Applied.class, cascaded.get());
        assertSame(FlowView.empty(), cascaded.get().flow());
        assertEquals(frozen(parent, PARENT, "p", CHILD, "c"), outcome.flow());
    }

    /**
     * Spec R16.3: each watch reaction is a root call with a flow of its own. Its action does not see what the
     * triggering call put, and its outcome, in {@code Reaction.Done}, hands out its own flow; the triggering call's
     * flow holds nothing the reaction wrote.
     */
    @Test
    void eachReactionHandsOutItsOwnFlow() {
        Doc doc = new Doc("1", DRAFT);
        List<Object> seen = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        ctx.flow().put(BATCH, "root");
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED)
                    .action("Publish", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                        seen.add(ctx.flow().get(BATCH));
                        ctx.flow().put(NOTE, "reaction");
                        return ActionOutcome.ok();
                    })
                    .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(REVIEW, DOCS, (Doc owner) -> List.of(owner), (Doc moved) -> Optional.of(moved))
                    .when("always", (Doc owner, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> true).execute(PUBLISH)
                    .endWatch()
                .build();
        Outcome<Doc, DocStatus, DocTransition> outcome;
        try (MachineRegistry registry = registry(graph)) {
            outcome = registry.of(DOCS).execute(doc, SUBMIT);
        }
        assertEquals(List.of(Optional.empty()), seen);
        assertEquals(PUBLISHED, doc.status());
        assertEquals(frozen(doc, BATCH, "root"), outcome.flow());
        Reaction.Done done = assertInstanceOf(Reaction.Done.class, outcome.reactions().get(0));
        assertEquals(frozen(doc, NOTE, "reaction"), done.outcome().flow());
    }

    /**
     * An abort undoes nothing of the flow: the failure holds what the action put before it threw, with the root
     * entity.
     */
    @Test
    void anAbortHandsOutTheValuesPresentWhenTheCallFailed() {
        Doc doc = new Doc("1", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> machine = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(BATCH, "b-1");
                            throw new IllegalStateException("reviewer unavailable");
                        })));
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(doc, SUBMIT));
        assertEquals(CallPhase.ACTION, error.phase());
        assertEquals(frozen(doc, BATCH, "b-1"), error.flow());
    }

    /**
     * The abort of a cascaded step poisons the call, which throws it once the parent's action returned: the failure
     * holds the parent's write and the cascaded action's, since an abort rolls nothing back.
     */
    @Test
    void aNestedAbortHandsOutTheWritesOfEveryStepUpToIt() {
        Doc parent = new Doc("1", DRAFT);
        Doc child = new Doc("2", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(CHILD, "c");
                            throw new IllegalStateException("reviewer unavailable");
                        }),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard
                        .action("Collect", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(PARENT, "p");
                            try {
                                ctx.cascade(DOCS, child, SUBMIT);
                            } catch (TransitionAbortedException swallowed) {
                                ctx.flow().put(NOTE, "swallowed");
                            }
                            return ActionOutcome.ok();
                        }));
        TransitionAbortedException error;
        try (MachineRegistry registry = registry(graph)) {
            error = assertThrows(TransitionAbortedException.class, () -> registry.of(DOCS).execute(parent, DISCARD));
        }
        assertSame(child, error.entity());
        assertEquals(frozen(parent, PARENT, "p", CHILD, "c", NOTE, "swallowed"), error.flow());
    }

    /**
     * A failure the edge expects, returned or thrown as a type it declares, rolls its step back before the engine finds
     * that no route takes it: the UnhandledFailureException the call throws lacks the writes of that step and of the
     * steps it cascaded to, and keeps what earlier steps wrote. The flow exists, so it holds the root entity.
     */
    @Test
    void anUnhandledFailureHandsOutTheFlowWithoutTheWritesOfItsStep() {
        Doc doc = new Doc("1", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> machine = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(BATCH, "b-1");
                            return ActionOutcome.failed("no reviewer");
                        })));
        UnhandledFailureException root = assertThrows(UnhandledFailureException.class,
                () -> machine.execute(doc, SUBMIT));
        assertEquals(frozen(doc), root.flow());

        Doc declared = new Doc("4", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> throwing = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(BATCH, "b-4");
                            throw new IllegalStateException("reviewer unavailable");
                        })
                        .failsOn(IllegalStateException.class)));
        UnhandledFailureException thrown = assertThrows(UnhandledFailureException.class,
                () -> throwing.execute(declared, SUBMIT));
        assertEquals(frozen(declared), thrown.flow());

        Doc parent = new Doc("2", DRAFT);
        Doc child = new Doc("3", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(CHILD, "c");
                            return ActionOutcome.failed("no reviewer");
                        }),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard
                        .action("Collect", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(PARENT, "p");
                            ctx.cascade(DOCS, child, SUBMIT);
                            return ActionOutcome.ok();
                        }));
        UnhandledFailureException nested;
        try (MachineRegistry registry = registry(graph)) {
            nested = assertThrows(UnhandledFailureException.class, () -> registry.of(DOCS).execute(parent, DISCARD));
        }
        assertSame(child, nested.entity());
        assertEquals(frozen(parent, PARENT, "p"), nested.flow());
    }

    /**
     * A COLLECT element that aborts is rolled back with its writes before the next element runs; the aggregate the
     * call throws holds the parent's write and the passing element's.
     */
    @Test
    void aCollectAggregateHandsOutTheFlowWithoutTheWritesOfTheFailedElement() {
        Doc parent = new Doc("1", DRAFT);
        Doc failing = new Doc("2", DRAFT);
        Doc passing = new Doc("3", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Review",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(CHILD, ctx.entity().id());
                            return ctx.entity() == failing ? ActionOutcome.failed("no reviewer") : ActionOutcome.ok();
                        }),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.action("Collect",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(PARENT, "p");
                            ctx.cascadeAll(DOCS, List.of(failing, passing), SUBMIT, CascadePolicy.COLLECT);
                            return ActionOutcome.ok();
                        }));
        CascadeException error;
        try (MachineRegistry registry = registry(graph)) {
            error = assertThrows(CascadeException.class, () -> registry.of(DOCS).execute(parent, DISCARD));
        }
        assertEquals(1, error.errors().size());
        assertEquals(frozen(parent, PARENT, "p", CHILD, "3"), error.flow());
    }

    /**
     * A commit that finds a state changed while the call ran, and one whose setter throws, hand out every value the
     * steps put.
     */
    @Test
    void aCommitFailureHandsOutTheFlowOfTheSteps() {
        Doc moved = new Doc("1", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> conflicting = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(BATCH, "b-1");
                            ctx.entity().setStatus(PUBLISHED);
                            return ActionOutcome.ok();
                        })));
        CommitFailedException conflict = assertThrows(CommitFailedException.class,
                () -> conflicting.execute(moved, SUBMIT));
        assertEquals(StatesWritten.NONE, conflict.statesWritten());
        assertEquals(frozen(moved, BATCH, "b-1"), conflict.flow());

        Doc unwritable = new Doc("2", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> refusing = StateMachines.from(docs(
                        (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                                .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                                    ctx.flow().put(BATCH, "b-2");
                                    return ActionOutcome.ok();
                                })))
                .state(Doc::status, (Doc doc, DocStatus status) -> {
                    throw new IllegalStateException("read-only store");
                })
                .build();
        CommitFailedException setter = assertThrows(CommitFailedException.class,
                () -> refusing.execute(unwritable, SUBMIT));
        assertEquals(StatesWritten.PARTIAL, setter.statesWritten());
        assertEquals(frozen(unwritable, BATCH, "b-2"), setter.flow());
    }

    /**
     * A failing hook's AfterCommitException holds what the steps put and what the hooks wrote up to its throw, the
     * failing hook's own writes included, since a hook's write applies at once.
     */
    @Test
    void anAfterCommitFailureHandsOutTheHooksWritesUpToTheFailingHook() {
        Doc parent = new Doc("1", DRAFT);
        Doc child = new Doc("2", DRAFT);
        IllegalStateException boom = new IllegalStateException("persistence unavailable");
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.afterCommitWith("Second",
                        (Doc committed, CommitContext<DocStatus, DocTransition> commit) -> {
                            commit.flow().put(COUNT, 2);
                            throw boom;
                        }),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard
                        .action("Collect", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(BATCH, "b-1");
                            ctx.cascade(DOCS, child, SUBMIT);
                            return ActionOutcome.ok();
                        })
                        .afterCommitWith("First", (Doc committed, CommitContext<DocStatus, DocTransition> commit) ->
                                commit.flow().put(NOTE, "n-1")));
        AfterCommitException error;
        try (MachineRegistry registry = registry(graph)) {
            error = assertThrows(AfterCommitException.class, () -> registry.of(DOCS).execute(parent, DISCARD));
        }
        assertSame(boom, error.getCause());
        assertEquals("Second", error.hook());
        assertEquals(frozen(parent, BATCH, "b-1", NOTE, "n-1", COUNT, 2), error.flow());
    }

    /**
     * Spec R15.3: a failure of another call that crosses an action keeps the flow its own root attached, and the
     * abort of this call, which carries it as cause, holds this call's flow.
     */
    @Test
    void aFailureOfAnotherCallThatCrossesAnActionKeepsItsOwnFlow() {
        Doc doc = new Doc("1", DRAFT);
        Doc otherDoc = new Doc("other", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> other = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(NOTE, "other");
                            throw new IllegalStateException("other failed");
                        })));
        StateMachine<Doc, DocStatus, DocTransition> machine = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(BATCH, "b-1");
                            other.async().execute(otherDoc, SUBMIT).join();
                            return ActionOutcome.ok();
                        })));
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(doc, SUBMIT));
        CallFailedException cause = assertInstanceOf(CallFailedException.class, error.getCause());
        assertNotSame(error.call(), cause.call());
        assertEquals(frozen(doc, BATCH, "b-1"), error.flow());
        assertEquals(frozen(otherDoc, NOTE, "other"), cause.flow());
    }

    /**
     * A reevaluation that fails before it runs an edge has no transaction, so no flow: reading the state, a watch
     * function that throws, and a chain of watch reactions over its limit all carry the empty flow.
     */
    @Test
    void aReevaluationThatFailsBeforeItRunsAnEdgeCarriesTheEmptyFlow() {
        Doc unreadable = new Doc("1", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> blind = StateMachines.from(docs(UnaryOperator.identity()))
                .state((Doc doc) -> {
                    throw new IllegalStateException("unreadable");
                }, Doc::setStatus)
                .build();
        TransitionAbortedException read = assertThrows(TransitionAbortedException.class,
                () -> blind.reevaluate(unreadable));
        assertEquals(CallPhase.RESOLVE, read.phase());
        assertSame(FlowView.empty(), read.flow());

        GraphDefinition<Doc, DocStatus, DocTransition> broken = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(DRAFT, DOCS, (Doc owner) -> List.of(owner), (Doc moved) -> Optional.of(moved))
                    .when("broken", (Doc owner, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> {
                        throw new IllegalStateException("broken condition");
                    }).execute(SUBMIT)
                    .endWatch()
                .build();
        TransitionAbortedException watch = assertThrows(TransitionAbortedException.class,
                () -> standalone(broken).reevaluate(new Doc("2", DRAFT)));
        assertEquals(CallPhase.RESOLVE, watch.phase());
        assertSame(FlowView.empty(), watch.flow());

        GraphDefinition<Doc, DocStatus, DocTransition> pingPong = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(DRAFT, DOCS, (Doc owner) -> List.of(owner), (Doc moved) -> Optional.of(moved))
                    .when("always", (Doc owner, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> true).execute(SUBMIT)
                    .endWatch()
                .watch(REVIEW, DOCS, (Doc owner) -> List.of(owner), (Doc moved) -> Optional.of(moved))
                    .when("always", (Doc owner, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> true).execute(SEND_BACK)
                    .endWatch()
                .build();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(pingPong).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .maxWatchChain(1)
                .build()) {
            Outcome<Doc, DocStatus, DocTransition> root = registry.of(DOCS).execute(new Doc("3", DRAFT), SUBMIT);
            Reaction.Done first = assertInstanceOf(Reaction.Done.class, root.reactions().get(0));
            Reaction.Aborted second = assertInstanceOf(Reaction.Aborted.class,
                    first.outcome().reactions().get(0));
            WatchChainExceededException chain = assertInstanceOf(WatchChainExceededException.class, second.cause());
            assertSame(FlowView.empty(), chain.flow());
        }
    }

    /** Spec D17: an Error propagates as itself and carries no flow; the hook's Error too. */
    @Test
    void anErrorPropagatesUnchangedAndCarriesNoFlow() {
        Fatal fatal = new Fatal();
        StateMachine<Doc, DocStatus, DocTransition> acting = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(BATCH, "b-1");
                            throw fatal;
                        })));
        assertSame(fatal, assertThrows(Fatal.class, () -> acting.execute(new Doc("1", DRAFT), SUBMIT)));
        StateMachine<Doc, DocStatus, DocTransition> hooked = standalone(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.afterCommitWith("Persist",
                        (Doc committed, CommitContext<DocStatus, DocTransition> commit) -> {
                            commit.flow().put(NOTE, "n-1");
                            throw fatal;
                        })));
        assertSame(fatal, assertThrows(Fatal.class, () -> hooked.execute(new Doc("2", DRAFT), SUBMIT)));
    }

    /**
     * The observers receive the outcome and the failure the caller gets, so they read the same flow: the root
     * attaches the flow to its failure before it tells them.
     */
    @Test
    void theObserversReadTheFlowTheCallerReceives() {
        List<FlowView> observed = new ArrayList<>();
        MachineObserver observer = new MachineObserver() {
            @Override
            public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                observed.add(outcome.flow());
            }

            @Override
            public void onFailed(MachineCall call, CallFailure failure, Duration elapsed) {
                observed.add(((CallFailedException) failure.error()).flow());
            }
        };
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(BATCH, ctx.entity().id());
                            if (ctx.entity().id().equals("2")) {
                                throw new IllegalStateException("reviewer unavailable");
                            }
                            return ActionOutcome.ok();
                        }));
        Doc applied = new Doc("1", DRAFT);
        Doc aborted = new Doc("2", DRAFT);
        Outcome<Doc, DocStatus, DocTransition> outcome;
        TransitionAbortedException error;
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .observer(observer)
                .build()) {
            outcome = registry.of(DOCS).execute(applied, SUBMIT);
            error = assertThrows(TransitionAbortedException.class, () -> registry.of(DOCS).execute(aborted, SUBMIT));
        }
        assertEquals(2, observed.size());
        assertSame(outcome.flow(), observed.get(0));
        assertSame(error.flow(), observed.get(1));
        assertEquals(frozen(aborted, BATCH, "2"), observed.get(1));
    }

    /**
     * Review Focus 1: the handed-out flow has no owner thread. Another thread reads the flow of an outcome and of a
     * failure after the call returned, and the caller of an asynchronous call reads the flow its executor's thread
     * froze. Nothing reaches the live flow any more: a handle an action kept, and the view a guard kept, throw
     * StaleContextException on every operation, and the handed-out flow does not change.
     */
    @Test
    void anotherThreadReadsTheHandedOutFlowAndAKeptHandleIsStale() throws InterruptedException {
        AtomicReference<FlowContext> kept = new AtomicReference<>();
        AtomicReference<FlowView> guardView = new AtomicReference<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("Ready", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            guardView.set(ctx.flow());
                            return GuardResult.pass();
                        })
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            kept.set(ctx.flow());
                            ctx.flow().put(BATCH, ctx.entity().id());
                            if (ctx.entity().id().equals("2")) {
                                throw new IllegalStateException("reviewer unavailable");
                            }
                            return ActionOutcome.ok();
                        }));
        StateMachine<Doc, DocStatus, DocTransition> machine = standalone(graph);
        Doc applied = new Doc("1", DRAFT);
        Doc aborted = new Doc("2", DRAFT);
        Doc queued = new Doc("3", DRAFT);
        Outcome<Doc, DocStatus, DocTransition> outcome = machine.execute(applied, SUBMIT);
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(aborted, SUBMIT));
        List<Object> read = new ArrayList<>();
        Thread reader = new Thread(() -> {
            for (FlowView flow : List.of(outcome.flow(), error.flow())) {
                read.add(flow.get(BATCH));
                read.add(List.copyOf(flow.keys()));
                read.add(flow.entity().orElseThrow());
                read.add(flow.entity(Doc.class).orElseThrow());
            }
        });
        reader.start();
        reader.join();
        assertEquals(List.of(Optional.of("1"), List.of(BATCH), applied, applied,
                Optional.of("2"), List.of(BATCH), aborted, aborted), read);

        Outcome<Doc, DocStatus, DocTransition> async = machine.async().execute(queued, SUBMIT).join();
        assertEquals(frozen(queued, BATCH, "3"), async.flow());

        assertThrows(StaleContextException.class, () -> kept.get().get(BATCH));
        assertThrows(StaleContextException.class, () -> kept.get().keys());
        assertThrows(StaleContextException.class, () -> kept.get().entity());
        assertThrows(StaleContextException.class, () -> kept.get().put(BATCH, "late"));
        assertThrows(StaleContextException.class, () -> kept.get().remove(BATCH));
        assertThrows(StaleContextException.class, () -> guardView.get().keys());
        assertEquals(frozen(queued, BATCH, "3"), async.flow());
    }
}
