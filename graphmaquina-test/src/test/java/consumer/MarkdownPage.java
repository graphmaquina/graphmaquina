package consumer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The sections and fenced code blocks of a Markdown page, read the way the guides write them: a fence is skipped as a
 * whole, up to its closing line, so that what it holds is never taken for a heading or for the start of a block.
 */
final class MarkdownPage {

    /** A fenced block: its content, each line followed by a newline, and whether a closing line ended it. */
    record Block(String content, boolean closed) { }

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

    private static String contentOf(List<String> blockLines) {
        return blockLines.stream().map((String line) -> line + "\n").collect(Collectors.joining());
    }
}
