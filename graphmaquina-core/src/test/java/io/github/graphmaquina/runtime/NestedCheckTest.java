package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocGraphs.docs;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.ChoiceEligibility;
import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.EligibilityCheckException;
import io.github.graphmaquina.error.ReentrantTransitionException;
import io.github.graphmaquina.error.StateMachineException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocMachines;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.Facades;
import io.github.graphmaquina.fixtures.GuardProbe;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A synchronous guard may start a check, nested in the call or check it runs for, of an entity that no enclosing call
 * or check holds: of another set, of the same machine, a parent its child's binding check only registered, or, from
 * the guard of a nested check, a third entity. The check of a held entity is refused before it starts: the root entity
 * of an enclosing call, the entity of a cascade step in flight, or one a cascade moved before. Inside a nested check, a
 * read of a held entity, through a context's peer view or a binding lookup, throws into the code that made it, and the
 * nested check fails into the guard. Everything else stays refused: a write from a nested check's guard, a check from a
 * state getter, an identity function, an action or an asynchronous guard's test call. A nested check whose identity
 * throws fails the guard and leaves the thread able to run the next call.
 */
class NestedCheckTest {

    /** States of a folder, which holds its doc while it is FILING. */
    enum FolderStatus { OPEN, FILING, CLOSED }

    /** Identifiers of the folder machine: CLOSE is the exit its doc takes once archived. */
    enum FolderTransition { FILE, ABANDON, CLOSE }

    static final MachineKey<Folder, FolderStatus, FolderTransition> FOLDERS =
            MachineKey.of(Folder.class, FolderStatus.class, FolderTransition.class);

    /** A folder and the doc it holds while it is FILING. */
    @Getter
    @AllArgsConstructor
    static final class Folder {

        private final String id;
        private FolderStatus status;
        private final Doc doc;

        void setStatus(FolderStatus status) { this.status = status; }

        @Override
        public String toString() { return "Folder#" + id; }
    }

    /**
     * The doc machine of a registry of its own, whose SUBMIT and DISCARD edges {@code submit} and {@code discard}
     * shape.
     */
    private static StateMachine<Doc, DocStatus, DocTransition> machine(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit,
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> discard) {
        return DocMachines.registry(docs(submit, discard)).of(DOCS);
    }

    /** An edge left as it is. */
    private static UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> plain() {
        return UnaryOperator.<TransitionBuilder<Doc, DocStatus, DocTransition>>identity();
    }

    /** An edge whose guard {@code asks} hands its context to what {@code asks} maps its entity to, then passes. */
    private static UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> asking(
            Map<Doc, Consumer<GuardContext<Doc, DocStatus, DocTransition>>> asks) {
        return (TransitionBuilder<Doc, DocStatus, DocTransition> edge) -> edge.guard("asks",
                (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                    asks.getOrDefault(ctx.entity(), (GuardContext<Doc, DocStatus, DocTransition> none) -> { })
                            .accept(ctx);
                    return GuardResult.pass();
                });
    }

    /** A DISCARD edge whose action cascades SUBMIT to each of {@code peers}, in order. */
    private static UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> cascading(Doc... peers) {
        return (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.action("cascades",
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    for (Doc peer : peers) {
                        ctx.cascade(DOCS, peer, SUBMIT);
                    }
                    return ActionOutcome.ok();
                });
    }

    /** The doc machine whose SUBMIT edge {@code submit} shapes; its getter runs {@code ask} on {@code asked}. */
    private static StateMachine<Doc, DocStatus, DocTransition> readingAsks(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit, Doc asked, Runnable ask) {
        Function<Doc, DocStatus> getter = (Doc doc) -> {
            if (doc == asked) {
                ask.run();
            }
            return doc.status();
        };
        return MachineRegistry.builder()
                .add(StateMachines.from(docs(submit)).state(getter, Doc::setStatus).identity(Doc::id))
                .build()
                .of(DOCS);
    }

