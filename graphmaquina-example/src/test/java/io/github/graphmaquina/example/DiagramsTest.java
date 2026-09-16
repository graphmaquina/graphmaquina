package io.github.graphmaquina.example;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.example.item.Item;
import io.github.graphmaquina.example.item.ItemActions;
import io.github.graphmaquina.example.item.ItemStatus;
import io.github.graphmaquina.example.item.ItemTransition;
import io.github.graphmaquina.example.item.ItemsRuntime;
import io.github.graphmaquina.example.order.Order;
import io.github.graphmaquina.example.order.OrderActions;
import io.github.graphmaquina.example.order.OrderStatus;
import io.github.graphmaquina.example.order.OrderTransition;
import io.github.graphmaquina.example.order.OrdersRuntime;
import io.github.graphmaquina.test.GraphGoldens;
import org.junit.jupiter.api.Test;

/** Visual DOT output only; GraphContractsTest and OrderFlowTest verify the machines' contracts and behavior. */
class DiagramsTest {

    private static GraphDefinition<Order, OrderStatus, OrderTransition> orderGraph() {
        return OrdersRuntime.graph(new OrderActions(new FakeGateway(), new FakeCarrier()));
    }

    private static GraphDefinition<Item, ItemStatus, ItemTransition> itemGraph() {
        return ItemsRuntime.graph(new ItemActions());
    }

    @Test
    void orderDiagramMatchesGolden() {
        GraphGoldens.assertMatches(orderGraph(), "golden/order.dot");
    }

    @Test
    void itemDiagramMatchesGolden() {
        GraphGoldens.assertMatches(itemGraph(), "golden/item.dot");
    }

}
