package io.github.graphmaquina.rust;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * {@code LineDiff} of {@code graphmaquina-test} with the alignment of its middle block answered by the Rust kernel.
 * Everything else is a copy of {@code io.github.graphmaquina.test.internal.LineDiff} at commit {@code e5521d52}:
 * line splitting, carriage-return normalization, the missing-final-feed marker, prefix and suffix trimming, the
 * million-cell fallback, hunk grouping and rendering. Only {@code middle} changed: the lines of the two blocks are
 * interned into numbers (equal lines, equal numbers) and {@link RustKernels#align} fills the same table and walks
 * it with the same tie-breaking, returning one kind per step that this class turns back into the same operations.
 */
public final class RustLineDiff {

    private static final int CONTEXT = 3;
    private static final int MAX_LINES = 200;
    private static final long MAX_CELLS = 1_000_000L;
    private static final String NO_NEWLINE = "\\ No newline at end of file";
    /** The kernel's step codes, indexed: 0 keeps a common line, 1 deletes and 2 adds. */
    private static final Kind[] KINDS = {Kind.CONTEXT, Kind.DELETE, Kind.ADD};

    private RustLineDiff() { }

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
        if ((long) (left.size() + 1) * (right.size() + 1) > MAX_CELLS) {
            deleteThenAdd(ops, left, right, base, 0, 0);
        } else {
            align(ops, left, right, base);
        }
    }

    /**
     * Interns both blocks, lets the kernel align them, and turns each step back into the operation
     * {@code LineDiff.align} and {@code LineDiff.deleteThenAdd} emit for it: a common line or a deletion takes the
     * next left line, an addition the next right line, each at the positions both sides stand at.
     */
    private static void align(List<Op> ops, List<Line> left, List<Line> right, int base) {
        Map<Line, Integer> numbers = HashMap.newHashMap(left.size() + right.size());
        int[] leftNumbers = intern(left, numbers);
        int[] rightNumbers = intern(right, numbers);
        byte[] kinds = new byte[left.size() + right.size()];
        int steps = RustKernels.align(leftNumbers, rightNumbers, kinds);
        int i = 0;
        int j = 0;
        for (int step = 0; step < steps; step++) {
            Kind kind = KINDS[kinds[step]];
            Line line = kind == Kind.ADD ? right.get(j) : left.get(i);
            ops.add(new Op(kind, line, base + i, base + j));
            i += kind == Kind.ADD ? 0 : 1;
            j += kind == Kind.DELETE ? 0 : 1;
        }
    }

    private static int[] intern(List<Line> lines, Map<Line, Integer> numbers) {
        int[] interned = new int[lines.size()];
        for (int index = 0; index < lines.size(); index++) {
            Integer known = numbers.putIfAbsent(lines.get(index), numbers.size());
            interned[index] = known == null ? numbers.size() - 1 : known;
        }
        return interned;
    }

    /**
     * Deletes the lines of the left block from {@code row} on and then adds those of the right block from
     * {@code column} on.
     */
    private static void deleteThenAdd(List<Op> ops, List<Line> left, List<Line> right, int base, int row, int column) {
        for (int i = row; i < left.size(); i++) {
            ops.add(new Op(Kind.DELETE, left.get(i), base + i, base + column));
        }
        for (int j = column; j < right.size(); j++) {
            ops.add(new Op(Kind.ADD, right.get(j), base + left.size(), base + j));
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
    @Getter(AccessLevel.PACKAGE)
    @RequiredArgsConstructor
    private enum Kind {
        CONTEXT(' '), DELETE('-'), ADD('+');

        private final char marker;
    }

    /**
     * One line of a text. {@code diagnostic} marks the line that stands for a missing final line feed, so a
     * real line that happens to read the same is a different line.
     */
    private record Line(String text, boolean diagnostic) { }

    /** One line of the edit script with the zero-based position each side stands at when it is emitted. */
    private record Op(Kind kind, Line line, int left, int right) { }
}
