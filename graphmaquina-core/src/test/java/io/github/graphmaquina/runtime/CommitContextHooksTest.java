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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.Item;
import io.github.graphmaquina.fixtures.ItemStatus;
import io.github.graphmaquina.fixtures.ItemTransition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/**
 * What an {@code afterCommitWith} hook receives and when: the context (origin, identifier, destination, kind and
 * terminal flag) of a direct hop, a failure route, an asynchronous action and a cascaded hop, the committed state a
 * failing hook leaves, and the declaration rules for hook names and for the one hook an edge may carry.
 */
class CommitContextHooksTest {

    @Test
    void snapshotsTerminalDeclarationsMadeAfterTheHookAndBeforeBuild() {
        List<String> seen = new ArrayList<>();
        GraphBuilder<Doc, DocStatus, DocTransition> builder = StateGraph.define(DOCS).initial(DRAFT)
                .statePolicy(DRAFT, "a draft moves forward");
        builder.transition(SUBMIT, DRAFT, REVIEW)
                .afterCommitWith("review", (doc, context) -> seen.add(context.to() + ":" + context.isTerminal()))
                .endTransition()
                .transition(PUBLISH, DRAFT, PUBLISHED).endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition();
        builder.terminal(REVIEW).terminal(PUBLISHED).terminal(ARCHIVED);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = builder.build();
        machine(graph).execute(new Doc("1", DRAFT), SUBMIT);
        assertEquals(List.of("REVIEW:true"), seen);
        assertTrue(graph.terminals().contains(REVIEW));
    }

    @Test
    void failureRouteReceivesItsOwnContextAndIgnoredOrRejectedCallsRunNoHook() {
        List<String> seen = new ArrayList<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(graph((doc, context) ->
                seen.add(context.from() + ":" + context.transition() + ":" + context.to() + ":" + context.kind()
                        + ":" + context.isTerminal() + ":" + doc.status()), true));
        Doc doc = new Doc("1", REVIEW);
        assertInstanceOf(Outcome.Failed.class, machine.execute(doc, PUBLISH));
        assertEquals(List.of("REVIEW:SEND_BACK:ARCHIVED:MOVE:true:ARCHIVED"), seen);
        assertInstanceOf(Outcome.Ignored.class, machine.reevaluate(doc));
        assertInstanceOf(Outcome.Rejected.class, machine.execute(doc, SUBMIT));
        assertEquals(1, seen.size());
    }

    @Test
    void asyncActionDeliversItsContextAfterCommit() {
        List<String> seen = new ArrayList<>();
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(graph((doc, context) ->
                seen.add(context.from() + ":" + context.transition() + ":" + context.to() + ":" + context.kind()
                        + ":" + context.isTerminal() + ":" + doc.status()), false));
        assertInstanceOf(Outcome.Applied.class, machine.async().execute(new Doc("1", REVIEW), PUBLISH).join());
        assertEquals(List.of("REVIEW:PUBLISH:PUBLISHED:ACTION:false:PUBLISHED"), seen);
    }

    @Test
    void contextualHookFailureKeepsCommittedStateAndExceptionDetails() {
        IllegalStateException boom = new IllegalStateException("persistence unavailable");
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(graph((doc, context) -> { throw boom; }, false));
        Doc doc = new Doc("1", DRAFT);
        AfterCommitException error = assertThrows(AfterCommitException.class, () -> machine.execute(doc, SUBMIT));
        assertSame(boom, error.getCause());
        assertEquals("committed", error.step());
        assertEquals(DRAFT, error.from());
        assertEquals(REVIEW, error.to());
        assertEquals(REVIEW, doc.status());
    }

    @Test
    void legacyAndContextualHooksStillCountAsOneHookPerEdge() {
        assertThrows(IllegalStateException.class, () -> StateGraph.define(DOCS)
                .transition(SUBMIT, DRAFT, REVIEW).afterCommit("legacy", doc -> { })
                .afterCommitWith("contextual", (doc, context) -> { }));
        assertThrows(IllegalStateException.class, () -> StateGraph.define(DOCS)
                .transition(SUBMIT, DRAFT, REVIEW).afterCommitWith("contextual", (doc, context) -> { })
                .afterCommit("legacy", doc -> { }));
    }

