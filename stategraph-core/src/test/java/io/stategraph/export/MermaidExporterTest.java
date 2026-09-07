package io.stategraph.export;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.stategraph.api.ActionOutcome;
import io.stategraph.api.EdgeKind;
import io.stategraph.api.GraphDefinition;
import io.stategraph.api.GuardContext;
import io.stategraph.api.GuardResult;
import io.stategraph.api.TransitionContext;
import io.stategraph.fixtures.Order;
import io.stategraph.fixtures.OrderGraphs;
import io.stategraph.fixtures.OrderStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class MermaidExporterTest {

    private static String golden(String name) throws IOException {
        try (InputStream in = MermaidExporterTest.class.getResourceAsStream("/golden/" + name)) {
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
        assertEquals(golden("order.mmd"), MermaidExporter.render(GraphView.of(orderGraph())));
    }

    @Test
    void fullViewMatchesGolden() throws IOException {
        assertEquals(golden("order-full.mmd"), MermaidExporter.render(GraphView.of(orderGraph()), Detail.FULL));
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
    void labelGrammarOmitsWhatDoesNotApply() {
        GraphView view = new GraphView("X", "A", Set.of("B"), List.of(
                new EdgeView("A", "B", EdgeKind.MOVE, "", Optional.empty(), List.of(), false, Optional.empty(), Optional.empty()),
                new EdgeView("A", "A", EdgeKind.STAY, "", Optional.empty(), List.of(), false, Optional.empty(), Optional.empty()),
                new EdgeView("B", "A", EdgeKind.FORBID, "no way back", Optional.empty(), List.of(), false, Optional.empty(), Optional.empty())),
                List.of());
        String expected = String.join("\n",
                "stateDiagram-v2",
                "    [*] --> A",
                "    A --> B",
                "    A --> A : stay",
                "    B --> A : forbid: no way back",
                "    B --> [*]",
                "");
        assertEquals(expected, MermaidExporter.render(view, Detail.FULL));
        assertEquals(String.join("\n", "stateDiagram-v2", "    [*] --> A", "    A --> B", "    B --> [*]", ""),
                MermaidExporter.render(view));
    }
}
