package io.github.graphmaquina.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.EdgeKind;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.Keys;
import io.github.graphmaquina.fixtures.Order;
import io.github.graphmaquina.fixtures.OrderGraphs;
import io.github.graphmaquina.fixtures.OrderStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class GraphvizExporterTest {

    private static String golden(String name) throws IOException {
        try (InputStream in = GraphvizExporterTest.class.getResourceAsStream("/golden/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static GraphDefinition<Order, OrderStatus> orderGraph() {
        return OrderGraphs.order(
                (TransitionContext<Order, OrderStatus> ctx) -> CompletableFuture.completedFuture(ActionOutcome.ok()),
                (TransitionContext<Order, OrderStatus> ctx) -> ActionOutcome.ok(),
                (TransitionContext<Order, OrderStatus> ctx) -> ActionOutcome.ok(),
                (GuardContext<Order, OrderStatus> ctx) -> GuardResult.pass(),
                (GuardContext<Order, OrderStatus> ctx) -> GuardResult.pass());
    }

    @Test
    void transitableViewMatchesGolden() throws IOException {
        assertEquals(golden("order.dot"), GraphvizExporter.render(GraphView.of(orderGraph())));
    }

    @Test
    void fullViewMatchesGolden() throws IOException {
        assertEquals(golden("order-full.dot"), GraphvizExporter.render(GraphView.of(orderGraph()), Detail.FULL));
    }

    @Test
    void viewCarriesNamesFailureRoutesAndWatches() {
        GraphView view = GraphView.of(orderGraph());
        assertEquals("Order/OrderStatus", view.name());
        assertEquals("NEW", view.initial());
        assertEquals(Set.of("SHIPPED", "CANCELLED"), view.terminals());
        EdgeView newToDeclined = view.edges().get(2);
        assertEquals("PAYMENT_DECLINED", newToDeclined.to());
        assertEquals(Optional.of("ChargeCard"), newToDeclined.failureOf());
        EdgeView paidToShipped = view.edges().get(8);
        assertEquals(Optional.of("Item: allIn PACKED"), paidToShipped.watchRule());
        assertEquals(List.of(new WatchView("PAID", "Item", List.of("allIn PACKED → SHIPPED"))), view.watches());
    }

    @Test
    void twoActionsSharingAFailureRouteAreBothNamed() {
        GraphDefinition<Doc, DocStatus> graph = StateGraph.define(Keys.DOCS)
                .initial(DocStatus.DRAFT).terminal(DocStatus.ARCHIVED)
                .from(DocStatus.DRAFT)
                    .to(DocStatus.REVIEW).action("A", (TransitionContext<Doc, DocStatus> ctx) -> ActionOutcome.ok())
                        .onFailure(DocStatus.ARCHIVED)
                    .to(DocStatus.PUBLISHED).action("B", (TransitionContext<Doc, DocStatus> ctx) -> ActionOutcome.ok())
                        .onFailure(DocStatus.ARCHIVED)
                    .to(DocStatus.ARCHIVED).move()
                    .stay()
                    .forbidOthers("a draft goes forward or is archived")
                .from(DocStatus.REVIEW).to(DocStatus.PUBLISHED).move().stay().forbidOthers("review only publishes")
                .from(DocStatus.PUBLISHED).to(DocStatus.ARCHIVED).move().stay().forbidOthers("published only archives")
                .from(DocStatus.ARCHIVED).stay().forbidOthers("archived is final")
                .build();
        String rendered = GraphvizExporter.render(GraphView.of(graph));
        assertTrue(rendered.contains("[label=\"[failure of A, B]\"]"), rendered);
        assertTrue(rendered.contains("[label=\"A\"]"), rendered);
        assertTrue(rendered.contains("[label=\"B\"]"), rendered);
    }

    @Test
    void terminalWithNoOutgoingEdgesIsDeclared() {
        GraphView view = new GraphView("X", "A", Set.of("B"), List.of(move("A", "B")), List.of());
        assertEquals("""
                digraph "X" {
                    rankdir=LR;
                    initial [shape=point, label=""];
                    n0 [label="A", shape=ellipse];
                    n1 [label="B", shape=doublecircle];
                    initial -> n0;
                    n0 -> n1;
                }
                """, GraphvizExporter.render(view));
    }

    @Test
    void nonFirstInitialStateDoesNotReorderTheMatrix() {
        GraphView view = new GraphView("X", "B", Set.of("B"), List.of(move("A", "B")), List.of());
        assertEquals("""
                digraph "X" {
                    rankdir=LR;
                    initial [shape=point, label=""];
                    n0 [label="A", shape=ellipse];
                    n1 [label="B", shape=doublecircle];
                    initial -> n1;
                    n0 -> n1;
                }
                """, GraphvizExporter.render(view));
    }

    @Test
    void fullIncludesStayAndForbidWhileDefaultOnlyIncludesMoves() {
        GraphView view = new GraphView("X", "A", Set.of("B"), List.of(
                move("A", "B"),
                new EdgeView("A", "A", EdgeKind.STAY, "", Optional.empty(), List.of(), false, Optional.empty(), Optional.empty()),
                new EdgeView("B", "A", EdgeKind.FORBID, "no way back", Optional.empty(), List.of(), false, Optional.empty(), Optional.empty())),
                List.of());
        String full = GraphvizExporter.render(view, Detail.FULL);
        assertTrue(full.contains("n0 -> n1;"), full);
        assertTrue(full.contains("n0 -> n0 [label=\"stay\"];"), full);
        assertTrue(full.contains("n1 -> n0 [label=\"forbid: no way back\"];"), full);
        String transitable = GraphvizExporter.render(view);
        assertFalse(transitable.contains("stay"), transitable);
        assertFalse(transitable.contains("forbid"), transitable);
        assertTrue(transitable.contains("n0 -> n1;"), transitable);
    }

    @Test
    void isolatedStatesAndUnorderedTerminalsAreAlwaysDeclaredDeterministically() {
        GraphView view = new GraphView("X", "initial", Set.of("Z", "B"), List.of(
                new EdgeView("island", "island", EdgeKind.STAY, "", Optional.empty(), List.of(), false, Optional.empty(), Optional.empty())),
                List.of(new WatchView("watch0", "Peer", List.of())));
        assertEquals("""
                digraph "X" {
                    rankdir=LR;
                    initial [shape=point, label=""];
                    n0 [label="island", shape=ellipse];
                    n1 [label="initial", shape=ellipse];
                    n2 [label="B", shape=doublecircle];
                    n3 [label="Z", shape=doublecircle];
                    n4 [label="watch0", shape=ellipse];
                    initial -> n1;
                    watch0 [shape=note, label="watches Peer"];
                    n4 -> watch0 [style=dashed, arrowhead=none];
                }
                """, GraphvizExporter.render(view));
    }

    @Test
    void initialCanAlsoBeTerminalWithoutAnyEdges() {
        String rendered = GraphvizExporter.render(new GraphView("X", "done", Set.of("done"), List.of(), List.of()));
        assertTrue(rendered.contains("n0 [label=\"done\", shape=doublecircle];"), rendered);
        assertTrue(rendered.contains("initial -> n0;"), rendered);
    }

    @Test
    void userTextIsEscapedAndNeverUsedAsNodeIds() {
        String special = "quote\" slash\\N\r\nnext\rline\n雪";
        String escaped = "quote\\\" slash\\\\N\\nnext\\nline\\n雪";
        GraphView view = new GraphView(special, special, Set.of("n0"), List.of(
                new EdgeView(special, "n0", EdgeKind.ACTION, "", Optional.of(special), List.of(special),
                        true, Optional.of(special), Optional.of(special)),
                new EdgeView("n0", special, EdgeKind.FORBID, special, Optional.empty(), List.of(),
                        false, Optional.empty(), Optional.empty())),
                List.of(new WatchView(special, special, List.of())));
        String rendered = GraphvizExporter.render(view, Detail.FULL);
        assertTrue(rendered.startsWith("digraph \"" + escaped + "\" {\n"), rendered);
        assertTrue(rendered.contains("n0 [label=\"" + escaped + "\", shape=ellipse];"), rendered);
        assertTrue(rendered.contains("n1 [label=\"n0\", shape=doublecircle];"), rendered);
        assertTrue(rendered.contains("n0 -> n1 [label=\"" + escaped + " [async] [if " + escaped
                + "] [failure of " + escaped + "] [watches " + escaped + "]\"];"), rendered);
        assertTrue(rendered.contains("n1 -> n0 [label=\"forbid: " + escaped + "\"];"), rendered);
        assertTrue(rendered.contains("watch0 [shape=note, label=\"watches " + escaped + "\"];"), rendered);
        assertFalse(rendered.contains("\r"), rendered);
    }

    @Test
    void multipleWatchesHaveSeparateNotes() {
        GraphView view = new GraphView("X", "A", Set.of(), List.of(), List.of(
                new WatchView("A", "Peer1", List.of()), new WatchView("A", "Peer2", List.of())));
        String rendered = GraphvizExporter.render(view);
        assertTrue(rendered.contains("watch0 [shape=note, label=\"watches Peer1\"];"), rendered);
        assertTrue(rendered.contains("watch1 [shape=note, label=\"watches Peer2\"];"), rendered);
        assertTrue(rendered.contains("n0 -> watch0 [style=dashed, arrowhead=none];"), rendered);
        assertTrue(rendered.contains("n0 -> watch1 [style=dashed, arrowhead=none];"), rendered);
    }

    private static EdgeView move(String from, String to) {
        return new EdgeView(from, to, EdgeKind.MOVE, "", Optional.empty(), List.of(), false, Optional.empty(), Optional.empty());
    }
}