    @Test
    void cascadedHopsKeepTheirOwnContextEvenWhenTheEntityAlreadyHasItsFinalState() {
        List<String> seen = new ArrayList<>();
        BiConsumer<Item, CommitContext<ItemStatus, ItemTransition>> hook = (item, context) -> seen.add(
                context.from() + ":" + context.transition() + ":" + context.to() + ":" + context.isTerminal()
                        + ":" + item.status());
        GraphDefinition<Item, ItemStatus, ItemTransition> items = StateGraph.define(ITEMS)
                .initial(ItemStatus.PENDING).terminal(ItemStatus.PACKED).terminal(ItemStatus.CANCELLED)
                .statePolicy(ItemStatus.PENDING, "an item starts packing first")
                .statePolicy(ItemStatus.PACKING, "cannot go back to pending")
                .transition(ItemTransition.START_PACKING, ItemStatus.PENDING, ItemStatus.PACKING)
                    .afterCommitWith("packing", hook).endTransition()
                .transition(ItemTransition.CANCEL, ItemStatus.PENDING, ItemStatus.CANCELLED).endTransition()
                .transition(ItemTransition.PACK, ItemStatus.PACKING, ItemStatus.PACKED)
                    .afterCommitWith("packed", hook).endTransition()
                .build();
        Item item = new Item("1", ItemStatus.PENDING, Optional.empty());
        GraphDefinition<Doc, DocStatus, DocTransition> docs = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted")
                .statePolicy(REVIEW, "in review it is published")
                .statePolicy(PUBLISHED, "published only archives")
                .transition(SUBMIT, DRAFT, REVIEW).action("pack", context -> {
                    context.cascade(ITEMS, item, ItemTransition.START_PACKING);
                    context.cascade(ITEMS, item, ItemTransition.PACK);
                    return ActionOutcome.ok();
                }).afterCommitWith("root", (doc, context) -> seen.add("root:" + context.isTerminal())).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(docs).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .add(StateMachines.from(items).state(Item::status, Item::setStatus).identity(Item::id)).build()) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT));
            assertEquals(List.of("root:false", "PENDING:START_PACKING:PACKING:false:PACKED",
                    "PACKING:PACK:PACKED:true:PACKED"), seen);
        }
    }

    @Test
    void actionEdgesReportTerminalDestinationsOnDirectHopsAndFailureRoutes() {
        List<String> seen = new ArrayList<>();
        BiConsumer<Doc, CommitContext<DocStatus, DocTransition>> hook = (doc, context) -> seen.add(
                context.from() + ":" + context.transition() + ":" + context.to() + ":" + context.kind()
                        + ":" + context.isTerminal());
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted")
                .statePolicy(REVIEW, "in review it is published or discarded")
                .statePolicy(PUBLISHED, "published only archives")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).action("publish", context -> ActionOutcome.failed("not ready"))
                    .onFailure(DISCARD).endTransition()
                .transition(DISCARD, REVIEW, ARCHIVED).action("close", context -> ActionOutcome.ok())
                    .afterCommitWith("closed", hook).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).action("archive", context -> ActionOutcome.ok())
                    .afterCommitWith("archived", hook).endTransition()
                .build();
        StateMachine<Doc, DocStatus, DocTransition> machine = machine(graph);
        assertInstanceOf(Outcome.Failed.class, machine.execute(new Doc("1", REVIEW), PUBLISH));
        assertInstanceOf(Outcome.Applied.class, machine.execute(new Doc("2", PUBLISHED), ARCHIVE));
        assertEquals(List.of("REVIEW:DISCARD:ARCHIVED:ACTION:true", "PUBLISHED:ARCHIVE:ARCHIVED:ACTION:true"), seen);
    }

    @Test
    void blankHookNamesFailWhenDeclaredRatherThanAtBuild() {
        assertThrows(IllegalArgumentException.class, () -> StateGraph.define(DOCS)
                .transition(SUBMIT, DRAFT, REVIEW).afterCommit(" ", doc -> { }));
        assertThrows(IllegalArgumentException.class, () -> StateGraph.define(DOCS)
                .transition(SUBMIT, DRAFT, REVIEW).afterCommitWith(" ", (doc, context) -> { }));
    }

    /**
     * A failure route discards every hop the failed action had cascaded: the peer is read back at its committed
     * state, the hooks of the discarded hops never run, and only the recovering edge reports a context.
     */
    @Test
    void aFailureRouteDiscardsTheCascadedHopsAndTheirHooks() {
        List<String> seen = new ArrayList<>();
        BiConsumer<Item, CommitContext<ItemStatus, ItemTransition>> itemHook =
                (item, context) -> seen.add("item:" + context.transition());
        GraphDefinition<Item, ItemStatus, ItemTransition> items = StateGraph.define(ITEMS)
                .initial(ItemStatus.PENDING).terminal(ItemStatus.PACKED).terminal(ItemStatus.CANCELLED)
                .statePolicy(ItemStatus.PENDING, "an item starts packing first")
                .statePolicy(ItemStatus.PACKING, "cannot go back to pending")
                .transition(ItemTransition.START_PACKING, ItemStatus.PENDING, ItemStatus.PACKING)
                    .afterCommitWith("packing", itemHook).endTransition()
                .transition(ItemTransition.CANCEL, ItemStatus.PENDING, ItemStatus.CANCELLED).endTransition()
                .transition(ItemTransition.PACK, ItemStatus.PACKING, ItemStatus.PACKED)
                    .afterCommitWith("packed", itemHook).endTransition()
                .build();
        Item item = new Item("1", ItemStatus.PENDING, Optional.empty());
        GraphDefinition<Doc, DocStatus, DocTransition> docs = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted")
                .statePolicy(REVIEW, "in review it is published")
                .statePolicy(PUBLISHED, "published only archives")
                .transition(SUBMIT, DRAFT, REVIEW).action("pack", context -> {
                    context.cascade(ITEMS, item, ItemTransition.START_PACKING);
                    context.cascade(ITEMS, item, ItemTransition.PACK);
                    return ActionOutcome.failed("nothing to pack");
                }).afterCommitWith("submitted", (doc, context) -> seen.add("submitted")).onFailure(DISCARD).endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).action("close", context -> {
                    seen.add("peer:" + context.peer(ITEMS).stateOf(item));
                    return ActionOutcome.ok();
                }).afterCommitWith("closed", (doc, context) -> seen.add("closed:" + context.from() + ":" + context.to()))
                    .endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(docs).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .add(StateMachines.from(items).state(Item::status, Item::setStatus).identity(Item::id)).build()) {
            Doc doc = new Doc("1", DRAFT);
            Outcome<Doc, DocStatus, DocTransition> outcome = registry.of(DOCS).execute(doc, SUBMIT);
            assertInstanceOf(Outcome.Failed.class, outcome);
            Outcome.Failed<Doc, DocStatus, DocTransition> failed =
                    (Outcome.Failed<Doc, DocStatus, DocTransition>) outcome;
            assertEquals(List.of("peer:PENDING", "closed:DRAFT:ARCHIVED"), seen);
            assertEquals(ItemStatus.PENDING, item.status());
            assertEquals(ARCHIVED, doc.status());
            assertEquals(List.of(DISCARD), failed.records().stream().map(record -> record.transition()).toList());
        }
    }

    private static GraphDefinition<Doc, DocStatus, DocTransition> graph(
            BiConsumer<Doc, CommitContext<DocStatus, DocTransition>> hook, boolean fail) {
        return StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted")
                .statePolicy(REVIEW, "in review it is published or sent back")
                .statePolicy(PUBLISHED, "published only archives")
                .transition(SUBMIT, DRAFT, REVIEW).afterCommitWith("committed", hook).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).asyncAction("publish", context ->
                        CompletableFuture.completedFuture(fail ? ActionOutcome.failed("not ready") : ActionOutcome.ok()))
                    .afterCommitWith("committed", hook).onFailure(SEND_BACK).endTransition()
                .transition(SEND_BACK, REVIEW, ARCHIVED).afterCommitWith("committed", hook).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).afterCommitWith("committed", hook).endTransition()
                .build();
    }

    private static StateMachine<Doc, DocStatus, DocTransition> machine(GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        return StateMachines.from(graph).state(Doc::status, Doc::setStatus).asyncTimeout(Duration.ofSeconds(5)).build();
    }
}
