package io.github.graphmaquina.export;

import java.util.regex.Pattern;
import lombok.NonNull;

/**
 * The colors {@link GraphvizExporter} draws with, by role. Each color is a hex {@code #RRGGBB} or {@code #RRGGBBAA}
 * value, in either case; an alpha of {@code 00} makes a role transparent. Start from {@link #defaults()} and change
 * the roles you need with their {@code with} methods, each of which returns a copy with that one role changed:
 *
 * <pre>{@code
 * GraphvizTheme green = GraphvizTheme.defaults().withInk("#0b3d2e").withAutomatic("#087a62");
 * }</pre>
 *
 * <p>The defaults are the light palette of graphmaquina.dev. Fonts, sizes and spacing are not part of a theme.
 *
 * @param canvas the background
 * @param ink state names and outlines, the initial dot, the fill of terminal states, edge identifiers and titles
 * @param muted the arrows of edges that are not automatic, the text under an edge identifier, and the child
 *     machine in a sub-machine's title
 * @param stateFill the body of a state that is not terminal, and the names written on terminal states
 * @param automatic the arrow and identifier of an automatic edge
 * @param clusterFill the body of the region that holds a sub-machine
 * @param clusterBorder the outline of that region
 * @param watch the outline and text of a watch note, and its dashed line
 * @param watchFill the body of a watch note
 */
public record GraphvizTheme(@NonNull String canvas, @NonNull String ink, @NonNull String muted,
                            @NonNull String stateFill, @NonNull String automatic, @NonNull String clusterFill,
                            @NonNull String clusterBorder, @NonNull String watch, @NonNull String watchFill) {

    private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?");

    private static final GraphvizTheme DEFAULTS = new GraphvizTheme("#ffffff", "#1b1f3a", "#4f5677", "#ffffff",
            "#2457d6", "#f5f6fb", "#d4d8ea", "#a85a00", "#fff6e5");

    /**
     * Checks every role, in declaration order.
     *
     * @throws IllegalArgumentException naming the first role whose color is not {@code #RRGGBB} or {@code #RRGGBBAA}
     */
    public GraphvizTheme {
        requireColor("canvas", canvas);
        requireColor("ink", ink);
        requireColor("muted", muted);
        requireColor("stateFill", stateFill);
        requireColor("automatic", automatic);
        requireColor("clusterFill", clusterFill);
        requireColor("clusterBorder", clusterBorder);
        requireColor("watch", watch);
        requireColor("watchFill", watchFill);
    }

    /** The light palette of graphmaquina.dev: indigo ink on white, blue automatic edges, amber watches. */
    public static GraphvizTheme defaults() {
        return DEFAULTS;
    }

    public GraphvizTheme withCanvas(String canvas) {
        return new GraphvizTheme(canvas, ink, muted, stateFill, automatic, clusterFill, clusterBorder, watch,
                watchFill);
    }

    public GraphvizTheme withInk(String ink) {
        return new GraphvizTheme(canvas, ink, muted, stateFill, automatic, clusterFill, clusterBorder, watch,
                watchFill);
    }

    public GraphvizTheme withMuted(String muted) {
        return new GraphvizTheme(canvas, ink, muted, stateFill, automatic, clusterFill, clusterBorder, watch,
                watchFill);
    }

    public GraphvizTheme withStateFill(String stateFill) {
        return new GraphvizTheme(canvas, ink, muted, stateFill, automatic, clusterFill, clusterBorder, watch,
                watchFill);
    }

    public GraphvizTheme withAutomatic(String automatic) {
        return new GraphvizTheme(canvas, ink, muted, stateFill, automatic, clusterFill, clusterBorder, watch,
                watchFill);
    }

    public GraphvizTheme withClusterFill(String clusterFill) {
        return new GraphvizTheme(canvas, ink, muted, stateFill, automatic, clusterFill, clusterBorder, watch,
                watchFill);
    }

    public GraphvizTheme withClusterBorder(String clusterBorder) {
        return new GraphvizTheme(canvas, ink, muted, stateFill, automatic, clusterFill, clusterBorder, watch,
                watchFill);
    }

    public GraphvizTheme withWatch(String watch) {
        return new GraphvizTheme(canvas, ink, muted, stateFill, automatic, clusterFill, clusterBorder, watch,
                watchFill);
    }

    public GraphvizTheme withWatchFill(String watchFill) {
        return new GraphvizTheme(canvas, ink, muted, stateFill, automatic, clusterFill, clusterBorder, watch,
                watchFill);
    }

    private static void requireColor(String role, String color) {
        if (!COLOR.matcher(color).matches()) {
            throw new IllegalArgumentException(role + " must be a #RRGGBB or #RRGGBBAA color: \"" + color + "\"");
        }
    }
}
