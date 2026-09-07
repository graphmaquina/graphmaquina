package io.stategraph.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GraphEdgeTest {

    @Test
    void rejectsNullFrom() {
        NullPointerException error = assertThrows(NullPointerException.class, () -> new GraphEdge<String>(null, "B"));
        assertEquals("from", error.getMessage());
    }

    @Test
    void rejectsNullTo() {
        NullPointerException error = assertThrows(NullPointerException.class, () -> new GraphEdge<String>("A", null));
        assertEquals("to", error.getMessage());
    }

    @Test
    void equalsByValue() {
        assertEquals(new GraphEdge<>("A", "B"), new GraphEdge<>("A", "B"));
    }
}
