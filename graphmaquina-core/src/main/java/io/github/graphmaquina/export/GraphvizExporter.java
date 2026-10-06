package io.github.graphmaquina.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.NonNull;

/**
 * Renders a {@link GraphView} as Graphviz DOT text with deterministic ordering and LF line endings.
 * Export requires no Graphviz installation; use Graphviz separately to render DOT as an image.
 * State names are escaped labels on internal node IDs. The initial state has an incoming arrow from a point,
 * terminals use double circles, and watches use notes with dashed connections. Only declared edges are
 * drawn; a rejection policy is a tooltip on its node, never an arrow.
 */
public final class GraphvizExporter {

    private GraphvizExporter() { }

    /** Every declared edge, parallels included, in the view's order. */
    public static String render(@NonNull GraphView view) {
        Map<String, String> nodes = new LinkedHashMap<>();
        view.states().forEach((String state) -> addState(nodes, state));
        for (TransitionView transition : view.transitions()) {
            addState(nodes, transition.from());
            addState(nodes, transition.to());
        }
        addState(nodes, view.initial());
        view.terminals().stream().sorted().forEach((String state) -> addState(nodes, state));
        for (WatchView watch : view.watches()) {
            addState(nodes, watch.state());
        }
        Map<String, PolicyView> policies = new LinkedHashMap<>();
        for (PolicyView policy : view.policies()) {
            policies.putIfAbsent(policy.state(), policy);
        }

        StringBuilder out = new StringBuilder("digraph ").append(quote(view.name())).append(" {\n");
        out.append("    rankdir=LR;\n");
        out.append("    initial [shape=point, label=\"\"];\n");
        nodes.forEach((String state, String id) -> {
            out.append("    ").append(id).append(" [label=").append(quote(state)).append(", shape=")
                    .append(view.terminals().contains(state) ? "doublecircle" : "ellipse");
            PolicyView policy = policies.get(state);
            if (policy != null) {
                out.append(", tooltip=").append(quote(tooltip(policy)));
            }
            out.append("];\n");
        });
        out.append("    initial -> ").append(nodes.get(view.initial())).append(";\n");
        for (TransitionView transition : view.transitions()) {
            out.append("    ").append(nodes.get(transition.from())).append(" -> ").append(nodes.get(transition.to()))
                    .append(" [label=").append(quote(label(transition))).append("];\n");
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

    private static String tooltip(PolicyView policy) {
        List<String> parts = new ArrayList<>();
        parts.add("otherwise: " + policy.otherwise());
        parts.addAll(policy.rejections());
        return String.join("; ", parts);
    }

    /** The identifier always leads; the action, guards and routing follow it. */
    private static String label(TransitionView transition) {
        List<String> parts = new ArrayList<>();
        parts.add(transition.transition());
        transition.action().ifPresent(parts::add);
        if (transition.async()) {
            parts.add("[async]");
        }
        if (!transition.guards().isEmpty()) {
            parts.add("[if " + String.join(", ", transition.guards()) + "]");
        }
        transition.failureTransition().ifPresent((String id) -> parts.add("[onFailure " + id + "]"));
        transition.watch().ifPresent((String rule) -> parts.add("[watches " + rule + "]"));
        return String.join(" ", parts);
    }
}
