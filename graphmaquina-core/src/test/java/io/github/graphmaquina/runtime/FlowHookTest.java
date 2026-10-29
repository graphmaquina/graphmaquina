package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocGraphs.docs;
import static io.github.graphmaquina.fixtures.DocMachines.registry;
import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static io.github.graphmaquina.fixtures.LogLines.lines;
import static io.github.graphmaquina.fixtures.Thrown.describe;
import static io.github.graphmaquina.fixtures.Thrown.thrownBy;
import static io.github.graphmaquina.fixtures.Thrown.thrownOnAnotherThread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.FlowContext;
import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.StaleContextException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.Item;
import io.github.graphmaquina.fixtures.LibraryLog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;

/**
 * The flow of a call as its post-commit hooks reach it, through {@link CommitContext#flow()}: a hook reads what the
 * steps of the call put, writes values the later hooks of the call read, and reaches the root call's entity, whichever
 * hop it runs for. Each hook gets a handle of its own, which works only on the thread running the hook and only while
 * the hook runs, also when the hook throws. A hook that starts a new call leaves that call a flow of its own. Each
 * write is logged at TRACE with the hook's name.
 */
class FlowHookTest {

    private static final FlowKey<String> BATCH = FlowKey.of("batch", String.class);
    private static final FlowKey<String> NOTE = FlowKey.visible("note", String.class);
    private static final FlowKey<Integer> COUNT = FlowKey.of("count", Integer.class);
    private static final FlowKey<Odd> ODD = FlowKey.visible("odd", Odd.class);
    private static final FlowKey<Counted> COUNTED = FlowKey.visible("counted", Counted.class);

    /** A value whose {@code toString()} throws. */
    private static final class Odd {
        @Override
        public String toString() {
            throw new IllegalStateException("no text");
        }
    }

    /** A value that counts how many times it was printed. */
    private static final class Counted {
        private final AtomicInteger printed = new AtomicInteger();

        @Override
        public String toString() {
            printed.incrementAndGet();
            return "counted";
        }
    }

    /** Every operation of {@code flow}, by name: its four reads and its two writes. */
    private static Map<String, Runnable> operations(FlowContext flow) {
        Map<String, Runnable> operations = new LinkedHashMap<>();
        operations.put("get", () -> flow.get(BATCH));
        operations.put("keys", flow::keys);
        operations.put("entity", flow::entity);
        operations.put("entity(Class)", () -> flow.entity(Doc.class));
        operations.put("trail", flow::trail);
        operations.put("put", () -> flow.put(BATCH, "late"));
        operations.put("remove", () -> flow.remove(BATCH));
        return operations;
    }

    /**
     * {@code describe} of every operation of {@code flow}, by name, each run by
     * {@link io.github.graphmaquina.fixtures.Thrown#thrownBy}.
     */
    private static Map<String, String> outcomes(FlowContext flow) {
        Map<String, String> outcomes = new LinkedHashMap<>();
        operations(flow).forEach((String name, Runnable operation) ->
                outcomes.put(name, describe(thrownBy(operation))));
        return outcomes;
    }

    /** Every operation name of {@link #operations}, each mapped to the same {@code text}, in order. */
    private static Map<String, String> each(String text) {
        Map<String, String> expected = new LinkedHashMap<>();
        for (String name : List.of("get", "keys", "entity", "entity(Class)", "trail", "put", "remove")) {
            expected.put(name, text);
        }
        return expected;
    }

    private static final String STALE =
            "StaleContextException: the context of Doc/DocStatus is no longer valid: the transaction ended";

