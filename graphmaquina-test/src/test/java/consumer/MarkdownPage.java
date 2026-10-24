package consumer;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The sections and fenced code blocks of a Markdown page, read the way the guides write them: a fence is skipped as a
 * whole, up to its closing line, so that what it holds is never taken for a heading or for the start of a block.
 */
final class MarkdownPage {

    /** A fenced block: its content, each line followed by a newline, and whether a closing line ended it. */
    record Block(String content, boolean closed) { }

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})(?: |$)");

    private MarkdownPage() { }

    /**
     * Returns the index of the line after the first line outside any fence that equals {@code line}, or -1 when no
     * line outside a fence does.
     */
    static int lineAfter(List<String> lines, String line) {
        int index = 0;
        while (index < lines.size()) {
            String current = lines.get(index++);
            if (current.equals(line)) {
                return index;
            }
            index = afterFence(lines, current, index);
        }
        return -1;
    }

    /**
     * Returns the blocks of {@code language} in the section that the first line outside any fence equal to
     * {@code heading} opens, in order. The section ends at the first following line outside a fence that
     * {@code endsSection} accepts. There are none when no line outside a fence equals the heading.
     */
    static List<Block> blocksAfter(List<String> lines, String heading, Predicate<String> endsSection,
            String language) {
        int start = lineAfter(lines, heading);
        return start < 0 ? List.of() : blocksFrom(lines, start, endsSection, language);
    }

    /**
     * Returns the blocks of {@code language} in the section that the first line outside any fence equal to the
     * heading line {@code heading} opens, in order. The section ends at the next heading outside a fence whose level
     * is the same as the heading's or higher, so a subsection belongs to it. There are none when no line outside a
     * fence equals the heading.
     */
    static List<Block> sectionBlocks(List<String> lines, String heading, String language) {
        int level = level(heading);
        return blocksAfter(lines, heading, (String line) -> level(line) > 0 && level(line) <= level, language);
    }

    /** Reads the page {@code name} of the directory {@code docs}, as lines. */
    static List<String> read(Path docs, String name) throws IOException {
        return Files.readAllLines(docs.resolve(name), UTF_8);
    }

    /** Returns the blocks of {@code language} anywhere on the page, in order. */
    static List<Block> allBlocks(List<String> lines, String language) {
        return blocksFrom(lines, 0, (String line) -> false, language);
    }

    /**
     * Returns the blocks of {@code language} that start at line {@code from} or later and before the first line
     * outside a fence that {@code endsSection} accepts, in order. A fence of another language is skipped up to its own
     * closing line, and a block that is never closed runs to the end of the page.
     */
    static List<Block> blocksFrom(List<String> lines, int from, Predicate<String> endsSection, String language) {
        List<Block> blocks = new ArrayList<>();
        int index = from;
        while (index < lines.size() && !endsSection.test(lines.get(index))) {
            Optional<MarkdownFence> fence = MarkdownFence.opening(lines.get(index++));
            if (fence.isPresent()) {
                int closing = fence.get().closingLine(lines, index);
                if (fence.get().language(language)) {
                    blocks.add(new Block(contentOf(lines.subList(index, closing)), closing < lines.size()));
                }
                index = closing + 1;
            }
        }
        return blocks;
    }

    /** Returns the index of the line after the fence that {@code opening} opens, or {@code index} if it opens none. */
    private static int afterFence(List<String> lines, String opening, int index) {
        return MarkdownFence.opening(opening)
                .map((MarkdownFence fence) -> fence.closingLine(lines, index) + 1)
                .orElse(index);
    }

    /** Returns the level of the ATX heading {@code line}, or 0 when the line is no heading. */
    private static int level(String line) {
        Matcher heading = HEADING.matcher(line);
        return heading.find() ? heading.group(1).length() : 0;
    }

    private static String contentOf(List<String> blockLines) {
        return blockLines.stream().map((String line) -> line + "\n").collect(Collectors.joining());
    }
}
