package io.github.graphmaquina.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.NonNull;

/**
 * Renders a {@link GraphView} as Graphviz DOT text with deterministic ordering and LF line endings, drawn with the
 * colors of a {@link GraphvizTheme}; {@link #render(GraphView)} uses {@link GraphvizTheme#defaults()}. Export
 * requires no Graphviz installation; use Graphviz separately to render DOT as an image.
 *
 * <p>The text opens with three default statements. {@code graph} lays the drawing out left to right and titles it
 * with the view's name in bold at the top left. {@code node} draws states as rounded boxes, and {@code edge} draws
 * arrows. Together they set the font {@code Helvetica}, the sizes and spacing, and the common colors. Each node or edge then carries only what
 * sets it apart. State names are escaped labels on internal node IDs. A terminal state is filled with the theme's
 * {@code ink}, written in its {@code canvas} color and drawn with a double outline. The initial state has an
 * incoming arrow from a solid dot, and each watch is a note in the theme's watch colors joined to its state by a
 * dashed line. Only declared edges are drawn; a rejection policy is a tooltip on its node, never an arrow.
 *
 * <p>An edge's label is an HTML-like label: its identifier in bold on the first line, then one line per part, in
 * the theme's {@code muted} color: {@code [auto]} when the edge is automatic, or {@code [auto n]} when its state
 * declares an order among two or more automatic edges, with {@code n} counted from 1 in that order, then its
 * action, {@code [async]}, {@code [if <guards>]}, one {@code [recover <kind> -> <transition>]} per recovery rule in
 * declaration order, {@code [onFailure <transition>]} and {@code [watches <rules>]}. The parts the edge does not
 * have are left out. A route that continues the chain of automatic attempts ends its part with {@code continues},
 * as in {@code [recover <kind> -> <transition> continues]} and {@code [onFailure <transition> continues]}. A rule's
 * kind is its binary name without the package, such as {@code PublicationFailures$Unavailable}. The throwable types
 * of failsOn and of the rules are not drawn, and a rule adds no arrow and no node. An automatic edge's arrow and
 * identifier take the theme's {@code automatic} color. In an HTML-like label, {@code &}, {@code <}, {@code >} and
 * {@code "} are written as entities, a line break starts a new left-aligned line, and a control character that XML
 * forbids is drawn as U+FFFD. Every other text is a quoted string with backslashes, quotes and line breaks escaped.
 *
 * <p>A state that holds a sub-machine is drawn as a cluster instead of a node: a rounded region in the theme's
 * {@code clusterFill} and {@code clusterBorder}, titled with the state in bold followed by {@code : <child>} in the
 * {@code muted} color, after the child's view name, with the state's policy as its tooltip. The cluster holds an
 * invisible anchor and the child's own graph, its initial arrow, edges, watches and sub-machines included, whose IDs
 * carry the prefix {@code s<j>_}, where {@code j} counts the bound states of the view in node order, and one more
 * such prefix per level of nesting. An edge into the state points at the child's initial state, or, when that state
 * holds a sub-machine too, at that one's initial state, and so on down; an exit, an edge whose identifier the
 * binding maps a terminal state of the child to, is drawn once from each such terminal state; any other edge
 * leaving the state, and the state's watch note, start at the anchor. Arrows entering a cluster use {@code lhead};
 * edges leaving an anchor and its watch connections use {@code ltail}. An exit starts at the selected terminal
 * without clipping at its source cluster. Arrows whose ends lie in the same cluster omit clipping. The
 * {@code compound=true} attribute enables cluster clipping; a view without sub-machines has no cluster and no
 * {@code compound} attribute.
 */
public final class GraphvizExporter {

    /**
     * The font of every text: a PostScript name Graphviz knows, so that it sizes boxes with Helvetica's widths even
     * where it has no font library, and writes SVG text as {@code Helvetica,sans-Serif}.
     */
    private static final String FONT = "Helvetica";

    /** The end of a left-aligned line in an HTML-like label. */
    private static final String LINE = "<br align=\"left\"/>";

    private GraphvizExporter() { }

    /**
     * Every declared edge, parallels included, in the view's order, and every sub-machine as a cluster, drawn with
     * {@link GraphvizTheme#defaults()}.
     */
    public static String render(@NonNull GraphView view) {
        return render(view, GraphvizTheme.defaults());
    }

