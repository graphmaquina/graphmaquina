package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.EdgeSpec;
import java.util.ArrayList;
import java.util.List;

/** Renders the N×N matrix of a graph as aligned text. */
final class MatrixRenderer {

    private MatrixRenderer() { }

    /** Rows and columns by ordinal. ACTION shows the action's name; MOVE, STAY and FORBID show move, stay and -. */
    static <E, S extends Enum<S>> String render(S[] states, List<EdgeSpec<E, S>> cells) {
        int n = states.length;
        List<String> texts = new ArrayList<>();
        int width = 0;
        for (S state : states) {
            width = Math.max(width, state.name().length());
        }
        for (EdgeSpec<E, S> cell : cells) {
            String text = cellText(cell);
            texts.add(text);
            width = Math.max(width, text.length());
        }
        StringBuilder out = new StringBuilder();
        StringBuilder header = new StringBuilder(pad("", width)).append(" |");
        for (S state : states) {
            header.append(' ').append(pad(state.name(), width));
        }
        out.append(header.toString().stripTrailing()).append('\n');
        for (int i = 0; i < n; i++) {
            StringBuilder line = new StringBuilder(pad(states[i].name(), width)).append(" |");
            for (int j = 0; j < n; j++) {
                line.append(' ').append(pad(texts.get(i * n + j), width));
            }
            out.append(line.toString().stripTrailing()).append('\n');
        }
        return out.toString();
    }

    private static <E, S extends Enum<S>> String cellText(EdgeSpec<E, S> cell) {
        return switch (cell.kind()) {
            case ACTION -> cell.action().orElseThrow().name();
            case MOVE -> "move";
            case STAY -> "stay";
            case FORBID -> "-";
        };
    }

    private static String pad(String text, int width) {
        StringBuilder padded = new StringBuilder(text);
        while (padded.length() < width) {
            padded.append(' ');
        }
        return padded.toString();
    }
}
