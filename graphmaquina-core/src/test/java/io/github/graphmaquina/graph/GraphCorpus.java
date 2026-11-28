package io.github.graphmaquina.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Fixed corpora of graphs for {@link GraphCharacterizationTest}, drawn from fixed seeds. Node {@code id} of a graph
 * can be made more than once: as the canonical instance, as a repeat in the declared list (which may come first and
 * then become the declared one), and as a fresh equal instance at an edge endpoint, so the corpora exercise which
 * equal instance each answer holds.
 */
final class GraphCorpus {

    /** Seven constants: enough for every enum graph below, whose instances are always the constants themselves. */
    enum Colour { RED, GREEN, BLUE, CYAN, MAGENTA, YELLOW, BLACK }

    /** Makes an instance of node {@code id} that equals every other instance of that id. */
    @FunctionalInterface
    interface Nodes<N> {
        N make(int id, String tag, StringBuilder log);
    }

    /** A fresh string on every call, never the interned literal. */
    static final Nodes<String> STRINGS = (id, tag, log) -> new String("n" + id);

    /** Outside the cache of {@code Long.valueOf}, so every call boxes a fresh instance. */
    static final Nodes<Long> LONGS = (id, tag, log) -> Long.valueOf(1_000L + id);

    static final Nodes<Colour> COLOURS = (id, tag, log) -> Colour.values()[id];

    static final Nodes<LoggingNode> LOGGING = LoggingNode::new;

