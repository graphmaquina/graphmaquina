package io.github.graphmaquina.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins, over fixed corpora, everything a caller can observe of {@link StrongComponents#find}, {@link Graph#cycles()},
 * {@link StrongComponents#cycleWitness}, {@link Graph#reachableFrom}, {@link Graph#shortestPath} and
 * {@link Graph#inducedSubgraph}: the answers and their order, the class of every returned container, which equal
 * instance stands in each place, refusals, and every {@code equals}/{@code hashCode} call made on nodes whose
 * equality is user code, including while {@link Graph#of} builds the graph (see {@link GraphObservations}).
 *
 * <p>Each corpus is pinned by the SHA-256 of its observations, taken on the code before the graph algorithms moved to
 * integer arrays (v2.0.9, e5521d52). A rewrite that changes any answer, any instance, any container class or any call
 * changes the digest.
 */
class GraphCharacterizationTest {

    @Test
    void everyThreeNodeStringGraphKeepsItsObservations() {
        assertEquals("227391fec16352640a58b020007c5ec3bda64680807ef63c8333d33c2669be61",
                GraphObservations.digest(GraphCorpus.threeNodeStrings()));
    }

    @Test
    void randomStringGraphsKeepTheirObservations() {
        assertEquals("86efb67fcdf0e64da8c22b5091e95beded9c2382dd6cb73fd73be44564160fc5",
                GraphObservations.digest(GraphCorpus.random(0x5EED_0001L, 3_000, 10, GraphCorpus.STRINGS)));
    }

    @Test
    void randomLongGraphsKeepTheirObservations() {
        assertEquals("b01c5e0afedf81dd598f053e45d0faea657f4ea60ba82df762fefe5ea9496cda",
                GraphObservations.digest(GraphCorpus.random(0x5EED_0002L, 1_000, 9, GraphCorpus.LONGS)));
    }

    @Test
    void randomEnumGraphsKeepTheirObservations() {
        assertEquals("9c0ed181f21f2d88c59190f987996e1785865500d037ccc7ef4425cede8ccdd5",
                GraphObservations.digest(GraphCorpus.random(0x5EED_0003L, 1_000, 7, GraphCorpus.COLOURS)));
    }

    @Test
    void stringShapesKeepTheirObservations() {
        assertEquals("1d1be76009d527ccb4b987e356843a6abebedecf3e1d81e166d0575f8a080263",
                GraphObservations.digest(GraphCorpus.shapes(GraphCorpus.STRINGS, 40)));
    }

    @Test
    void userNodesKeepEveryEqualsAndHashCodeCall() {
        assertEquals("287bf284624ae4ee8b139b1e4c2ff21702b5dc886d2d06e3e7daa4991dd8c298",
                GraphObservations.digest(GraphCorpus.random(0x5EED_0004L, 1_500, 8, GraphCorpus.LOGGING)));
    }

    @Test
    void userNodeShapesKeepEveryEqualsAndHashCodeCall() {
        assertEquals("6342f3366eca01e8c7e55010c03e9faffdc519de41bd4cc0a2353832064e1af7",
                GraphObservations.digest(GraphCorpus.shapes(GraphCorpus.LOGGING, 12)));
    }

    @Test
    void anImpostorEndpointInAStringGraphKeepsEveryCall() {
        assertEquals("d3c159325122cad6b0e93faa77e57a84fbb028b78095221e482ce027a82d2d83",
                GraphObservations.digest(GraphCorpus.impostors(0x5EED_0005L, 400)));
    }
}
