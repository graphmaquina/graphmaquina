package io.stategraph.export;

import io.stategraph.api.EdgeKind;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.NonNull;

/** Renders a {@link GraphView} as Mermaid {@code stateDiagram-v2} text, deterministically and with a fixed {@code \n}. */
public final class MermaidExporter {

    private MermaidExporter() { }

    /** Renders only the transitable edges: ACTION and MOVE. */
    public static String render(@NonNull GraphView view) {
        return render(view, Detail.TRANSITABLE);
    }

    /** Renders {@code view} at the given level of {@link Detail}. */
    public static String render(@NonNull GraphView view, @NonNull Detail detail) {
        StringBuilder out = new StringBuilder("stateDiagram-v2\n");
        out.append("    [*] --> ").append(view.initial()).append('\n');
        Set<String> statesInOrder = new LinkedHashSet<>();
        for (EdgeView edge : view.edges()) {
            statesInOrder.add(edge.from());
            boolean transitable = edge.kind() == EdgeKind.ACTION || edge.kind() == EdgeKind.MOVE;
            if (!transitable && detail == Detail.TRANSITABLE) {
                continue;
            }
            String label = label(edge);
            out.append("    ").append(edge.from()).append(" --> ").append(edge.to());
            if (!label.isEmpty()) {
                out.append(" : ").append(label);
            }
            out.append('\n');
        }
        for (String state : statesInOrder) {
            if (view.terminals().contains(state)) {
                out.append("    ").append(state).append(" --> [*]\n");
            }
        }
        for (String terminal : view.terminals()) {
            if (!statesInOrder.contains(terminal)) {
                out.append("    ").append(terminal).append(" --> [*]\n");
            }
        }
        for (WatchView watch : view.watches()) {
            out.append("    note right of ").append(watch.state()).append(" : watches ").append(watch.peerMachine()).append('\n');
        }
        return out.toString();
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
