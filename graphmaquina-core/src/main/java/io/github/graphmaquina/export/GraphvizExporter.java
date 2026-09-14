package io.github.graphmaquina.export;

import io.github.graphmaquina.api.EdgeKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.NonNull;

/**
 * Renders a {@link GraphView} as Graphviz DOT text with deterministic ordering and LF line endings.
 * Export requires no Graphviz installation; use Graphviz separately to render DOT as an image.
 * State names are escaped labels on internal node IDs. Initial states have an incoming point arrow,
 * terminals use double circles, and watches use notes with dashed connections.
 */
public final class GraphvizExporter {

    private GraphvizExporter() { }

    /** Renders only the transitable edges: ACTION and MOVE. */
    public static String render(@NonNull GraphView view) {
        return render(view, Detail.TRANSITABLE);
    }

    /**
     * Renders {@code view} at the given level of {@link Detail}, retaining all known states.
     * States follow matrix endpoint order, then the initial state if absent, then remaining terminals
     * in name order and watch-only states in watch order. Edges and watches follow the view's lists.
     */
    public static String render(@NonNull GraphView view, @NonNull Detail detail) {
        Map<String, String> nodes = new LinkedHashMap<>();
        for (EdgeView edge : view.edges()) {
            addState(nodes, edge.from());
            addState(nodes, edge.to());
        }
        addState(nodes, view.initial());
        view.terminals().stream().sorted().forEach(state -> addState(nodes, state));
        for (WatchView watch : view.watches()) {
            addState(nodes, watch.state());
        }

        StringBuilder out = new StringBuilder("digraph ").append(quote(view.name())).append(" {\n");
        out.append("    rankdir=LR;\n");
        out.append("    initial [shape=point, label=\"\"];\n");
        nodes.forEach((state, id) -> out.append("    ").append(id)
                .append(" [label=").append(quote(state)).append(", shape=")
                .append(view.terminals().contains(state) ? "doublecircle" : "ellipse").append("];\n"));
        out.append("    initial -> ").append(nodes.get(view.initial())).append(";\n");
        for (EdgeView edge : view.edges()) {
            boolean transitable = edge.kind() == EdgeKind.ACTION || edge.kind() == EdgeKind.MOVE;
            if (!transitable && detail == Detail.TRANSITABLE) {
                continue;
            }
            out.append("    ").append(nodes.get(edge.from())).append(" -> ").append(nodes.get(edge.to()));
            String label = label(edge);
            if (!label.isEmpty()) {
                out.append(" [label=").append(quote(label)).append(']');
            }
            out.append(";\n");
        }
        for (int i = 0; i < view.watches().size(); i++) {
            WatchView watch = view.watches().get(i);
            out.append("    watch").append(i).append(" [shape=note, label=")
                    .append(quote("watches " + watch.peerMachine())).append("];\n");
            out.append("    ").append(nodes.get(watch.state())).append(" -> watch").append(i)
                    .append(" [style=dashed, arrowhead=none];\n");
        }
        return out.append("}\n").toString();
    }

    private static void addState(Map<String, String> nodes, String state) {
        nodes.computeIfAbsent(state, ignored -> "n" + nodes.size());
    }

    private static String quote(String text) {
        // Escape backslashes first so user text cannot invoke Graphviz substitutions such as \N.
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\n") + "\"";
    }

    private static String label(EdgeView edge) {
        if (edge.kind() == EdgeKind.STAY) {
            return "stay";
        }
        if (edge.kind() == EdgeKind.FORBID) {
            return "forbid: " + edge.reason();
        }
        List<String> parts = new ArrayList<>();
        edge.action().ifPresent(parts::add);
        if (edge.async()) {
            parts.add("[async]");
        }
        if (!edge.guards().isEmpty()) {
            parts.add("[if " + String.join(", ", edge.guards()) + "]");
        }
        edge.failureOf().ifPresent((String action) -> parts.add("[failure of " + action + "]"));
        edge.watchRule().ifPresent((String rule) -> parts.add("[watches " + rule + "]"));
        return String.join(" ", parts);
    }
}
