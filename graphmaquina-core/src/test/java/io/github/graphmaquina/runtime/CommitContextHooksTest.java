package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
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
import io.github.graphmaquina.fixtures.Item;
import io.github.graphmaquina.fixtures.ItemStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

class CommitContextHooksTest {

    @Test
    void snapshotsTerminalDeclarationsMadeAfterTheHookAndBeforeBuild() {
        List<String> seen = new ArrayList<>();
        GraphBuilder<Doc, DocStatus> builder = StateGraph.define(DOCS).initial(DRAFT);
        builder.from(DRAFT).to(REVIEW).move().afterCommitWith("review", (doc, context) ->
                seen.add(context.to() + ":" + context.isTerminal()))
                .to(PUBLISHED).move().to(ARCHIVED).move().stay().forbidOthers("x")
                .from(REVIEW).stay().forbidOthers("x")
                .from(PUBLISHED).stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x");
        builder.terminal(REVIEW).terminal(PUBLISHED).terminal(ARCHIVED);
        GraphDefinition<Doc, DocStatus> graph = builder.build();
        machine(graph).transitionTo(new Doc("1", DRAFT), REVIEW);
        assertEquals(List.of("REVIEW:true"), seen);
        assertTrue(graph.terminals().contains(REVIEW));
    }

    @Test
    void failureRouteReceivesItsOwnContextAndIgnoredOrRejectedCallsRunNoHook() {
        List<String> seen = new ArrayList<>();
        StateMachine<Doc, DocStatus> machine = machine(graph((doc, context) -> seen.add(context.from() + ":" + context.to()
                + ":" + context.kind() + ":" + context.isTerminal() + ":" + doc.status()), true));
        Doc doc = new Doc("1", REVIEW);
        assertInstanceOf(Outcome.Failed.class, machine.transitionTo(doc, PUBLISHED));
        assertEquals(List.of("REVIEW:ARCHIVED:MOVE:true:ARCHIVED"), seen);
        assertInstanceOf(Outcome.Ignored.class, machine.transitionTo(doc, ARCHIVED));
        assertInstanceOf(Outcome.Rejected.class, machine.transitionTo(doc, DRAFT));
        assertEquals(1, seen.size());
    }

    @Test
    void asyncActionDeliversItsContextAfterCommit() {
        List<String> seen = new ArrayList<>();
        StateMachine<Doc, DocStatus> machine = machine(graph((doc, context) -> seen.add(context.from() + ":" + context.to()
                + ":" + context.kind() + ":" + context.isTerminal() + ":" + doc.status()), false));
        assertInstanceOf(Outcome.Applied.class, machine.async().transitionTo(new Doc("1", REVIEW), PUBLISHED).join());
        assertEquals(List.of("REVIEW:PUBLISHED:ACTION:false:PUBLISHED"), seen);
    }

    @Test
    void contextualHookFailureKeepsCommittedStateAndExceptionDetails() {
        IllegalStateException boom = new IllegalStateException("persistence unavailable");
        StateMachine<Doc, DocStatus> machine = machine(graph((doc, context) -> { throw boom; }, false));
        Doc doc = new Doc("1", DRAFT);
        AfterCommitException error = assertThrows(AfterCommitException.class, () -> machine.transitionTo(doc, REVIEW));
        assertSame(boom, error.getCause());
        assertEquals("committed", error.step());
        assertEquals(DRAFT, error.from());
        assertEquals(REVIEW, error.to());
        assertEquals(REVIEW, doc.status());
    }

    @Test
    void legacyAndContextualHooksStillCountAsOneHookPerCell() {
        assertThrows(IllegalStateException.class, () -> StateGraph.define(DOCS)
                .from(DRAFT).to(REVIEW).move().afterCommit("legacy", doc -> { })
                .afterCommitWith("contextual", (doc, context) -> { }));
        assertThrows(IllegalStateException.class, () -> StateGraph.define(DOCS)
                .from(DRAFT).to(REVIEW).move().afterCommitWith("contextual", (doc, context) -> { })
                .afterCommit("legacy", doc -> { }));
    }

