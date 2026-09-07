package io.stategraph.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GraphEdgeTest {

    @Test
    void rejectsNullFrom() {
        NullPointerException error = assertThrows(NullPointerException.class, () -> new GraphEdge<String>(null, "B"));
        assertTrue(error.getMessage().startsWith("from"), error.getMessage());
    }

    @Test
    void rejectsNullTo() {
        NullPointerException error = assertThrows(NullPointerException.class, () -> new GraphEdge<String>("A", null));
        assertTrue(error.getMessage().startsWith("to"), error.getMessage());
    }

    @Test
    void equalsByValue() {
        assertEquals(new GraphEdge<>("A", "B"), new GraphEdge<>("A", "B"));
    }
}
