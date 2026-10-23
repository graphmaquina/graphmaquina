package consumer;

import java.util.List;
import java.util.Optional;

/**
 * An opening fence of a CommonMark fenced code block, for lines without indentation, which is how the guides write
 * them. An opening line starts with at least three backticks or at least three tildes; the rest of the line,
 * without its leading and trailing spaces and tabs, is the info string, and a backtick fence's info string cannot
 * contain a backtick. A fence is closed by a line of the same character, at least as long as the opening run,
 * followed only by spaces or tabs. Anything between the two lines is content, whatever it looks like.
 */
final class MarkdownFence {

    private static final int MINIMUM_RUN = 3;

    private final char marker;
    private final int length;
    private final String info;

    private MarkdownFence(char marker, int length, String info) {
        this.marker = marker;
        this.length = length;
        this.info = info;
    }

    /** Returns the fence that the line opens, or empty when the line opens none. */
    static Optional<MarkdownFence> opening(String line) {
        if (line.isEmpty() || !isMarker(line.charAt(0))) {
            return Optional.empty();
        }
        char marker = line.charAt(0);
        int run = runLength(line, marker);
        String info = line.substring(run).replaceAll("^[ \t]+|[ \t]+$", "");
        if (run < MINIMUM_RUN || backtickInBacktickInfo(marker, info)) {
            return Optional.empty();
        }
        return Optional.of(new MarkdownFence(marker, run, info));
    }

    /** Whether the first word of the info string is exactly the given language name. */
    boolean language(String name) {
        return info.split("[ \t]+", 2)[0].equals(name);
    }

    /** Whether the line closes this fence. */
    boolean closes(String line) {
        int run = runLength(line, marker);
        return run >= length && line.substring(run).chars().allMatch((int c) -> c == ' ' || c == '\t');
    }

    /** Returns the index of the first line at or after {@code from} that closes this fence, or the line count. */
    int closingLine(List<String> lines, int from) {
        int index = from;
        while (index < lines.size() && !closes(lines.get(index))) {
            index++;
        }
        return index;
    }

    private static boolean isMarker(char character) {
        return character == '`' || character == '~';
    }

    /** A backtick fence's info string cannot contain a backtick. */
    private static boolean backtickInBacktickInfo(char marker, String info) {
        return marker == '`' && info.indexOf('`') >= 0;
    }

    private static int runLength(String line, char marker) {
        int run = 0;
        while (run < line.length() && line.charAt(run) == marker) {
            run++;
        }
        return run;
    }
}