    @Test
    void cascadedHopsKeepTheirOwnContextWhenTheEntityAlreadyHasItsFinalState() {
        List<String> seen = new ArrayList<>();
        BiConsumer<Item, CommitContext<ItemStatus>> hook = (item, context) -> seen.add(
                context.from() + ":" + context.to() + ":" + context.isTerminal() + ":" + item.status());
        GraphDefinition<Item, ItemStatus> items = StateGraph.define(ITEMS).initial(ItemStatus.PENDING)
                .terminal(ItemStatus.PACKED).terminal(ItemStatus.CANCELLED)
                .from(ItemStatus.PENDING).to(ItemStatus.PACKING).move().afterCommitWith("packing", hook)
                    .to(ItemStatus.CANCELLED).move().stay().forbidOthers("x")
                .from(ItemStatus.PACKING).to(ItemStatus.PACKED).move().afterCommitWith("packed", hook)
                    .stay().forbidOthers("x")
                .from(ItemStatus.PACKED).stay().forbidOthers("x")
                .from(ItemStatus.CANCELLED).stay().forbidOthers("x").build();
        Item item = new Item("1", ItemStatus.PENDING, Optional.empty());
        GraphDefinition<Doc, DocStatus> docs = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .from(DRAFT).to(REVIEW).action("pack", context -> {
                    context.cascade(ITEMS, item, ItemStatus.PACKING);
                    context.cascade(ITEMS, item, ItemStatus.PACKED);
                    context.cascade(ITEMS, item, ItemStatus.PACKED); // Convergent: no extra hook.
                    return ActionOutcome.ok();
                }).afterCommitWith("root", (doc, context) -> seen.add("root:" + context.isTerminal()))
                    .stay().forbidOthers("x")
                .from(REVIEW).to(PUBLISHED).move().stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x").build();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(docs).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .add(StateMachines.from(items).state(Item::status, Item::setStatus).identity(Item::id)).build()) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).transitionTo(new Doc("1", DRAFT), REVIEW));
            assertEquals(List.of("root:false", "PENDING:PACKING:false:PACKED", "PACKING:PACKED:true:PACKED"), seen);
        }
    }

    @Test
    void actionCellsReportTerminalDestinationsOnDirectHopsAndFailureRoutes() {
        List<String> seen = new ArrayList<>();
        BiConsumer<Doc, CommitContext<DocStatus>> hook = (doc, context) -> seen.add(
                context.from() + ":" + context.to() + ":" + context.kind() + ":" + context.isTerminal());
        GraphDefinition<Doc, DocStatus> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .from(DRAFT).to(REVIEW).move().stay().forbidOthers("x")
                .from(REVIEW).to(PUBLISHED).action("publish", context -> ActionOutcome.failed("not ready")).onFailure(ARCHIVED)
                    .to(ARCHIVED).action("close", context -> ActionOutcome.ok()).afterCommitWith("closed", hook).stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).action("archive", context -> ActionOutcome.ok()).afterCommitWith("archived", hook)
                    .stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x").build();
        StateMachine<Doc, DocStatus> machine = machine(graph);
        assertInstanceOf(Outcome.Failed.class, machine.transitionTo(new Doc("1", REVIEW), PUBLISHED));
        assertInstanceOf(Outcome.Applied.class, machine.transitionTo(new Doc("2", PUBLISHED), ARCHIVED));
        assertEquals(List.of("REVIEW:ARCHIVED:ACTION:true", "PUBLISHED:ARCHIVED:ACTION:true"), seen);
    }

    @Test
    void blankHookNamesFailWhenDeclaredRatherThanAtBuild() {
        assertThrows(IllegalArgumentException.class, () -> StateGraph.define(DOCS)
                .from(DRAFT).to(REVIEW).move().afterCommit(" ", doc -> { }));
        assertThrows(IllegalArgumentException.class, () -> StateGraph.define(DOCS)
                .from(DRAFT).to(REVIEW).move().afterCommitWith(" ", (doc, context) -> { }));
    }

    private static GraphDefinition<Doc, DocStatus> graph(BiConsumer<Doc, CommitContext<DocStatus>> hook, boolean fail) {
        return StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .from(DRAFT).to(REVIEW).move().afterCommitWith("committed", hook).stay().forbidOthers("x")
                .from(REVIEW).to(PUBLISHED).asyncAction("publish", context -> CompletableFuture.completedFuture(
                        fail ? ActionOutcome.failed("not ready") : ActionOutcome.ok()))
                    .afterCommitWith("committed", hook).onFailure(ARCHIVED)
                    .to(ARCHIVED).move().afterCommitWith("committed", hook).stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().afterCommitWith("committed", hook).stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x").build();
    }

    private static StateMachine<Doc, DocStatus> machine(GraphDefinition<Doc, DocStatus> graph) {
        return StateMachines.from(graph).state(Doc::status, Doc::setStatus).asyncTimeout(Duration.ofSeconds(5)).build();
    }
}
