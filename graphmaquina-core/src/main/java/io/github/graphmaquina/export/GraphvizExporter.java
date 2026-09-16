package io.github.graphmaquina.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.NonNull;

/**
 * Renders a {@link GraphView} as Graphviz DOT text with deterministic ordering and LF line endings.
 * Export requires no Graphviz installation; use Graphviz separately to render DOT as an image.
 * State names are escaped labels on internal node IDs. Initial states have an incoming point arrow,
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

        StringBuilder out = new StringBuilder("digraph ");
        appendQuoted(out, view.name());
        out.append(" {\n");
        out.append("    rankdir=LR;\n");
        out.append("    initial [shape=point, label=\"\"];\n");
        nodes.forEach((String state, String id) -> {
            out.append("    ").append(id).append(" [label=");
            appendQuoted(out, state);
            out.append(", shape=").append(view.terminals().contains(state) ? "doublecircle" : "ellipse");
            PolicyView policy = policies.get(state);
            if (policy != null) {
                out.append(", tooltip=");
                appendQuoted(out, tooltip(policy));
            }
            out.append("];\n");
        });
        out.append("    initial -> ").append(nodes.get(view.initial())).append(";\n");
        for (TransitionView transition : view.transitions()) {
            out.append("    ").append(nodes.get(transition.from())).append(" -> ").append(nodes.get(transition.to()))
                    .append(" [label=");
            appendQuoted(out, label(transition));
            out.append("];\n");
        }
        for (int i = 0; i < view.watches().size(); i++) {
            WatchView watch = view.watches().get(i);
            out.append("    watch").append(i).append(" [shape=note, label=");
            appendQuoted(out, "watches " + watch.peerMachine());
            out.append("];\n");
            out.append("    ").append(nodes.get(watch.state())).append(" -> watch").append(i)
                    .append(" [style=dashed, arrowhead=none];\n");
        }
        return out.append("}\n").toString();
    }

    private static void addState(Map<String, String> nodes, String state) {
        nodes.computeIfAbsent(state, ignored -> "n" + nodes.size());
    }

    // Escapes backslashes and quotes, and collapses CRLF into a single \n (CR and LF alone become \n too), in
    // one pass so user text cannot invoke Graphviz substitutions such as \N.
    private static void appendQuoted(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '"') out.append('\\').append(c);
            else if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                out.append("\\n");
            } else if (c == '\n') out.append("\\n");
            else out.append(c);
        }
        out.append('"');
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
