package io.github.graphmaquina.example;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.example.item.Item;
import io.github.graphmaquina.example.item.ItemActions;
import io.github.graphmaquina.example.item.ItemStatus;
import io.github.graphmaquina.example.item.ItemsRuntime;
import io.github.graphmaquina.example.order.Order;
import io.github.graphmaquina.example.order.OrderActions;
import io.github.graphmaquina.example.order.OrderStatus;
import io.github.graphmaquina.example.order.OrdersRuntime;
import io.github.graphmaquina.test.GraphGoldens;
import io.github.graphmaquina.test.GraphSnapshots;
import org.junit.jupiter.api.Test;

/**
 * Both graphs of the example against their checked-in resources: the diagram a reader sees, and the
 * structure the diagram leaves out. Neither test reads a file itself — {@code graphmaquina-test} resolves
 * the resource, compares it and rewrites it when the build runs with {@code -Dgraphmaquina.goldens.update=true}.
 */
class DiagramsTest {

    private static GraphDefinition<Order, OrderStatus> orderGraph() {
        return OrdersRuntime.graph(new OrderActions(new FakeGateway(), new FakeCarrier()));
    }

    private static GraphDefinition<Item, ItemStatus> itemGraph() {
        return ItemsRuntime.graph(new ItemActions());
    }

    @Test
    void orderDiagramMatchesGolden() {
        GraphGoldens.assertMatches(orderGraph(), "golden/order.mmd");
    }

    @Test
    void itemDiagramMatchesGolden() {
        GraphGoldens.assertMatches(itemGraph(), "golden/item.mmd");
    }

    @Test
    void orderContractMatchesSnapshot() {
        GraphSnapshots.assertMatches(orderGraph(), "contracts/order.graph");
    }

    @Test
    void itemContractMatchesSnapshot() {
        GraphSnapshots.assertMatches(itemGraph(), "contracts/item.graph");
    }
}
