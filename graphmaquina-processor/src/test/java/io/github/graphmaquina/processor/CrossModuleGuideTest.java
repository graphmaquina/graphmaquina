package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The guides quote the cross-module scenarios as they are: every Java block of the page on composing machines across
 * modules, and every Java block of the page on watches that declares or shows an owner lookup, appears verbatim in a
 * source of {@link CrossModuleSources}, which {@link CrossModuleCompositionTest} compiles and runs. A block may sit
 * deeper in the source than in the guide, by whole levels of four spaces, as in {@code GuideCopiesTest} of the
 * example. A blank block quotes nothing, so it fails.
 */
class CrossModuleGuideTest {

    private static final String DOCS = "graphmaquina.docs";
    private static final Pattern JAVA_BLOCK = Pattern.compile("```java\n(.*?)```", Pattern.DOTALL);

    @Test
    void everyJavaBlockIsAVerbatimCopyOfAScenarioSource() throws IOException {
        String page = "cross-module-composition.md";

        assertEquals(List.of(), missing(page, read(page), (String block) -> true, scenarios()));
    }

    /** The two blocks of the section on finding a watch's owner with a lookup: the lookup and the watch. */
    @Test
    void everyOwnerLookupBlockOfTheWatchGuideIsAVerbatimCopyOfAScenarioSource() throws IOException {
        String page = "coordination.md";
        Predicate<String> aboutOwnerLookup =
                (String block) -> block.contains("ownerLookup") || block.contains("@GraphLookup");

        assertEquals(2, blocks(read(page), aboutOwnerLookup).size());
        assertEquals(List.of(), missing(page, read(page), aboutOwnerLookup, scenarios()));
    }

    @Test
    void aBlankBlockQuotesNothing() {
        String page = "# Page\n\n```java\n```\n";

        assertEquals(List.of("page.md:3"), missing("page.md", page, (String block) -> true, List.of("class A { }")));
    }

    /**
     * Where {@code text}, the guide {@code page}, holds a Java block that {@code selected} accepts and no source of
     * {@code sources} holds, as {@code page:line} of its opening fence. A guide with no such block fails.
     */
    private static List<String> missing(String page, String text, Predicate<String> selected,
            Collection<String> sources) {
        List<Integer> starts = blocks(text, selected);
        assertFalse(starts.isEmpty(), () -> page + " holds no Java block to check");
        List<String> missing = new ArrayList<>();
        for (int start : starts) {
            Matcher block = JAVA_BLOCK.matcher(text);
            String code = block.find(start) ? block.group(1) : "";
            if (code.isBlank() || sources.stream().noneMatch((String source) -> holds(source, code))) {
                missing.add(page + ":" + text.substring(0, start).split("\n", -1).length);
            }
        }
        return missing;
    }

    /** The offset of each Java block of {@code text} whose code {@code selected} accepts. */
    private static List<Integer> blocks(String text, Predicate<String> selected) {
        List<Integer> starts = new ArrayList<>();
        Matcher block = JAVA_BLOCK.matcher(text);
        while (block.find()) {
            if (selected.test(block.group(1))) {
                starts.add(block.start());
            }
        }
        return starts;
    }

    /** Whether {@code source} holds {@code block}, indented by none, one, two or three levels of four spaces. */
    private static boolean holds(String source, String block) {
        for (int depth = 0; depth <= 12; depth += 4) {
            if (source.contains(indented(block, depth))) {
                return true;
            }
        }
        return false;
    }

    /** {@code block} with each of its lines that is not blank indented by {@code spaces} spaces. */
    private static String indented(String block, int spaces) {
        String indent = " ".repeat(spaces);
        StringBuilder text = new StringBuilder();
        for (String line : block.split("\n", -1)) {
            text.append(line.isBlank() ? line : indent + line).append('\n');
        }
        return text.substring(0, text.length() - 1);
    }

    /** Every source of both sides of the scenarios. */
    private static Collection<String> scenarios() {
        return FixtureSources.read(CrossModuleSources.root()).values();
    }

    private static String read(String page) throws IOException {
        Path docs = Path.of(Objects.requireNonNull(System.getProperty(DOCS), DOCS + " is not set"));
        return Files.readString(docs.resolve(page), StandardCharsets.UTF_8);
    }
}