    /**
     * A registry of the doc machine, whose SUBMIT edge {@code submit} shapes, and of the folder machine, whose FILING
     * state holds the doc of {@code folder}, the parent the lookup of any doc finds, and whose ABANDON edge
     * {@code abandon} shapes.
     */
    private static MachineRegistry filing(
            Folder folder, UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit,
            UnaryOperator<TransitionBuilder<Folder, FolderStatus, FolderTransition>> abandon) {
        GraphDefinition<Doc, DocStatus, DocTransition> children = docs(submit);
        GraphBuilder<Folder, FolderStatus, FolderTransition> graph = StateGraph.define(FOLDERS)
                .initial(FolderStatus.OPEN).terminal(FolderStatus.CLOSED)
                .statePolicy(FolderStatus.OPEN, "an open folder files its doc")
                .statePolicy(FolderStatus.FILING, "a filing folder is abandoned, or closes once its doc is archived")
                .transition(FolderTransition.FILE, FolderStatus.OPEN, FolderStatus.FILING).endTransition();
        graph = abandon.apply(graph.transition(FolderTransition.ABANDON, FolderStatus.FILING, FolderStatus.CLOSED))
                .endTransition();
        GraphDefinition<Folder, FolderStatus, FolderTransition> folders = graph
                .transition(FolderTransition.CLOSE, FolderStatus.FILING, FolderStatus.CLOSED).endTransition()
                .submachine(FolderStatus.FILING, children, (Folder each) -> Optional.of(each.doc()),
                        (Doc doc) -> Optional.of(folder))
                    .exit(DocStatus.ARCHIVED, FolderTransition.CLOSE)
                    .endSubmachine()
                .build();
        return MachineRegistry.builder()
                .add(StateMachines.from(children).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .add(StateMachines.from(folders).state(Folder::status, Folder::setStatus).identity(Folder::id))
                .build();
    }

    /** The simple name of the library exception {@code call} throws, or {@code ran} when it returns. */
    private static String tried(Runnable call) {
        try {
            call.run();
            return "ran";
        } catch (StateMachineException refused) {
            return refused.getClass().getSimpleName();
        }
    }

    /** The simple name of what {@code call} throws, which must be a library exception. */
    private static String refusal(Executable call) {
        return assertThrows(StateMachineException.class, call).getClass().getSimpleName();
    }

    @Test
    void aGuardChecksAnEntityOfAnotherSet() {
        StateMachine<Doc, DocStatus, DocTransition> otherSet = DocMachines.registry(docs(plain())).of(DOCS);
        Doc root = new Doc("1", DRAFT);
        Doc other = new Doc("2", DRAFT);
        List<Eligibility<Doc, DocStatus, DocTransition>> seen = new ArrayList<>();
        StateMachine<Doc, DocStatus, DocTransition> docs = machine(asking(Map.of(root,
                (GuardContext<Doc, DocStatus, DocTransition> ctx) -> seen.add(otherSet.eligibility(other, SUBMIT)))),
                plain());
        assertInstanceOf(Outcome.Applied.class, docs.execute(root, SUBMIT));
        assertEquals(List.of(new Eligibility.Eligible<>(other, DRAFT, SUBMIT)), seen);
        assertEquals(REVIEW, root.status());
        assertEquals(DRAFT, other.status());
    }

    @Test
    void aGuardChecksAnotherEntityOfTheSameMachine() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        Doc root = new Doc("1", DRAFT);
        Doc other = new Doc("2", DRAFT);
        List<Eligibility<Doc, DocStatus, DocTransition>> seen = new ArrayList<>();
        self.set(machine(asking(Map.of(root,
                (GuardContext<Doc, DocStatus, DocTransition> ctx) -> seen.add(self.get().eligibility(other, DISCARD)))),
                plain()));
        assertInstanceOf(Outcome.Applied.class, self.get().execute(root, SUBMIT));
        assertEquals(List.of(new Eligibility.Eligible<>(other, DRAFT, DISCARD)), seen);
        assertEquals(DRAFT, other.status());
    }

    /** By its own instance or another one with its identity, and none of the guards of the check runs. */
    @Test
    void aCheckOfTheRootEntityFromItsGuardIsRefused() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        GuardProbe<Doc, DocStatus, DocTransition> counts = new GuardProbe<>();
        Doc root = new Doc("1", DRAFT);
        Doc twin = new Doc("1", DRAFT);
        List<String> refusals = new ArrayList<>();
        self.set(machine(asking(Map.of(root, (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
            refusals.add(refusal(() -> self.get().eligibility(root, DISCARD)));
            refusals.add(refusal(() -> self.get().eligibility(twin, DISCARD)));
        })), (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.guard("counts", counts)));
        assertInstanceOf(Outcome.Applied.class, self.get().execute(root, SUBMIT));
        assertEquals(List.of("ReentrantTransitionException", "ReentrantTransitionException"), refusals);
        assertEquals(0, counts.calls);
    }

