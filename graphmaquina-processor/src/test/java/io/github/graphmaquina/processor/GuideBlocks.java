package io.github.graphmaquina.processor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * The fenced code blocks of a guide under {@code docs/}, whose directory the build names in the system property
 * {@value #DOCS}. A fence is a line that starts with three backticks once its indentation is left out, so a block
 * indented in a list item is read too; a fence left open runs to the end of the guide.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class GuideBlocks {

    private static final String DOCS = "graphmaquina.docs";
    private static final String FENCE = "```";

    /**
     * One fenced block: its language, the first word of its opening fence's info string, empty for a bare fence; the
     * line of that fence, counted from 1; and its code, the lines between the two fences, joined by newlines.
     */
    record Block(String language, int line, String code) { }

    /** The text of the guide {@code page}, such as {@code coordination.md}. */
    static String read(String page) throws IOException {
        Path docs = Path.of(Objects.requireNonNull(System.getProperty(DOCS), DOCS + " is not set"));
        return Files.readString(docs.resolve(page), StandardCharsets.UTF_8);
    }

    /** Every fenced block of {@code text}, in order. */
    static List<Block> of(String text) {
        List<String> lines = List.of(text.split("\n", -1));
        List<Block> blocks = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            String line = lines.get(index).stripLeading();
            if (line.startsWith(FENCE)) {
                int close = closing(lines, index + 1);
                blocks.add(new Block(language(line), index + 1, String.join("\n", lines.subList(index + 1, close))));
                index = close + 1;
            } else {
                index++;
            }
        }
        return blocks;
    }

    /** The index of the first fence from {@code from} on, or the number of lines when there is none. */
    private static int closing(List<String> lines, int from) {
        for (int index = from; index < lines.size(); index++) {
            if (lines.get(index).stripLeading().startsWith(FENCE)) {
                return index;
            }
        }
        return lines.size();
    }

    /** The first word of the info string of {@code fence}. */
    private static String language(String fence) {
        return fence.substring(FENCE.length()).strip().split("\\s+", 2)[0];
    }
}
