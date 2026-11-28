package io.github.graphmaquina.graph;

import java.util.List;
import java.util.Map;

/**
 * One graph of a characterization corpus: the graph, the node list it was declared from, a tag for every node
 * instance used to build it (keyed by identity, so the declared node and an equal instance an edge carries read
 * differently), the log that {@link LoggingNode} and {@link ImpostorNode} write to, and what that log held once
 * {@link Graph#of} returned.
 */
record GraphCase<N>(Graph<N> graph, List<N> declared, Map<Object, String> tags, StringBuilder log,
                    String construction) {
}
