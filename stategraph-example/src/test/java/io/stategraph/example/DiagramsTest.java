package io.stategraph.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.stategraph.example.item.ItemActions;
import io.stategraph.example.item.Items_;
import io.stategraph.example.order.OrderActions;
import io.stategraph.example.order.Orders_;
import io.stategraph.export.GraphView;
import io.stategraph.export.MermaidExporter;
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
        String rendered = MermaidExporter.render(GraphView.of(Orders_.graph(new OrderActions(new FakeGateway(), new FakeCarrier()))));
        assertEquals(golden("order.mmd"), rendered);
    }

    @Test
    void itemDiagramMatchesGolden() throws IOException {
        assertEquals(golden("item.mmd"), MermaidExporter.render(GraphView.of(Items_.graph(new ItemActions()))));
    }
}