    /** Every declared edge, parallels included, in the view's order, and every sub-machine as a cluster. */
    public static String render(@NonNull GraphView view, @NonNull GraphvizTheme theme) {
        StringBuilder out = new StringBuilder("digraph ").append(quote(view.name())).append(" {\n");
        out.append("    graph [rankdir=LR, bgcolor=").append(quote(theme.canvas()))
                .append(", pad=\"0.3\", nodesep=\"0.45\", ranksep=\"0.7\", fontname=").append(FONT)
                .append(", fontsize=12, fontcolor=").append(quote(theme.ink()))
                .append(", labelloc=t, labeljust=l, label=<<b>").append(html(view.name())).append("</b>>];\n");
        out.append("    node [shape=box, style=\"rounded,filled\", color=").append(quote(theme.ink()))
                .append(", fillcolor=").append(quote(theme.stateFill())).append(", fontcolor=")
                .append(quote(theme.ink())).append(", fontname=").append(FONT)
                .append(", fontsize=12, penwidth=1.2, margin=\"0.18,0.06\"];\n");
        out.append("    edge [color=").append(quote(theme.muted())).append(", fontcolor=").append(quote(theme.ink()))
                .append(", fontname=").append(FONT).append(", fontsize=10, penwidth=1.1, arrowsize=0.7];\n");
        if (!view.submachines().isEmpty()) {
            out.append("    compound=true;\n");
        }
        Level.of(view, "", theme).render(out, "    ");
        return out.append("}\n").toString();
    }

