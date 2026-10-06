package io.github.graphmaquina.test.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A unified, line-oriented difference between two texts, meant to be read inside an assertion message:
 * only the lines that changed, each with three lines of context, instead of both documents in full.
 * Carriage returns are normalized to line feeds before comparing, and only that; every other character,
 * leading and trailing blanks included, is compared as it stands. A non-empty text that does not end with a
 * line feed carries an explicit marker line, so the presence of the final feed is itself part of the diff;
 * an empty text has no lines at all.
 *
 * <p>The middle block — what is left once the common prefix and suffix are removed — is aligned with a
 * longest common subsequence while its table stays within a million cells, and is otherwise reported as
 * one deletion block followed by one addition block, so the memory the alignment needs is bounded. The
 * rendered text is bounded too: past two hundred lines it stops and says how many it dropped.
 */
public final class LineDiff {

    private static final int CONTEXT = 3;
    private static final int MAX_LINES = 200;
    private static final long MAX_CELLS = 1_000_000L;
    private static final String NO_NEWLINE = "\\ No newline at end of file";

    private LineDiff() { }

    public static String render(String expected, String actual) {
        List<Line> left = lines(normalize(Objects.requireNonNull(expected, "expected")));
        List<Line> right = lines(normalize(Objects.requireNonNull(actual, "actual")));
        List<Op> ops = script(left, right);
        List<String> rendered = new ArrayList<>();
        for (int[] hunk : hunks(ops)) {
            render(rendered, ops, hunk[0], hunk[1]);
        }
        return join(rendered);
    }

    /** Splits on line feeds and marks a missing final feed, so two texts differing only in it still differ. */
    private static List<Line> lines(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> textLines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        boolean missingFinalFeed = !textLines.get(textLines.size() - 1).isEmpty();
        if (!missingFinalFeed) {
            textLines.remove(textLines.size() - 1);
        }
        List<Line> lines = new ArrayList<>(textLines.size() + (missingFinalFeed ? 1 : 0));
        for (String textLine : textLines) {
            lines.add(new Line(textLine, false));
        }
        if (missingFinalFeed) {
            lines.add(new Line(NO_NEWLINE, true));
        }
        return lines;
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static List<Op> script(List<Line> left, List<Line> right) {
        int prefix = 0;
        while (prefix < left.size() && prefix < right.size() && left.get(prefix).equals(right.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < left.size() - prefix && suffix < right.size() - prefix
                && left.get(left.size() - 1 - suffix).equals(right.get(right.size() - 1 - suffix))) {
            suffix++;
        }
        List<Op> ops = new ArrayList<>();
        for (int index = Math.max(0, prefix - CONTEXT); index < prefix; index++) {
            ops.add(new Op(Kind.CONTEXT, left.get(index), index, index));
        }
        middle(ops, left.subList(prefix, left.size() - suffix), right.subList(prefix, right.size() - suffix),
                prefix);
        int leftTail = left.size() - suffix;
        int rightTail = right.size() - suffix;
        for (int index = 0; index < Math.min(CONTEXT, suffix); index++) {
            ops.add(new Op(Kind.CONTEXT, left.get(leftTail + index), leftTail + index, rightTail + index));
        }
        return ops;
    }

    private static void middle(List<Op> ops, List<Line> left, List<Line> right, int base) {
        int rows = left.size();
        int columns = right.size();
        if ((long) (rows + 1) * (columns + 1) > MAX_CELLS) {
            for (int index = 0; index < rows; index++) {
                ops.add(new Op(Kind.DELETE, left.get(index), base + index, base));
            }
            for (int index = 0; index < columns; index++) {
                ops.add(new Op(Kind.ADD, right.get(index), base + rows, base + index));
            }
            return;
        }
        int[][] lcs = new int[rows + 1][columns + 1];
        for (int i = rows - 1; i >= 0; i--) {
            for (int j = columns - 1; j >= 0; j--) {
                lcs[i][j] = left.get(i).equals(right.get(j))
                        ? 1 + lcs[i + 1][j + 1]
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < rows && j < columns) {
            if (left.get(i).equals(right.get(j))) {
                ops.add(new Op(Kind.CONTEXT, left.get(i), base + i, base + j));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                ops.add(new Op(Kind.DELETE, left.get(i), base + i, base + j));
                i++;
            } else {
                ops.add(new Op(Kind.ADD, right.get(j), base + i, base + j));
                j++;
            }
        }
        while (i < rows) {
            ops.add(new Op(Kind.DELETE, left.get(i), base + i, base + j));
            i++;
        }
        while (j < columns) {
            ops.add(new Op(Kind.ADD, right.get(j), base + i, base + j));
            j++;
        }
    }

    /** Groups the changes into hunks of three context lines, merging two changes that share context. */
    private static List<int[]> hunks(List<Op> ops) {
        List<int[]> hunks = new ArrayList<>();
        int start = -1;
        int lastChange = -1;
        for (int index = 0; index < ops.size(); index++) {
            if (ops.get(index).kind() == Kind.CONTEXT) {
                continue;
            }
            if (start < 0) {
                start = Math.max(0, index - CONTEXT);
            } else if (index - lastChange > 2 * CONTEXT) {
                hunks.add(new int[] {start, lastChange + CONTEXT});
                start = index - CONTEXT;
            }
            lastChange = index;
        }
        if (start >= 0) {
            hunks.add(new int[] {start, Math.min(ops.size() - 1, lastChange + CONTEXT)});
        }
        return hunks;
    }

    private static void render(List<String> rendered, List<Op> ops, int start, int end) {
        int leftLines = 0;
        int rightLines = 0;
        for (int index = start; index <= end; index++) {
            Kind kind = ops.get(index).kind();
            leftLines += kind == Kind.ADD ? 0 : 1;
            rightLines += kind == Kind.DELETE ? 0 : 1;
        }
        Op first = ops.get(start);
        rendered.add("@@ -" + position(first.left(), leftLines) + "," + leftLines
                + " +" + position(first.right(), rightLines) + "," + rightLines + " @@");
        for (int index = start; index <= end; index++) {
            Op op = ops.get(index);
            rendered.add(op.kind().marker() + op.line().text());
        }
    }

    private static int position(int index, int count) {
        return count == 0 ? index : index + 1;
    }

    private static String join(List<String> rendered) {
        StringBuilder text = new StringBuilder();
        int shown = Math.min(rendered.size(), MAX_LINES);
        for (int index = 0; index < shown; index++) {
            text.append(rendered.get(index)).append('\n');
        }
        if (shown < rendered.size()) {
            text.append("... ").append(rendered.size() - shown)
                    .append(" more diff lines omitted; compare the golden resource named by this failure"
                            + " in full\n");
        }
        return text.toString();
    }

    /**
     * How a line of the edit script stands against the two texts: in both, only in the expected one or only in
     * the actual one, each rendered with its own marker character.
     */
    private enum Kind {
        CONTEXT(' '), DELETE('-'), ADD('+');

        private final char marker;

        Kind(char marker) {
            this.marker = marker;
        }

        char marker() {
            return marker;
        }
    }

    /**
     * One line of a text. {@code diagnostic} marks the line that stands for a missing final line feed, so a
     * real line that happens to read the same is a different line.
     */
    private record Line(String text, boolean diagnostic) { }

    /** One line of the edit script with the zero-based position each side stands at when it is emitted. */
    private record Op(Kind kind, Line line, int left, int right) { }
}