    /** A hook reads what the steps of its call put, in the order of their writes. */
    @Test
    void aHookReadsWhatTheStepsOfItsCallPut() {
        Doc doc = new Doc("1", DRAFT);
        List<Object> seen = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(BATCH, "b-1");
                            ctx.flow().put(COUNT, 2);
                            return ActionOutcome.ok();
                        })
                        .afterCommitWith("Persist", (Doc committed, CommitContext<DocStatus, DocTransition> commit) -> {
                            seen.add(commit.flow().get(BATCH));
                            seen.add(commit.flow().get(COUNT));
                            seen.add(List.copyOf(commit.flow().keys()));
                        }));
        try (MachineRegistry registry = registry(graph)) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(doc, SUBMIT));
        }
        assertEquals(List.of(Optional.of("b-1"), Optional.of(2), List.of(BATCH, COUNT)), seen);
    }

    /**
     * Spec R14.2: what a hook writes applies at once, and a later hook of the same call reads it. The parent's hop
     * comes first in the log, so its hook runs before the hook of the hop it cascaded to.
     */
    @Test
    void aLaterHookOfTheCallReadsWhatAnEarlierHookWrote() {
        Doc parent = new Doc("1", DRAFT);
        Doc child = new Doc("2", DRAFT);
        List<Object> seen = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.afterCommitWith("Second",
                        (Doc committed, CommitContext<DocStatus, DocTransition> commit) -> {
                            seen.add(commit.flow().get(NOTE));
                            seen.add(commit.flow().get(BATCH));
                            seen.add(List.copyOf(commit.flow().keys()));
                        }),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard
                        .action("Collect", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(BATCH, "b-1");
                            ctx.flow().put(COUNT, 1);
                            ctx.cascade(DOCS, child, SUBMIT);
                            return ActionOutcome.ok();
                        })
                        .afterCommitWith("First", (Doc committed, CommitContext<DocStatus, DocTransition> commit) -> {
                            commit.flow().put(NOTE, "n-1");
                            commit.flow().remove(BATCH);
                            seen.add(commit.flow().get(NOTE));
                        }));
        try (MachineRegistry registry = registry(graph)) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(parent, DISCARD));
        }
        assertEquals(List.of(Optional.of("n-1"), Optional.of("n-1"), Optional.empty(), List.of(COUNT, NOTE)), seen);
    }

    /**
     * The hook of a cascaded hop reaches the same flow as the root's hook, and both reach the root call's entity
     * through it; the entity of the hop is the hook's own argument.
     */
    @Test
    void everyHookOfTheCallReachesTheRootEntity() {
        Doc parent = new Doc("1", DRAFT);
        Doc child = new Doc("2", DRAFT);
        List<Object> roots = new ArrayList<>();
        List<Object> owns = new ArrayList<>();
        List<Object> read = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.afterCommitWith("Peer",
                        (Doc committed, CommitContext<DocStatus, DocTransition> commit) -> {
                            owns.add(committed);
                            roots.add(commit.flow().entity().orElseThrow());
                            roots.add(commit.flow().entity(Doc.class).orElseThrow());
                            read.add(commit.flow().get(BATCH));
                        }),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard
                        .action("Collect", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(BATCH, "b-1");
                            ctx.cascade(DOCS, child, SUBMIT);
                            return ActionOutcome.ok();
                        })
                        .afterCommitWith("Root", (Doc committed, CommitContext<DocStatus, DocTransition> commit) -> {
                            owns.add(committed);
                            roots.add(commit.flow().entity().orElseThrow());
                            read.add(commit.flow().get(BATCH));
                        }));
        try (MachineRegistry registry = registry(graph)) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(parent, DISCARD));
        }
        assertEquals(List.of(parent, child), owns);
        assertEquals(3, roots.size());
        for (Object root : roots) {
            assertSame(parent, root);
        }
        assertEquals(List.of(Optional.of("b-1"), Optional.of("b-1")), read);
    }

    /**
     * Spec R14.1 and R14.3: each hook gets a handle of its own, which goes stale once its hook returned, on every
     * operation, with the machine key and the entity of the hop the hook ran for, here the cascaded child, not the
     * root; a later hook of the same call cannot use the handle of an earlier one. The retained context keeps its
     * metadata.
     */
    @Test
    void theFlowOfAHookIsStaleOnEveryOperationOnceTheHookReturned() {
        Doc parent = new Doc("1", DRAFT);
        Doc child = new Doc("2", DRAFT);
        AtomicReference<Optional<CommitContext<DocStatus, DocTransition>>> first =
                new AtomicReference<>(Optional.empty());
        AtomicReference<Optional<CommitContext<DocStatus, DocTransition>>> second =
                new AtomicReference<>(Optional.empty());
        Map<String, String> inTheLaterHook = new LinkedHashMap<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.afterCommitWith("Second",
                        (Doc committed, CommitContext<DocStatus, DocTransition> commit) -> {
                            second.set(Optional.of(commit));
                            inTheLaterHook.putAll(outcomes(first.get().orElseThrow().flow()));
                        }),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard
                        .action("Collect", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.cascade(DOCS, child, SUBMIT);
                            return ActionOutcome.ok();
                        })
                        .afterCommitWith("First", (Doc committed, CommitContext<DocStatus, DocTransition> commit) ->
                                first.set(Optional.of(commit))));
        try (MachineRegistry registry = registry(graph)) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(parent, DISCARD));
        }
        assertEquals(each(STALE), inTheLaterHook);
        assertNotEquals(first.get().orElseThrow().flow(), second.get().orElseThrow().flow());
        assertEquals(each(STALE), outcomes(second.get().orElseThrow().flow()));
        StaleContextException stale = assertThrows(StaleContextException.class,
                () -> second.get().orElseThrow().flow().keys());
        assertSame(DOCS, stale.key());
        assertSame(child, stale.entity());
        assertEquals(DRAFT, second.get().orElseThrow().from());
        assertEquals(SUBMIT, second.get().orElseThrow().transition());
        assertEquals(REVIEW, second.get().orElseThrow().to());
        assertEquals(TransitionKind.MOVE, second.get().orElseThrow().kind());
    }

    /** Spec R14.3: the flow of a hook that throws goes stale too, on every operation. */
    @Test
    void theFlowOfAHookIsStaleOnEveryOperationOnceTheHookThrew() {
        Doc doc = new Doc("1", DRAFT);
        AtomicReference<Optional<FlowContext>> retained =
                new AtomicReference<>(Optional.empty());
        IllegalStateException boom = new IllegalStateException("persistence unavailable");
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.afterCommitWith("Persist",
                        (Doc committed, CommitContext<DocStatus, DocTransition> commit) -> {
                            commit.flow().put(BATCH, "b-1");
                            retained.set(Optional.of(commit.flow()));
                            throw boom;
                        }));
        try (MachineRegistry registry = registry(graph)) {
            AfterCommitException error = assertThrows(AfterCommitException.class,
                    () -> registry.of(DOCS).execute(doc, SUBMIT));
            assertSame(boom, error.getCause());
        }
        assertEquals(each(STALE), outcomes(retained.get().orElseThrow()));
    }

    /**
     * A hook's flow checks its lifetime before its thread: used on another thread once its hook returned, every
     * operation throws StaleContextException, not WrongThreadException.
     */
    @Test
    void theFlowOfAHookChecksItsLifetimeBeforeItsThread() {
        AtomicReference<Optional<FlowContext>> retained =
                new AtomicReference<>(Optional.empty());
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.afterCommitWith("Persist",
                        (Doc committed, CommitContext<DocStatus, DocTransition> commit) ->
                                retained.set(Optional.of(commit.flow()))));
        try (MachineRegistry registry = registry(graph)) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT));
        }
        Map<String, String> offThread = new LinkedHashMap<>();
        operations(retained.get().orElseThrow()).forEach((String name, Runnable operation) ->
                offThread.put(name, describe(thrownOnAnotherThread(operation))));
        assertEquals(each(STALE), offThread);
    }

    /**
     * While the hook runs, every operation of its flow works only on the thread running it: on another thread each
     * throws WrongThreadException, which names the hook and that thread, and a write refused there stores nothing.
     */
    @Test
    void everyOperationOfAHooksFlowRefusesAnotherThread() {
        Doc doc = new Doc("1", DRAFT);
        AtomicReference<Optional<String>> owner =
                new AtomicReference<>(Optional.empty());
        Map<String, String> offThread = new LinkedHashMap<>();
        List<Object> afterwards = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.afterCommitWith("Persist",
                        (Doc committed, CommitContext<DocStatus, DocTransition> commit) -> {
                            owner.set(Optional.of(Thread.currentThread().getName()));
                            operations(commit.flow()).forEach((String name, Runnable operation) ->
                                    offThread.put(name, describe(thrownOnAnotherThread(operation))));
                            afterwards.add(commit.flow().get(BATCH));
                            afterwards.add(commit.flow().keys());
                        }));
        try (MachineRegistry registry = registry(graph)) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(doc, SUBMIT));
        }
        assertEquals(each("WrongThreadException: the flow of the hook Persist can only be used from the thread"
                + " running it: " + owner.get().orElseThrow()), offThread);
        assertEquals(List.of(Optional.empty(), Set.of()), afterwards);
    }

    /**
     * A hook's flow refuses what an action's flow refuses: a null key or value, a value of another class than its
     * key's, and a null or primitive class token for the entity, or a class the root entity is not an instance of.
     */
    @Test
    void aHooksFlowRefusesWhatEveryFlowRefuses() {
        Doc doc = new Doc("1", DRAFT);
        @SuppressWarnings("unchecked")
        FlowKey<Object> loose = (FlowKey<Object>) (FlowKey<?>) BATCH;
        List<String> refused = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.afterCommitWith("Persist",
                        (Doc committed, CommitContext<DocStatus, DocTransition> commit) -> {
                            FlowContext flow = commit.flow();
                            refused.add("get: " + describe(thrownBy(() -> flow.get(null))));
                            refused.add("put key: " + describe(thrownBy(() -> flow.put(null, "b-1"))));
                            refused.add("put value: " + describe(thrownBy(() -> flow.put(BATCH, null))));
                            refused.add("put type: " + describe(thrownBy(() -> flow.put(loose, 5))));
                            refused.add("remove: " + describe(thrownBy(() -> flow.remove(null))));
                            refused.add("entity null: " + describe(thrownBy(() -> flow.entity(null))));
                            refused.add("entity int: " + describe(thrownBy(() -> flow.entity(int.class))));
                            refused.add("entity Item: " + describe(thrownBy(() -> flow.entity(Item.class))));
                            refused.add("keys: " + flow.keys());
                        }));
        try (MachineRegistry registry = registry(graph)) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(doc, SUBMIT));
        }
        assertEquals(List.of("get: NullPointerException: key", "put key: NullPointerException: key",
                "put value: NullPointerException: value",
                "put type: ClassCastException: Cannot cast java.lang.Integer to java.lang.String",
                "remove: NullPointerException: key", "entity null: NullPointerException: type",
                "entity int: IllegalArgumentException: entity type must be a reference type; use its wrapper class",
                "entity Item: ClassCastException: Cannot cast " + Doc.class.getName() + " to " + Item.class.getName(),
                "keys: []"), refused);
    }

    /**
     * Review Focus 4: a hook may start a new call, since the root releases its reentrancy marker before the hooks.
     * That call has a flow of its own, which its action and its hook use, and once it returned, the outer hook's
     * handle still reads and writes the flow of the outer call.
     */
    @Test
    void aHookThatStartsANewCallKeepsTheFlowOfItsOwnCall() {
        Doc outer = new Doc("1", DRAFT);
        Doc inner = new Doc("2", DRAFT);
        AtomicReference<Optional<StateMachine<Doc, DocStatus, DocTransition>>> machine =
                new AtomicReference<>(Optional.empty());
        List<String> seen = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(BATCH, "outer");
                            return ActionOutcome.ok();
                        })
                        .afterCommitWith("Outer", (Doc committed, CommitContext<DocStatus, DocTransition> commit) -> {
                            FlowContext flow = commit.flow();
                            seen.add("outer hook before: " + flow.get(BATCH));
                            seen.add("nested call: " + machine.get().orElseThrow().execute(inner, DISCARD).code());
                            seen.add("outer hook after: " + flow.get(BATCH) + " " + flow.entity().orElseThrow());
                            flow.put(NOTE, "after");
                            seen.add("outer hook wrote: " + flow.get(NOTE) + " " + flow.keys());
                        }),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard
                        .action("Collect", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            seen.add("inner action: " + ctx.flow().get(BATCH) + " "
                                    + ctx.flow().entity().orElseThrow());
                            ctx.flow().put(BATCH, "inner");
                            return ActionOutcome.ok();
                        })
                        .afterCommitWith("Inner", (Doc committed, CommitContext<DocStatus, DocTransition> commit) ->
                                seen.add("inner hook: " + commit.flow().get(BATCH) + " "
                                        + commit.flow().entity().orElseThrow())));
        try (MachineRegistry registry = registry(graph)) {
            machine.set(Optional.of(registry.of(DOCS)));
            assertInstanceOf(Outcome.Applied.class, machine.get().orElseThrow().execute(outer, SUBMIT));
        }
        assertEquals(List.of(
                "outer hook before: Optional[outer]",
                "inner action: Optional.empty Doc#2",
                "inner hook: Optional[inner] Doc#2",
                "nested call: APPLIED",
                "outer hook after: Optional[outer] Doc#1",
                "outer hook wrote: Optional[after] [batch:String, note:String]"), seen);
        assertEquals(REVIEW, outer.status());
        assertEquals(ARCHIVED, inner.status());
    }

    /**
     * A hook does not run when an earlier hook of its call throws: the parent's hop runs its hook first, which throws,
     * so the hook of the cascaded child's hop, which would read the flow, never runs, although the child's state was
     * written.
     */
    @Test
    void aHookDoesNotRunWhenAnEarlierHookOfItsCallThrows() {
        Doc parent = new Doc("1", DRAFT);
        Doc child = new Doc("2", DRAFT);
        IllegalStateException boom = new IllegalStateException("persistence unavailable");
        List<String> ran = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.afterCommitWith("Second",
                        (Doc committed, CommitContext<DocStatus, DocTransition> commit) ->
                                ran.add(committed.id() + " " + commit.flow().keys())),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard
                        .action("Collect", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(BATCH, "b-1");
                            ctx.cascade(DOCS, child, SUBMIT);
                            return ActionOutcome.ok();
                        })
                        .afterCommitWith("First", (Doc committed, CommitContext<DocStatus, DocTransition> commit) -> {
                            throw boom;
                        }));
        AfterCommitException error;
        try (MachineRegistry registry = registry(graph)) {
            error = assertThrows(AfterCommitException.class, () -> registry.of(DOCS).execute(parent, DISCARD));
        }
        assertSame(boom, error.getCause());
        assertEquals("First", error.hook());
        assertEquals(List.of(), ran);
        assertEquals(REVIEW, child.status());
    }

    /**
     * The guide's "Set a field only when the call commits": an action leaves a value in the flow, and a hook, declared
     * on the edge and on its recovery, assigns it after the commit. A step that ends well gets the value. A step whose
     * failure takes its route drops its write, so the hook of the recovering hop finds nothing. A call that aborts
     * runs no hook.
     */
    @Test
    void aHookAssignsWhatAnActionLeftInTheFlowOnlyWhenTheHopCommits() {
        Doc shipped = new Doc("1", DRAFT);
        Doc declined = new Doc("2", DRAFT);
        Doc aborted = new Doc("3", DRAFT);
        Doc reviewed = new Doc("4", REVIEW);
        List<String> hooks = new ArrayList<>();
        List<String> assigned = new ArrayList<>();
        BiConsumer<Doc, CommitContext<DocStatus, DocTransition>> assign =
                (Doc committed, CommitContext<DocStatus, DocTransition> commit) -> {
                    hooks.add(committed.id() + " " + commit.transition());
                    commit.flow().get(NOTE).ifPresent((String note) -> assigned.add(committed.id() + " " + note));
                };
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(NOTE, "n-" + ctx.entity().id());
                            if (ctx.entity() == declined) {
                                return ActionOutcome.failed("declined");
                            }
                            if (ctx.entity() == aborted) {
                                ctx.cascade(DOCS, reviewed, SUBMIT);
                            }
                            return ActionOutcome.ok();
                        })
                        .onFailure(DISCARD)
                        .afterCommitWith("Assign", assign),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) ->
                        discard.afterCommitWith("Assign", assign));
        try (MachineRegistry registry = registry(graph)) {
            StateMachine<Doc, DocStatus, DocTransition> machine = registry.of(DOCS);
            assertEquals(OutcomeCode.APPLIED, machine.execute(shipped, SUBMIT).code());
            assertEquals(OutcomeCode.FAILURE_ROUTED, machine.execute(declined, SUBMIT).code());
            assertThrows(TransitionAbortedException.class, () -> machine.execute(aborted, SUBMIT));
        }
        assertEquals(List.of("1 SUBMIT", "2 DISCARD"), hooks);
        assertEquals(List.of("1 n-1"), assigned);
        assertEquals(REVIEW, shipped.status());
        assertEquals(ARCHIVED, declined.status());
        assertEquals(DRAFT, aborted.status());
    }

    /**
     * A hook's handle creates no flow by itself, and no read creates one: its entity needs none, a read before the
     * call's first write answers empty, and an operation it refuses creates none. Its first write that passes its
     * checks creates the call's flow, with the root entity, if no step of the call wrote. Once invalidated, the handle
     * throws StaleContextException with the key and the entity of the hop, here a peer of the root.
     */
    @Test
    void aHooksHandleCreatesTheCallsFlowOnlyOnItsFirstWrite() {
        Doc root = new Doc("1", DRAFT);
        Doc peer = new Doc("2", DRAFT);
        UnitOfWork uow = new UnitOfWork(8, Long.MAX_VALUE);
        FlowHandle.Hook flow = FlowHandle.hook(uow, root, CallLog.OFF, "Persist", new EntityRef(DOCS, "2", peer));
        assertSame(root, flow.entity().orElseThrow());
        assertSame(root, flow.entity(Doc.class).orElseThrow());
        assertEquals(Optional.empty(), flow.get(BATCH));
        assertEquals(Set.of(), flow.keys());
        assertThrows(NullPointerException.class, () -> flow.get(null));
        assertThrows(NullPointerException.class, () -> flow.put(BATCH, null));
        assertFalse(uow.hasFlow());
        flow.put(BATCH, "b-1");
        assertTrue(uow.hasFlow());
        assertSame(root, uow.flow().orElseThrow().rootEntity());
        assertEquals(Optional.of("b-1"), flow.get(BATCH));
        flow.invalidate();
        StaleContextException stale = assertThrows(StaleContextException.class, flow::keys);
        assertSame(DOCS, stale.key());
        assertSame(peer, stale.entity());
    }

    /**
     * Every put and remove of a hook is logged at TRACE, after the line of the hook, with the hook's name and without
     * a depth: a key prints as name:Type, and a visible key adds the value, or a fixed placeholder when its
     * toString() throws, and the call goes on.
     */
    @Test
    void eachWriteOfAHookIsTracedWithTheHooksName() {
        Doc doc = new Doc("1", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.afterCommitWith("Persist",
                        (Doc committed, CommitContext<DocStatus, DocTransition> commit) -> {
                            commit.flow().put(NOTE, "n-1");
                            commit.flow().put(BATCH, "b-1");
                            commit.flow().remove(BATCH);
                            commit.flow().put(ODD, new Odd());
                        }));
        try (MachineRegistry registry = registry(graph); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(doc, SUBMIT));
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINER call=A depth=0 resolved Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW kind=MOVE",
                    "FINER call=A commit-check entities=1 hops=1",
                    "FINER call=A setter Doc/DocStatus#1 DRAFT -> REVIEW entries=1",
                    "FINE call=A committed Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW depth=0",
                    "FINER call=A hook Persist Doc/DocStatus#1 SUBMIT",
                    "FINER call=A hook-flow-put Persist note:String=n-1",
                    "FINER call=A hook-flow-put Persist batch:String",
                    "FINER call=A hook-flow-remove Persist batch:String",
                    "FINER call=A hook-flow-put Persist odd:Odd=<toString threw java.lang.IllegalStateException>",
                    "FINE call=A end code=APPLIED state=REVIEW entity=Doc/DocStatus#1 records=1 starts=0 reactions=0"
                            + " aborted=0 automatic=0 elapsed=<us>"), lines(log));
        }
    }

    /** A call that does not log at TRACE builds no line for a hook's write, and never prints a visible value. */
    @Test
    void aHooksWriteBuildsNoLineWithoutTrace() {
        Doc doc = new Doc("1", DRAFT);
        Counted counted = new Counted();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.afterCommitWith("Persist",
                        (Doc committed, CommitContext<DocStatus, DocTransition> commit) -> {
                            commit.flow().put(COUNTED, counted);
                            commit.flow().remove(COUNTED);
                        }));
        try (MachineRegistry registry = registry(graph); LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(doc, SUBMIT));
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINE call=A committed Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW depth=0",
                    "FINE call=A end code=APPLIED state=REVIEW entity=Doc/DocStatus#1 records=1 starts=0 reactions=0"
                            + " aborted=0 automatic=0 elapsed=<us>"), lines(log));
        }
        assertEquals(0, counted.printed.get());
    }
}
