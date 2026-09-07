package io.stategraph.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.stategraph.example.item.ItemGraph;
import io.stategraph.example.item.PackItem;
import io.stategraph.example.order.AllItemsPacked;
import io.stategraph.example.order.CancelOrder;
import io.stategraph.example.order.ChargeCard;
import io.stategraph.example.order.OrderGraph;
import io.stategraph.example.order.RetryBudgetLeft;
import io.stategraph.example.order.ShipOrder;
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
        String rendered = MermaidExporter.render(GraphView.of(OrderGraph.define(new ChargeCard(new FakeGateway()), new ShipOrder(),
                new CancelOrder(), new RetryBudgetLeft(), new AllItemsPacked())));
        assertEquals(golden("order.mmd"), rendered);
    }

    @Test
    void itemDiagramMatchesGolden() throws IOException {
        assertEquals(golden("item.mmd"), MermaidExporter.render(GraphView.of(ItemGraph.define(new PackItem()))));
    }
}