    /** The step's guard runs in ACTION, and checks an entity the call does not hold all the same. */
    @Test
    void aCheckOfACascadeStepEntityFromItsGuardIsRefused() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        List<ReentrantTransitionException> caught = new ArrayList<>();
        List<Eligibility<Doc, DocStatus, DocTransition>> seen = new ArrayList<>();
        Doc root = new Doc("1", DRAFT);
        Doc step = new Doc("2", DRAFT);
        Doc unrelated = new Doc("3", DRAFT);
        self.set(machine(asking(Map.of(step, (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
            caught.add(assertThrows(ReentrantTransitionException.class,
                    () -> self.get().eligibility(step, DISCARD)));
            seen.add(self.get().eligibility(unrelated, DISCARD));
        })), cascading(step)));
        assertInstanceOf(Outcome.Applied.class, self.get().execute(root, DISCARD));
        assertEquals(1, caught.size());
        assertSame(step, caught.get(0).entity());
        assertEquals(DOCS, caught.get(0).key());
        assertEquals(List.of(new Eligibility.Eligible<>(unrelated, DRAFT, DISCARD)), seen);
        assertEquals(REVIEW, step.status());
    }

    @Test
    void aCheckOfAnEntityWithAPendingHopIsRefused() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        List<ReentrantTransitionException> caught = new ArrayList<>();
        Doc root = new Doc("1", DRAFT);
        Doc moved = new Doc("2", DRAFT);
        Doc asker = new Doc("3", DRAFT);
        self.set(machine(asking(Map.of(asker, (GuardContext<Doc, DocStatus, DocTransition> ctx) -> caught.add(
                assertThrows(ReentrantTransitionException.class, () -> self.get().eligibility(moved, PUBLISH))))),
                cascading(moved, asker)));
        assertInstanceOf(Outcome.Applied.class, self.get().execute(root, DISCARD));
        assertEquals(1, caught.size());
        assertSame(moved, caught.get(0).entity());
        assertEquals(DOCS, caught.get(0).key());
        assertEquals(List.of(REVIEW, REVIEW), List.of(moved.status(), asker.status()));
    }

    /** The child's binding check only registered its parent, so the child's guard checks it as any other entity. */
    @Test
    void aChildGuardMayCheckItsParentWhenNothingIsPending() {
        AtomicReference<MachineRegistry> registry = new AtomicReference<>();
        Folder folder = new Folder("f1", FolderStatus.FILING, new Doc("1", DRAFT));
        List<Eligibility<Folder, FolderStatus, FolderTransition>> seen = new ArrayList<>();
        registry.set(filing(folder, asking(Map.of(folder.doc(), (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                        seen.add(registry.get().of(FOLDERS).eligibility(folder, FolderTransition.ABANDON)))),
                UnaryOperator.<TransitionBuilder<Folder, FolderStatus, FolderTransition>>identity()));
        assertInstanceOf(Outcome.Applied.class, registry.get().of(DOCS).execute(folder.doc(), SUBMIT));
        assertEquals(List.of(new Eligibility.Eligible<>(folder, FolderStatus.FILING, FolderTransition.ABANDON)),
                seen);
        assertEquals(REVIEW, folder.doc().status());
    }

    /** The parent is the root entity of the call, so the binding check of its child's nested check cannot read it. */
    @Test
    void aNestedChecksBindingLookupOfAHeldParentThrows() {
        AtomicReference<MachineRegistry> registry = new AtomicReference<>();
        Folder folder = new Folder("f1", FolderStatus.FILING, new Doc("1", DRAFT));
        List<EligibilityCheckException> caught = new ArrayList<>();
        registry.set(filing(folder, plain(), (TransitionBuilder<Folder, FolderStatus, FolderTransition> abandon) ->
                abandon.guard("asks", (GuardContext<Folder, FolderStatus, FolderTransition> ctx) -> {
                    caught.add(assertThrows(EligibilityCheckException.class,
                            () -> registry.get().of(DOCS).eligibility(folder.doc(), SUBMIT)));
                    return GuardResult.pass();
                })));
        assertInstanceOf(Outcome.Applied.class,
                registry.get().of(FOLDERS).execute(folder, FolderTransition.ABANDON));
        assertEquals(1, caught.size());
        ReentrantTransitionException held =
                assertInstanceOf(ReentrantTransitionException.class, caught.get(0).getCause());
        assertSame(folder, held.entity());
        assertEquals(FOLDERS, held.key());
        assertEquals(Optional.of(DRAFT), caught.get(0).from());
        assertEquals(FolderStatus.CLOSED, folder.status());
    }

    /** The second check runs from the guard of the first, which is nested in the call. */
    @Test
    void aNestedChecksGuardMayCheckAThirdEntity() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        Doc root = new Doc("1", DRAFT);
        Doc second = new Doc("2", DRAFT);
        Doc third = new Doc("3", DRAFT);
        List<Object> seen = new ArrayList<>();
        self.set(machine(asking(Map.of(
                root, (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                        seen.add(self.get().eligibility(second, SUBMIT)),
                second, (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                    seen.add(ctx.peer(DOCS).stateOf(third));
                    seen.add(self.get().eligibility(third, DISCARD));
                })), plain()));
        assertInstanceOf(Outcome.Applied.class, self.get().execute(root, SUBMIT));
        assertEquals(List.of(DRAFT, new Eligibility.Eligible<>(third, DRAFT, DISCARD),
                new Eligibility.Eligible<>(second, DRAFT, SUBMIT)), seen);
    }

    /** Each read of a peer view that a nested check's guard can make of the root entity. */
    static Stream<Named<BiFunction<PeerView<Doc, DocStatus, DocTransition>, Doc, Object>>> reads() {
        BiFunction<PeerView<Doc, DocStatus, DocTransition>, Doc, Object> stateOf =
                (PeerView<Doc, DocStatus, DocTransition> view, Doc doc) -> view.stateOf(doc);
        BiFunction<PeerView<Doc, DocStatus, DocTransition>, Doc, Object> allIn =
                (PeerView<Doc, DocStatus, DocTransition> view, Doc doc) -> view.allIn(List.of(doc), DRAFT);
        BiFunction<PeerView<Doc, DocStatus, DocTransition>, Doc, Object> anyIn =
                (PeerView<Doc, DocStatus, DocTransition> view, Doc doc) -> view.anyIn(List.of(doc), DRAFT);
        return Stream.of(Named.of("stateOf", stateOf), Named.of("allIn", allIn), Named.of("anyIn", anyIn));
    }

    /** The read throws into the nested guard, which fails the nested check, which fails the root's guard. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reads")
    void aNestedChecksPeerReadOfAHeldEntityThrows(
            BiFunction<PeerView<Doc, DocStatus, DocTransition>, Doc, Object> read) {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        Doc root = new Doc("1", DRAFT);
        Doc other = new Doc("2", DRAFT);
        self.set(machine(asking(Map.of(root, (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                        self.get().eligibility(other, DISCARD))),
                asking(Map.of(other, (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                        read.apply(ctx.peer(DOCS), root)))));
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> self.get().execute(root, SUBMIT));
        assertEquals(CallPhase.GUARD, error.phase());
        EligibilityCheckException nested = assertInstanceOf(EligibilityCheckException.class, error.getCause());
        ReentrantTransitionException held = assertInstanceOf(ReentrantTransitionException.class, nested.getCause());
        assertSame(root, held.entity());
        assertEquals(DOCS, held.key());
        assertSame(other, nested.entity());
        assertEquals(Optional.of("asks"), nested.guard());
        assertEquals(DRAFT, root.status());
    }

    /** The root guard hands its own context to the nested check's guard, whose read of the root through it throws. */
    @Test
    void aNestedChecksReadOfAHeldEntityThroughAnEnclosingContextThrows() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        AtomicReference<GuardContext<Doc, DocStatus, DocTransition>> enclosing = new AtomicReference<>();
        Doc root = new Doc("1", DRAFT);
        Doc other = new Doc("2", DRAFT);
        self.set(machine(asking(Map.of(root, (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
            enclosing.set(ctx);
            self.get().eligibility(other, DISCARD);
        })), asking(Map.of(other, (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                enclosing.get().peer(DOCS).stateOf(root)))));
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> self.get().execute(root, SUBMIT));
        EligibilityCheckException nested = assertInstanceOf(EligibilityCheckException.class, error.getCause());
        ReentrantTransitionException held = assertInstanceOf(ReentrantTransitionException.class, nested.getCause());
        assertSame(root, held.entity());
        assertEquals(DOCS, held.key());
        assertSame(other, nested.entity());
    }

    /**
     * A nested check that fails prints its entity's identity once it gave the thread back to the enclosing guard:
     * that text, application code, runs as no guard does, so a check it starts is refused, as everywhere else.
     */
    @Test
    void anIdentityTextThatAFailedNestedCheckPrintsCannotStartACheck() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        Doc root = new Doc("1", DRAFT);
        Doc other = new Doc("2", DRAFT);
        Doc third = new Doc("3", DRAFT);
        List<String> shown = new ArrayList<>();
        Runnable onShow = () -> shown.add(tried(() -> self.get().eligibility(third, DISCARD)));
        List<String> seen = new ArrayList<>();
        self.set(MachineRegistry.builder()
                .add(StateMachines.from(docs(asking(Map.of(root, (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                                seen.add(refusal(() -> self.get().eligibility(other, DISCARD))))),
                        asking(Map.of(other, (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw new IllegalStateException("the other cannot tell");
                        }))))
                        .state(Doc::status, Doc::setStatus).identity((Doc doc) -> new Shown(doc.id(), onShow)))
                .build()
                .of(DOCS));
        assertInstanceOf(Outcome.Applied.class, self.get().execute(root, SUBMIT));
        assertEquals(List.of("EligibilityCheckException"), seen);
        assertEquals(false, shown.isEmpty());
        assertEquals(List.of("ReentrantTransitionException"), shown.stream().distinct().toList());
    }

    /** An identity that runs {@code onShow} each time it is printed. */
    private record Shown(String id, Runnable onShow) {

        @Override
        public String toString() {
            onShow.run();
            return id;
        }
    }

    /**
     * The root's action keeps its context and cascades to a step, whose guard starts a check. The guard of that check
     * cascades through the kept context, to one doc and to a collection, by key and through a facade the registry does
     * not know, and each is refused before it stages anything or poisons the call, naming the action's machine and
     * entity. The step's own guard, which no nested check encloses, still cascades through it, as before.
     */
    @Test
    void aNestedChecksCascadeThroughAnEnclosingActionsContextIsRefused() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        AtomicReference<TransitionContext<Doc, DocStatus, DocTransition>> kept = new AtomicReference<>();
        Doc root = new Doc("1", DRAFT);
        Doc step = new Doc("2", DRAFT);
        Doc other = new Doc("3", DRAFT);
        Doc target = new Doc("4", DRAFT);
        Doc moved = new Doc("5", DRAFT);
        List<Object> seen = new ArrayList<>();
        List<ReentrantTransitionException> refused = new ArrayList<>();
        self.set(machine(asking(Map.of(step, (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
            seen.add(self.get().eligibility(other, DISCARD));
            seen.add(kept.get().cascade(DOCS, moved, SUBMIT).code());
        })), (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> asking(Map.of(other,
                (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                        refused.addAll(cascadesThrough(kept.get(), target))))
                .apply(discard).action("keeps", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    kept.set(ctx);
                    ctx.cascade(DOCS, step, SUBMIT);
                    return ActionOutcome.ok();
                })));
        assertInstanceOf(Outcome.Applied.class, self.get().execute(root, DISCARD));
        assertEquals(List.of(new Eligibility.Eligible<>(other, DRAFT, DISCARD), OutcomeCode.APPLIED), seen);
        assertEquals(4, refused.size());
        assertEquals(List.of(List.of(DOCS, root)), refused.stream()
                .map((ReentrantTransitionException each) -> List.<Object>of(each.key(), each.entity())).distinct()
                .toList());
        assertEquals(List.of(DRAFT, REVIEW, REVIEW), List.of(target.status(), moved.status(), step.status()));
    }

    /**
     * The root's action keeps its context and cascades to a step, whose guard, with no check nested, cascades through
     * the kept context to a third doc. That doc's action runs as any action does, with no guard running, so the check
     * it tries is refused, as a check from an action is.
     */
    @Test
    void anActionOfAStepThatAGuardCascadesToCannotStartACheck() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        AtomicReference<TransitionContext<Doc, DocStatus, DocTransition>> kept = new AtomicReference<>();
        Doc root = new Doc("1", DRAFT);
        Doc step = new Doc("2", DRAFT);
        Doc moved = new Doc("3", DRAFT);
        Doc other = new Doc("4", DRAFT);
        List<ReentrantTransitionException> refused = new ArrayList<>();
        self.set(machine((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> asking(Map.of(step,
                (GuardContext<Doc, DocStatus, DocTransition> ctx) -> kept.get().cascade(DOCS, moved, SUBMIT)))
                .apply(submit).action("tries", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    if (ctx.entity() == moved) {
                        refused.add(assertThrows(ReentrantTransitionException.class,
                                () -> self.get().eligibility(other, DISCARD)));
                    }
                    return ActionOutcome.ok();
                }), (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.action("keeps",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            kept.set(ctx);
                            ctx.cascade(DOCS, step, SUBMIT);
                            return ActionOutcome.ok();
                        })));
        assertInstanceOf(Outcome.Applied.class, self.get().execute(root, DISCARD));
        assertEquals(1, refused.size());
        assertEquals(List.of(DOCS, other), List.of(refused.get(0).key(), refused.get(0).entity()));
        assertEquals(List.of(REVIEW, REVIEW, DRAFT), List.of(step.status(), moved.status(), other.status()));
    }

    /** What a cascade through {@code kept} to {@code target} throws, by key and by facade, to one doc or to a list. */
    private static List<ReentrantTransitionException> cascadesThrough(
            TransitionContext<Doc, DocStatus, DocTransition> kept, Doc target) {
        return List.of(
                assertThrows(ReentrantTransitionException.class, () -> kept.cascade(DOCS, target, SUBMIT)),
                assertThrows(ReentrantTransitionException.class,
                        () -> kept.cascadeAll(DOCS, List.of(target), SUBMIT, CascadePolicy.FAIL_FAST)),
                assertThrows(ReentrantTransitionException.class,
                        () -> kept.cascade(Facades.Docs.class, target, SUBMIT)),
                assertThrows(ReentrantTransitionException.class,
                        () -> kept.cascadeAll(Facades.Docs.class, List.of(target), SUBMIT, CascadePolicy.FAIL_FAST)));
    }

    /** An execute, a choice, a reevaluation and an advance from a nested check's guard are each refused. */
    @Test
    void executeFromANestedChecksGuardIsRefused() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        Doc root = new Doc("1", DRAFT);
        Doc other = new Doc("2", DRAFT);
        Doc third = new Doc("3", DRAFT);
        List<Object> seen = new ArrayList<>();
        self.set(machine(asking(Map.of(root, (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                        seen.add(self.get().eligibility(other, DISCARD)))),
                asking(Map.of(other, (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                    seen.add(refusal(() -> self.get().execute(third, SUBMIT)));
                    seen.add(refusal(() -> self.get().choose(third, List.of(SUBMIT))));
                    seen.add(refusal(() -> self.get().reevaluate(third)));
                    seen.add(refusal(() -> self.get().advance(third)));
                }))));
        assertInstanceOf(Outcome.Applied.class, self.get().execute(root, SUBMIT));
        assertEquals(List.of("ReentrantTransitionException", "ReentrantTransitionException",
                "ReentrantTransitionException", "ReentrantTransitionException",
                new Eligibility.Eligible<>(other, DRAFT, DISCARD)), seen);
        assertEquals(DRAFT, third.status());
    }

    /** The nested check reads its entity through the getter, which runs as no guard does. */
    @Test
    void aNestedChecksStateGetterCannotStartACheck() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        Doc root = new Doc("1", DRAFT);
        Doc other = new Doc("2", DRAFT);
        Doc third = new Doc("3", DRAFT);
        List<Object> seen = new ArrayList<>();
        self.set(readingAsks(asking(Map.of(root, (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                        seen.add(self.get().eligibility(other, DISCARD)))),
                other, () -> seen.add(refusal(() -> self.get().eligibility(third, DISCARD)))));
        assertInstanceOf(Outcome.Applied.class, self.get().execute(root, SUBMIT));
        assertEquals(List.of("ReentrantTransitionException", new Eligibility.Eligible<>(other, DRAFT, DISCARD)),
                seen);
    }

    /** A guard's peer read runs the peer's getter, which runs as no guard does. */
    @Test
    void aStateGetterThatAGuardsPeerReadRunsCannotStartACheck() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        Doc root = new Doc("1", DRAFT);
        Doc peer = new Doc("2", DRAFT);
        Doc third = new Doc("3", DRAFT);
        List<Object> seen = new ArrayList<>();
        self.set(readingAsks(asking(Map.of(root, (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                        seen.add(ctx.peer(DOCS).stateOf(peer)))),
                peer, () -> seen.add(refusal(() -> self.get().eligibility(third, DISCARD)))));
        assertInstanceOf(Outcome.Applied.class, self.get().execute(root, SUBMIT));
        assertEquals(List.of("ReentrantTransitionException", DRAFT), seen);
    }

    @Test
    void aCheckFromAnActionIsStillRefused() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        List<ReentrantTransitionException> caught = new ArrayList<>();
        Doc other = new Doc("2", DRAFT);
        self.set(machine((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("asks",
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    caught.add(assertThrows(ReentrantTransitionException.class,
                            () -> self.get().eligibility(other, DISCARD)));
                    return ActionOutcome.ok();
                }), plain()));
        assertInstanceOf(Outcome.Applied.class, self.get().execute(new Doc("1", DRAFT), SUBMIT));
        assertEquals(1, caught.size());
        assertSame(other, caught.get(0).entity());
        assertEquals(DOCS, caught.get(0).key());
    }

    /** A cascade step's guard that throws hands its count back: the action that catches the failure is no guard. */
    @Test
    void aCheckFromAnActionAfterAGuardOfItsCascadeThrewIsStillRefused() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        Doc root = new Doc("1", DRAFT);
        Doc step = new Doc("2", DRAFT);
        Doc other = new Doc("3", DRAFT);
        List<String> seen = new ArrayList<>();
        self.set(machine(asking(Map.of(step, (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
            throw new IllegalStateException("the step cannot tell");
        })), (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.action("cascades",
                (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    seen.add(refusal(() -> ctx.cascade(DOCS, step, SUBMIT)));
                    seen.add(refusal(() -> self.get().eligibility(other, DISCARD)));
                    return ActionOutcome.ok();
                })));
        assertThrows(StateMachineException.class, () -> self.get().execute(root, DISCARD));
        assertEquals(2, seen.size());
        assertEquals("ReentrantTransitionException", seen.get(1));
        assertEquals(DRAFT, other.status());
    }

    /** The test call of an asynchronous guard runs on the calling thread, as no synchronous guard does. */
    @Test
    void aCheckFromAnAsyncGuardsTestIsStillRefused() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        List<ReentrantTransitionException> caught = new ArrayList<>();
        Doc other = new Doc("2", DRAFT);
        self.set(DocMachines.registry(docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .asyncGuard("remote", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                    caught.add(assertThrows(ReentrantTransitionException.class,
                            () -> self.get().eligibility(other, DISCARD)));
                    return CompletableFuture.completedFuture(GuardResult.pass());
                })), Duration.ofSeconds(10)).of(DOCS));
        assertInstanceOf(Outcome.Applied.class, self.get().execute(new Doc("1", DRAFT), SUBMIT));
        assertEquals(1, caught.size());
        assertSame(other, caught.get(0).entity());
        assertEquals(DOCS, caught.get(0).key());
    }

    /**
     * Review Focus 3: an identity that throws, or answers null, fails the guard with what it threw, and the root call
     * aborts with it as cause. The thread then runs a call and a nested check as before.
     */
    @Test
    void aNestedCheckWhoseIdentityThrowsFailsTheGuardAndLeavesTheCellClean() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        AtomicReference<Doc> target = new AtomicReference<>(new Doc("boom", DRAFT));
        List<Object> seen = new ArrayList<>();
        Doc root = new Doc("1", DRAFT);
        self.set(MachineRegistry.builder()
                .add(StateMachines.from(docs(asking(Map.of(root, (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                                seen.add(self.get().eligibility(target.get(), DISCARD))))))
                        .state(Doc::status, Doc::setStatus).identity(NestedCheckTest::brokenIdentity))
                .build()
                .of(DOCS));
        assertEquals("no identity for boom", rootCause(self.get(), root, IllegalStateException.class).getMessage());
        target.set(new Doc("none", DRAFT));
        assertEquals("identity of Doc/DocStatus returned null",
                rootCause(self.get(), root, NullPointerException.class).getMessage());
        assertDoesNotThrow(() -> Reentrancy.check(DOCS, root));
        Doc next = new Doc("4", DRAFT);
        target.set(next);
        assertInstanceOf(Outcome.Applied.class, self.get().execute(root, SUBMIT));
        assertEquals(List.of(new Eligibility.Eligible<>(next, DRAFT, DISCARD)), seen);
    }

    /** The id of {@code doc}, except for {@code boom}, whose identity throws, and {@code none}, whose is null. */
    private static Object brokenIdentity(Doc doc) {
        if (doc.id().equals("boom")) {
            throw new IllegalStateException("no identity for boom");
        }
        return doc.id().equals("none") ? null : doc.id();
    }

    /** The cause, of type {@code type}, of the abort in GUARD that SUBMIT for {@code root} ends in. */
    private static <X extends Throwable> X rootCause(StateMachine<Doc, DocStatus, DocTransition> machine, Doc root,
                                                     Class<X> type) {
        TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                () -> machine.execute(root, SUBMIT));
        assertEquals(CallPhase.GUARD, error.phase());
        assertEquals(DRAFT, root.status());
        return assertInstanceOf(type, error.getCause());
    }

    /** An identity function that a nested check runs, before it starts and as it reads its entity, cannot check. */
    @Test
    void anIdentityThatANestedCheckRunsCannotStartACheck() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        Doc root = new Doc("1", DRAFT);
        Doc other = new Doc("2", DRAFT);
        Doc third = new Doc("3", DRAFT);
        List<String> refusals = new ArrayList<>();
        List<Object> seen = new ArrayList<>();
        Function<Doc, Object> identity = (Doc doc) -> {
            if (doc == other) {
                refusals.add(refusal(() -> self.get().eligibility(third, DISCARD)));
            }
            return doc.id();
        };
        self.set(MachineRegistry.builder()
                .add(StateMachines.from(docs(asking(Map.of(root, (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                                seen.add(self.get().eligibility(other, DISCARD))))))
                        .state(Doc::status, Doc::setStatus).identity(identity))
                .build()
                .of(DOCS));
        assertInstanceOf(Outcome.Applied.class, self.get().execute(root, SUBMIT));
        assertEquals(List.of(new Eligibility.Eligible<>(other, DRAFT, DISCARD)), seen);
        assertEquals(List.of("ReentrantTransitionException", "ReentrantTransitionException"), refusals);
    }

    /**
     * A folder's watch condition keeps the view it gets, and a guard of a later call reads a doc through it: the doc's
     * getter, which the engine runs for that read, runs as no guard does, so it cannot start a check.
     */
    @Test
    void aStateGetterThatAKeptWatchViewRunsForAGuardCannotStartACheck() {
        AtomicReference<MachineRegistry> registry = new AtomicReference<>();
        AtomicReference<PeerView<Doc, DocStatus, DocTransition>> kept = new AtomicReference<>();
        Doc root = new Doc("1", DRAFT);
        Doc peer = new Doc("2", DRAFT);
        Folder folder = new Folder("f1", FolderStatus.OPEN, peer);
        List<Object> seen = new ArrayList<>();
        Function<Doc, DocStatus> getter = (Doc doc) -> {
            if (doc == peer) {
                seen.add(tried(() -> registry.get().of(DOCS).eligibility(new Doc("3", DRAFT), DISCARD)));
            }
            return doc.status();
        };
        registry.set(MachineRegistry.builder()
                .add(StateMachines.from(docs(asking(Map.of(root, (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                                seen.add(kept.get().stateOf(peer))))))
                        .state(getter, Doc::setStatus).identity(Doc::id))
                .add(StateMachines.from(keepingFolders(kept)).state(Folder::status, Folder::setStatus)
                        .identity(Folder::id))
                .build());
        assertInstanceOf(Outcome.Ignored.class, registry.get().of(FOLDERS).reevaluate(folder));
        assertInstanceOf(Outcome.Applied.class, registry.get().of(DOCS).execute(root, SUBMIT));
        assertEquals(List.of("ReentrantTransitionException", DRAFT), seen);
    }

    /**
     * The folder graph OPEN -FILE-> FILING -CLOSE-> CLOSED, whose watch of OPEN over the folder's doc stores the view
     * its condition gets in {@code kept} and holds no rule.
     */
    private static GraphDefinition<Folder, FolderStatus, FolderTransition> keepingFolders(
            AtomicReference<PeerView<Doc, DocStatus, DocTransition>> kept) {
        return StateGraph.define(FOLDERS).initial(FolderStatus.OPEN).terminal(FolderStatus.CLOSED)
                .statePolicy(FolderStatus.OPEN, "an open folder files its doc")
                .statePolicy(FolderStatus.FILING, "a filing folder closes")
                .transition(FolderTransition.FILE, FolderStatus.OPEN, FolderStatus.FILING).endTransition()
                .transition(FolderTransition.CLOSE, FolderStatus.FILING, FolderStatus.CLOSED).endTransition()
                .watch(FolderStatus.OPEN, DOCS, (Folder each) -> List.of(each.doc()), (Doc doc) -> Optional.empty())
                    .when("keeps", (Folder owner, Collection<? extends Doc> peers,
                                    PeerView<Doc, DocStatus, DocTransition> view) -> {
                        kept.set(view);
                        return false;
                    })
                    .execute(FolderTransition.FILE)
                    .endWatch()
                .build();
    }

    /** Each candidate gets a nested check of its own; the root entity is refused before the first starts. */
    @Test
    void aChooseEligibilityFromAGuardRuns() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        Doc root = new Doc("1", DRAFT);
        Doc other = new Doc("2", DRAFT);
        List<Object> seen = new ArrayList<>();
        self.set(machine(asking(Map.of(root, (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
            seen.add(self.get().chooseEligibility(other, List.of(PUBLISH, DISCARD)));
            seen.add(refusal(() -> self.get().chooseEligibility(root, List.of(PUBLISH, DISCARD))));
        })), plain()));
        assertInstanceOf(Outcome.Applied.class, self.get().execute(root, SUBMIT));
        ChoiceEligibility.Chosen<?, ?, ?> chosen = assertInstanceOf(ChoiceEligibility.Chosen.class, seen.get(0));
        assertEquals(List.of(other, DRAFT, DISCARD), List.of(chosen.entity(), chosen.from(), chosen.transition()));
        assertEquals(1, chosen.rejected().size());
        assertEquals(PUBLISH, chosen.rejected().get(0).transition());
        assertEquals("ReentrantTransitionException", seen.get(1));
    }
}