    private static final int[][] ORDERS = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};

    private GraphCorpus() { }

    /** Every graph over three string nodes, under each declaration order, with shared and with fresh endpoints. */
    static List<GraphCase<String>> threeNodeStrings() {
        List<GraphCase<String>> cases = new ArrayList<>();
        for (int mask = 0; mask < 512; mask++) {
            for (int[] order : ORDERS) {
                cases.add(threeNode(mask, order, false));
                cases.add(threeNode(mask, order, true));
            }
        }
        return cases;
    }

    private static GraphCase<String> threeNode(int mask, int[] order, boolean freshEndpoints) {
        Builder<String> builder = new Builder<>(STRINGS, 3);
        List<String> declared = new ArrayList<>();
        for (int id : order) {
            declared.add(builder.canonical(id));
        }
        List<GraphEdge<String>> edges = new ArrayList<>();
        for (int bit = 0; bit < 9; bit++) {
            if ((mask & (1 << bit)) != 0) {
                int from = bit / 3;
                int to = bit % 3;
                edges.add(freshEndpoints ? builder.freshEdge(from, to) : builder.canonicalEdge(from, to));
            }
        }
        return builder.build(declared, edges);
    }

    /** {@code count} graphs of one to {@code maxNodes} nodes with random edges, repeats and fresh endpoints. */
    static <N> List<GraphCase<N>> random(long seed, int count, int maxNodes, Nodes<N> nodes) {
        Random random = new Random(seed);
        List<GraphCase<N>> cases = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            cases.add(randomCase(random, 1 + random.nextInt(maxNodes), nodes));
        }
        return cases;
    }

    private static <N> GraphCase<N> randomCase(Random random, int size, Nodes<N> nodes) {
        Builder<N> builder = new Builder<>(nodes, size);
        List<N> declared = new ArrayList<>();
        for (int id = 0; id < size; id++) {
            declared.add(builder.canonical(id));
        }
        Collections.shuffle(declared, random);
        if (random.nextInt(4) == 0) {
            declared.add(random.nextInt(declared.size() + 1), builder.fresh(random.nextInt(size), "r"));
        }
        return builder.build(declared, randomEdges(random, size, builder));
    }

    private static <N> List<GraphEdge<N>> randomEdges(Random random, int size, Builder<N> builder) {
        List<GraphEdge<N>> edges = new ArrayList<>();
        int count = random.nextInt(size * size + 1);
        for (int e = 0; e < count; e++) {
            int from = random.nextInt(size);
            int to = random.nextInt(size);
            edges.add(random.nextBoolean() ? builder.canonicalEdge(from, to) : builder.freshEdge(from, to));
            if (random.nextInt(6) == 0) {
                edges.add(builder.freshEdge(from, to));
            }
        }
        return edges;
    }

    /** Rings, a chain, complete graphs with self-loops and two rings joined one way, declared in id order. */
    static <N> List<GraphCase<N>> shapes(Nodes<N> nodes, int large) {
        List<GraphCase<N>> cases = new ArrayList<>();
        for (int size : new int[] {1, 2, 3, large}) {
            cases.add(shape(nodes, size, (from, to) -> to == (from + 1) % size));
            cases.add(shape(nodes, size, (from, to) -> to == from + 1));
        }
        for (int size : new int[] {2, 4, 5}) {
            cases.add(shape(nodes, size, (from, to) -> true));
        }
        cases.add(shape(nodes, large, (from, to) -> joinedRings(from, to, large / 2, large)));
        return cases;
    }

    /** A ring over the ids below {@code half}, another over the rest, and one edge from the first to the second. */
    private static boolean joinedRings(int from, int to, int half, int size) {
        return from < half ? to == (from + 1) % half || from == 0 && to == half
                : to == half + (from - half + 1) % (size - half);
    }

    /** Which ordered pairs of node ids a shape joins. */
    @FunctionalInterface
    private interface Pairs {
        boolean joins(int from, int to);
    }

    private static <N> GraphCase<N> shape(Nodes<N> nodes, int size, Pairs pairs) {
        Builder<N> builder = new Builder<>(nodes, size);
        List<N> declared = new ArrayList<>();
        List<GraphEdge<N>> edges = new ArrayList<>();
        for (int from = 0; from < size; from++) {
            declared.add(builder.canonical(from));
            for (int to = 0; to < size; to++) {
                if (pairs.joins(from, to)) {
                    edges.add(builder.canonicalEdge(from, to));
                }
            }
        }
        return builder.build(declared, edges);
    }

    /** Graphs over strings in which some edge endpoints are {@link ImpostorNode}s equal to a string node. */
    static List<GraphCase<Object>> impostors(long seed, int count) {
        Random random = new Random(seed);
        Nodes<Object> nodes = (id, tag, log) -> random.nextInt(3) == 0 ? new ImpostorNode("n" + id, tag, log)
                : new String("n" + id);
        List<GraphCase<Object>> cases = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int size = 1 + random.nextInt(6);
            Builder<Object> builder = new Builder<>(nodes, size);
            List<Object> declared = new ArrayList<>();
            for (int id = 0; id < size; id++) {
                declared.add(new String("n" + id));
                builder.tags.put(declared.get(id), "n" + id + "#d");
                builder.canonical.set(id, declared.get(id));
            }
            cases.add(builder.build(declared, randomEdges(random, size, builder)));
        }
        return cases;
    }

    /** Makes and tags the instances of one graph and builds it. */
    private static final class Builder<N> {

        private final Nodes<N> nodes;
        private final StringBuilder log = new StringBuilder();
        private final Map<Object, String> tags = new IdentityHashMap<>();
        private final List<N> canonical = new ArrayList<>();

        private Builder(Nodes<N> nodes, int size) {
            this.nodes = nodes;
            for (int id = 0; id < size; id++) {
                canonical.add(fresh(id, "d"));
            }
        }

        private N canonical(int id) {
            return canonical.get(id);
        }

        private N fresh(int id, String kind) {
            String tag = "n" + id + "#" + kind + tags.size();
            N node = nodes.make(id, tag, log);
            tags.putIfAbsent(node, tag);
            return node;
        }

        private GraphEdge<N> canonicalEdge(int from, int to) {
            return new GraphEdge<>(canonical(from), canonical(to));
        }

        private GraphEdge<N> freshEdge(int from, int to) {
            return new GraphEdge<>(fresh(from, "e"), fresh(to, "e"));
        }

        private GraphCase<N> build(List<N> declared, List<GraphEdge<N>> edges) {
            Graph<N> graph = Graph.of(declared, edges);
            String construction = log.toString();
            log.setLength(0);
            return new GraphCase<>(graph, List.copyOf(declared), tags, log, construction);
        }
    }
}
