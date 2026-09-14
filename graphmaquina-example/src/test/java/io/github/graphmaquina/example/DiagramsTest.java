package io.github.graphmaquina.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.example.item.ItemActions;
import io.github.graphmaquina.example.item.ItemsRuntime;
import io.github.graphmaquina.example.order.OrderActions;
import io.github.graphmaquina.example.order.OrdersRuntime;
import io.github.graphmaquina.export.GraphView;
import io.github.graphmaquina.export.GraphvizExporter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DiagramsTest {

    private static String golden(String name) throws IOException {
        try (InputStream in = DiagramsTest.class.getResourceAsStream("/golden/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void orderDiagramMatchesGolden() throws IOException {
        String rendered = GraphvizExporter.render(GraphView.of(OrdersRuntime.graph(new OrderActions(new FakeGateway(), new FakeCarrier()))));
        assertEquals(golden("order.dot"), rendered);
    }

    @Test
    void itemDiagramMatchesGolden() throws IOException {
        assertEquals(golden("item.dot"), GraphvizExporter.render(GraphView.of(ItemsRuntime.graph(new ItemActions()))));
    }
}
