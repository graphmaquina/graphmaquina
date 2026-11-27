package io.github.graphmaquina.test.internal;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
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
    /** A line in both texts; also the index of its marker in {@link #MARKERS}. */
    private static final byte KEEP = 0;
    /** A line only in the expected text. */
    private static final byte DELETE = 1;
    /** A line only in the actual text. */
    private static final byte ADD = 2;
    private static final char[] MARKERS = {' ', '-', '+'};

    private LineDiff() { }

    public static String render(String expected, String actual) {
        Text left = Text.of(normalize(Objects.requireNonNull(expected, "expected")));
        Text right = Text.of(normalize(Objects.requireNonNull(actual, "actual")));
        return Script.of(left, right).render(left, right);
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * The lines of a text, split on line feeds. When a non-empty text does not end with a line feed, its last line is
     * the marker that says so, and that line differs from a real line that happens to read the same.
     */
    private record Text(String[] lines, boolean missingFeed) {

        static Text of(String text) {
            if (text.isEmpty()) {
                return new Text(new String[0], false);
            }
            boolean missingFeed = text.charAt(text.length() - 1) != '\n';
            int feeds = 0;
            for (int at = text.indexOf('\n'); at >= 0; at = text.indexOf('\n', at + 1)) {
                feeds++;
            }
            String[] lines = new String[missingFeed ? feeds + 2 : feeds];
            int begin = 0;
            for (int line = 0; line < feeds; line++) {
                int end = text.indexOf('\n', begin);
                lines[line] = text.substring(begin, end);
                begin = end + 1;
            }
            if (missingFeed) {
                lines[feeds] = text.substring(begin);
                lines[feeds + 1] = NO_NEWLINE;
            }
            return new Text(lines, missingFeed);
        }

        int size() {
            return lines.length;
        }

        boolean marker(int line) {
            return missingFeed && line == lines.length - 1;
        }

        boolean same(int line, Text other, int otherLine) {
            return marker(line) == other.marker(otherLine) && lines[line].equals(other.lines[otherLine]);
        }
    }

    /**
     * The edit script: for each step, its kind and the zero-based position each side stands at when it is emitted.
     * A step that keeps or deletes a line shows the expected text's line at its left position; one that adds shows
     * the actual text's line at its right position.
     */
    private static final class Script {

        private final byte[] kinds;
        private final int[] lefts;
        private final int[] rights;
        private int size;

        private Script(int capacity) {
            kinds = new byte[capacity];
            lefts = new int[capacity];
            rights = new int[capacity];
        }

        private void add(byte kind, int left, int right) {
            kinds[size] = kind;
            lefts[size] = left;
            rights[size] = right;
            size++;
        }

        static Script of(Text left, Text right) {
            int prefix = 0;
            while (prefix < left.size() && prefix < right.size() && left.same(prefix, right, prefix)) {
                prefix++;
            }
            int suffix = 0;
            while (suffix < left.size() - prefix && suffix < right.size() - prefix
                    && left.same(left.size() - 1 - suffix, right, right.size() - 1 - suffix)) {
                suffix++;
            }
            int leftTail = left.size() - suffix;
            int rightTail = right.size() - suffix;
            Script script = new Script(Math.min(CONTEXT, prefix) + leftTail - prefix + rightTail - prefix
                    + Math.min(CONTEXT, suffix));
            for (int index = Math.max(0, prefix - CONTEXT); index < prefix; index++) {
                script.add(KEEP, index, index);
            }
            new Middle(left, right, prefix, leftTail, rightTail).emit(script);
            for (int index = 0; index < Math.min(CONTEXT, suffix); index++) {
                script.add(KEEP, leftTail + index, rightTail + index);
            }
            return script;
        }

        /** The hunks, each as its first and last step, grouping changes that share context. */
        private int[] hunks() {
            int[] hunks = new int[2 * size + 2];
            int count = 0;
            int start = -1;
            int lastChange = -1;
            for (int index = 0; index < size; index++) {
                if (kinds[index] == KEEP) {
                    continue;
                }
                if (start < 0) {
                    start = Math.max(0, index - CONTEXT);
                } else if (index - lastChange > 2 * CONTEXT) {
                    hunks[count++] = start;
                    hunks[count++] = lastChange + CONTEXT;
                    start = index - CONTEXT;
                }
                lastChange = index;
            }
            if (start >= 0) {
                hunks[count++] = start;
                hunks[count++] = Math.min(size - 1, lastChange + CONTEXT);
            }
            return Arrays.copyOf(hunks, count);
        }

        /**
         * Each hunk as a header and its lines, the first two hundred of them, then how many more there were. Lines
         * past the cut are counted, not built.
         */
        String render(Text left, Text right) {
            int[] hunks = hunks();
            int total = 0;
            for (int hunk = 0; hunk < hunks.length; hunk += 2) {
                total += 2 + hunks[hunk + 1] - hunks[hunk];
            }
            StringBuilder text = new StringBuilder();
            int shown = 0;
            for (int hunk = 0; hunk < hunks.length && shown < MAX_LINES; hunk += 2) {
                shown = renderHunk(text, shown, left, right, hunks[hunk], hunks[hunk + 1]);
            }
            if (shown < total) {
                text.append("... ").append(total - shown)
                        .append(" more diff lines omitted; compare the golden resource named by this failure"
                                + " in full\n");
            }
            return text.toString();
        }

        /** Appends the header and the lines of one hunk while fewer than two hundred are shown; returns the count. */
        private int renderHunk(StringBuilder text, int alreadyShown, Text left, Text right, int start, int end) {
            int leftLines = 0;
            int rightLines = 0;
            for (int index = start; index <= end; index++) {
                leftLines += kinds[index] == ADD ? 0 : 1;
                rightLines += kinds[index] == DELETE ? 0 : 1;
            }
            text.append("@@ -").append(position(lefts[start], leftLines)).append(',').append(leftLines)
                    .append(" +").append(position(rights[start], rightLines)).append(',').append(rightLines)
                    .append(" @@\n");
            int shown = alreadyShown + 1;
            for (int index = start; index <= end && shown < MAX_LINES; index++) {
                String line = kinds[index] == ADD ? right.lines()[rights[index]] : left.lines()[lefts[index]];
                text.append(MARKERS[kinds[index]]).append(line).append('\n');
                shown++;
            }
            return shown;
        }

        private static int position(int index, int count) {
            return count == 0 ? index : index + 1;
        }
    }

    /**
     * The block between the common prefix and suffix: lines {@code base} to {@code leftEnd} of the expected text and
     * {@code base} to {@code rightEnd} of the actual one.
     */
    private record Middle(Text left, Text right, int base, int leftEnd, int rightEnd) {

        void emit(Script script) {
            int rows = leftEnd - base;
            int columns = rightEnd - base;
            if ((long) (rows + 1) * (columns + 1) > MAX_CELLS) {
                deleteThenAdd(script, 0, 0);
            } else {
                align(script, rows, columns);
            }
        }

        /**
         * Numbers each line of the block, the same number for the same line, fills the table whose cell
         * {@code [i][j]} is the length of the longest common subsequence of the two blocks from those lines on, then
         * walks it from its first cell, keeping the common lines and preferring a deletion over an addition on a tie,
         * and deletes and adds what is left. Within a million cells no length exceeds 999, so a cell fits in 16 bits.
         */
        private void align(Script script, int rows, int columns) {
            Map<String, Integer> numbers = HashMap.newHashMap(rows + columns);
            int[] leftNumbers = number(left, base, rows, numbers);
            int[] rightNumbers = number(right, base, columns, numbers);
            int width = columns + 1;
            short[] table = new short[(rows + 1) * width];
            for (int i = rows - 1; i >= 0; i--) {
                int row = i * width;
                int line = leftNumbers[i];
                short after = 0;
                for (int j = columns - 1; j >= 0; j--) {
                    after = line == rightNumbers[j] ? (short) (table[row + width + j + 1] + 1)
                            : (short) Math.max(table[row + width + j], after);
                    table[row + j] = after;
                }
            }
            int i = 0;
            int j = 0;
            while (i < rows && j < columns) {
                if (leftNumbers[i] == rightNumbers[j]) {
                    script.add(KEEP, base + i++, base + j++);
                } else if (table[(i + 1) * width + j] >= table[i * width + j + 1]) {
                    script.add(DELETE, base + i++, base + j);
                } else {
                    script.add(ADD, base + i, base + j++);
                }
            }
            deleteThenAdd(script, i, j);
        }

        /** The number of each of {@code count} lines from {@code base}: -1 for the missing-feed marker. */
        private static int[] number(Text text, int base, int count, Map<String, Integer> numbers) {
            int[] numbered = new int[count];
            for (int line = 0; line < count; line++) {
                if (text.marker(base + line)) {
                    numbered[line] = -1;
                } else {
                    Integer known = numbers.putIfAbsent(text.lines()[base + line], numbers.size());
                    numbered[line] = known == null ? numbers.size() - 1 : known;
                }
            }
            return numbered;
        }

        /**
         * Deletes the lines of the left block from {@code row} on and then adds those of the right block from
         * {@code column} on.
         */
        private void deleteThenAdd(Script script, int row, int column) {
            for (int i = base + row; i < leftEnd; i++) {
                script.add(DELETE, i, base + column);
            }
            for (int j = base + column; j < rightEnd; j++) {
                script.add(ADD, leftEnd, j);
            }
        }
    }
}