    /**
     * One graph of the drawing, the view's own or a sub-machine's, whose IDs carry {@code prefix}, drawn with
     * {@code theme}: its states by internal ID in allocation order, the index of each bound state among the view's
     * sub-machines, and each state's policy.
     */
    private record Level(GraphView view, String prefix, GraphvizTheme theme, Map<String, String> nodes,
                         Map<String, Integer> bound, Map<String, PolicyView> policies) {

        static Level of(GraphView view, String prefix, GraphvizTheme theme) {
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
            Map<String, Integer> bound = new LinkedHashMap<>();
            for (int j = 0; j < view.submachines().size(); j++) {
                addState(nodes, view.submachines().get(j).state());
                bound.putIfAbsent(view.submachines().get(j).state(), j);
            }
            Map<String, PolicyView> policies = new LinkedHashMap<>();
            for (PolicyView policy : view.policies()) {
                policies.putIfAbsent(policy.state(), policy);
            }
            return new Level(view, prefix, theme, nodes, bound, policies);
        }

        private static void addState(Map<String, String> nodes, String state) {
            nodes.computeIfAbsent(state, (String ignored) -> "n" + nodes.size());
        }

        void render(StringBuilder out, String indent) {
            out.append(indent).append(prefix).append("initial [shape=point, width=0.14, height=0.14, fillcolor=")
                    .append(quote(theme.ink())).append(", label=\"\"];\n");
            nodes.forEach((String state, String id) -> {
                if (bound.containsKey(state)) {
                    return;
                }
                out.append(indent).append(prefix).append(id).append(" [label=").append(quote(state));
                if (view.terminals().contains(state)) {
                    out.append(", peripheries=2, fillcolor=").append(quote(theme.ink())).append(", fontcolor=")
                            .append(quote(theme.canvas()));
                }
                Optional<PolicyView> policy = policy(state);
                if (policy.isPresent()) {
                    out.append(", tooltip=").append(quote(tooltip(policy.get())));
                }
                out.append("];\n");
            });
            renderClusters(out, indent);
            renderEdges(out, indent);
            renderWatches(out, indent);
        }

        /**
         * Each sub-machine as a cluster: its title, its region's colors, its policy's tooltip, its anchor and the
         * child's own graph.
         */
        private void renderClusters(StringBuilder out, String indent) {
            for (int j = 0; j < view.submachines().size(); j++) {
                String inner = indent + "    ";
                SubmachineView submachine = view.submachines().get(j);
                out.append(indent).append("subgraph ").append(cluster(j)).append(" {\n");
                out.append(inner).append("label=<<b>").append(html(submachine.state())).append("</b><font color=")
                        .append(quote(theme.muted())).append(">: ").append(html(submachine.child().name()))
                        .append("</font>>;\n");
                out.append(inner).append("graph [style=\"rounded,filled\", color=")
                        .append(quote(theme.clusterBorder())).append(", fillcolor=").append(quote(theme.clusterFill()))
                        .append(", fontsize=11, penwidth=1.2];\n");
                Optional<PolicyView> policy = policy(submachine.state());
                if (policy.isPresent()) {
                    out.append(inner).append("tooltip=").append(quote(tooltip(policy.get()))).append(";\n");
                }
                out.append(inner).append(anchor(j)).append(" [shape=point, style=invis, label=\"\"];\n");
                child(j).render(out, inner);
                out.append(indent).append("}\n");
            }
        }

        /**
         * The initial arrow, then every declared edge in the view's order: an exit from each terminal state that
         * selects it, any other edge from its state or from the anchor of the cluster it leaves.
         */
        private void renderEdges(StringBuilder out, String indent) {
            arrow(out, indent, prefix + "initial", entry(view.initial()), Optional.empty(),
                    Optional.ofNullable(bound.get(view.initial())), List.of());
            for (TransitionView transition : view.transitions()) {
                List<String> attributes = attributes(transition);
                Optional<Integer> from = Optional.ofNullable(bound.get(transition.from()));
                Optional<Integer> to = Optional.ofNullable(bound.get(transition.to()));
                String head = entry(transition.to());
                List<String> terminals = from.isPresent()
                        ? terminalsSelecting(view.submachines().get(from.get()), transition.transition())
                        : List.of();
                if (!terminals.isEmpty()) {
                    Level child = child(from.get());
                    for (String terminal : terminals) {
                        arrow(out, indent, child.node(terminal), head, from, to, attributes);
                    }
                } else if (from.isPresent()) {
                    arrow(out, indent, anchor(from.get()), head, from, to, withTail(attributes, from.get(), to));
                } else {
                    arrow(out, indent, node(transition.from()), head, Optional.empty(), to, attributes);
                }
            }
        }

        /** Each watch as a note, with a dashed connection from its state, or from the anchor of a bound state. */
        private void renderWatches(StringBuilder out, String indent) {
            for (int i = 0; i < view.watches().size(); i++) {
                WatchView watch = view.watches().get(i);
                String note = prefix + "watch" + i;
                out.append(indent).append(note).append(" [shape=note, style=filled, color=")
                        .append(quote(theme.watch())).append(", fillcolor=").append(quote(theme.watchFill()))
                        .append(", fontcolor=").append(quote(theme.watch())).append(", label=")
                        .append(quote("watches " + watch.peerMachine())).append("];\n");
                Optional<Integer> at = Optional.ofNullable(bound.get(watch.state()));
                List<String> dashed = List.of("style=dashed", "arrowhead=none", "color=" + quote(theme.watch()));
                if (at.isPresent()) {
                    arrow(out, indent, anchor(at.get()), note, Optional.empty(), Optional.empty(),
                            withTail(dashed, at.get(), Optional.empty()));
                } else {
                    arrow(out, indent, node(watch.state()), note, Optional.empty(), Optional.empty(), dashed);
                }
            }
        }

        /** An edge's label, then the color of its arrow and identifier when it is automatic. */
        private List<String> attributes(TransitionView transition) {
            List<String> attributes = new ArrayList<>();
            attributes.add("label=" + label(transition, theme.muted()));
            if (transition.automatic()) {
                attributes.add("color=" + quote(theme.automatic()));
                attributes.add("fontcolor=" + quote(theme.automatic()));
            }
            return attributes;
        }

        private Optional<PolicyView> policy(String state) {
            return Optional.ofNullable(policies.get(state));
        }

        String node(String state) {
            return prefix + nodes.get(state);
        }

        private String cluster(int j) {
            return "cluster_" + prefix + "s" + j;
        }

        private String anchor(int j) {
            return prefix + "s" + j + "_anchor";
        }

        private Level child(int j) {
            return Level.of(view.submachines().get(j).child(), prefix + "s" + j + "_", theme);
        }

        /** The node an arrow into {@code state} points at: its own, or the entry of the sub-machine bound to it. */
        String entry(String state) {
            Optional<Integer> j = Optional.ofNullable(bound.get(state));
            if (j.isEmpty()) {
                return node(state);
            }
            Level child = child(j.get());
            return child.entry(child.view().initial());
        }

        /** {@code attributes}, then {@code ltail} for an arrow that leaves the cluster {@code from}. */
        private List<String> withTail(List<String> attributes, int from, Optional<Integer> to) {
            if (to.isPresent() && to.get() == from) {
                return attributes;
            }
            List<String> all = new ArrayList<>(attributes);
            all.add("ltail=" + cluster(from));
            return all;
        }

        /**
         * One arrow; {@code lhead} when its head lies in the cluster {@code to} and its tail, in the cluster
         * {@code from} or outside every cluster, does not.
         */
        private void arrow(StringBuilder out, String indent, String tail, String head, Optional<Integer> from,
                           Optional<Integer> to, List<String> attributes) {
            List<String> all = new ArrayList<>(attributes);
            if (to.isPresent() && !to.equals(from)) {
                all.add("lhead=" + cluster(to.get()));
            }
            out.append(indent).append(tail).append(" -> ").append(head);
            if (!all.isEmpty()) {
                out.append(" [").append(String.join(", ", all)).append("]");
            }
            out.append(";\n");
        }
    }

    /** The terminal states of the child that {@code submachine} maps to {@code transition}, in declaration order. */
    private static List<String> terminalsSelecting(SubmachineView submachine, String transition) {
        List<String> terminals = new ArrayList<>();
        submachine.exits().forEach((String terminal, String exit) -> {
            if (exit.equals(transition)) {
                terminals.add(terminal);
            }
        });
        return terminals;
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

    /**
     * An HTML-like label: the identifier in bold on the first line, then the automatic mark, the action, guards and
     * routing, every rule before the fallback, one per line in the {@code muted} color.
     */
    private static String label(TransitionView transition, String muted) {
        List<String> parts = new ArrayList<>();
        if (transition.automatic()) {
            parts.add(transition.automaticPosition().map((Integer n) -> "[auto " + n + "]").orElse("[auto]"));
        }
        transition.action().ifPresent(parts::add);
        if (transition.async()) {
            parts.add("[async]");
        }
        if (!transition.guards().isEmpty()) {
            parts.add("[if " + String.join(", ", transition.guards()) + "]");
        }
        for (RecoveryView recovery : transition.recoveries()) {
            parts.add("[recover " + withoutPackage(recovery.kind()) + " -> " + recovery.transition()
                    + continuation(recovery.continues()) + "]");
        }
        transition.failureTransition().ifPresent((String id) -> parts.add("[onFailure " + id
                + continuation(transition.failureContinues()) + "]"));
        transition.watch().ifPresent((String rule) -> parts.add("[watches " + rule + "]"));
        StringBuilder label = new StringBuilder("<<b>").append(html(transition.transition())).append("</b>");
        if (!parts.isEmpty()) {
            label.append(LINE).append("<font color=").append(quote(muted)).append(">");
            parts.forEach((String part) -> label.append(html(part)).append(LINE));
            label.append("</font>");
        }
        return label.append(">").toString();
    }

    /**
     * {@code text} inside an HTML-like label: the four XML specials as entities, each line break as the end of a
     * left-aligned line, and U+FFFD for a character XML forbids, any of which would make Graphviz drop the label.
     */
    private static String html(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.replace("\r\n", "\n").replace("\r", "\n").toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\n' -> out.append(LINE);
                default -> out.append((c < ' ' && c != '\t') || c == '\uFFFE' || c == '\uFFFF' ? '\uFFFD' : c);
            }
        }
        return out.toString();
    }

    /** The word that ends the fragment of a route that continues the chain of automatic attempts. */
    private static String continuation(boolean continues) {
        return continues ? " continues" : "";
    }

    /** A binary name without its package: the text after the last dot, nested names still joined by {@code $}. */
    private static String withoutPackage(String binaryName) {
        return binaryName.substring(binaryName.lastIndexOf('.') + 1);
    }
}
