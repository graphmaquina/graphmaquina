package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The guides show the cross-module scenarios as they are. Every Java block of the page on composing machines across
 * modules, and every Java block of the page on watches that declares or shows an owner lookup, is a verbatim copy of
 * whole lines of a source of {@link CrossModuleSources}, which {@link CrossModuleCompositionTest} compiles and runs: a
 * block may sit deeper in the source than in the guide, by whole levels of four spaces, as in {@code GuideCopiesTest}
 * of the example, and must start and end where lines of the source do. A blank block quotes nothing, so it fails, and
 * so does a fence whose language looks like Java but is not {@code java}, which the check would otherwise skip. Every
 * text block of the page is a message {@link CrossModuleCompositionTest} gets from the build or the runtime, in order.
 */
class CrossModuleGuideTest {

    private static final String PAGE = "cross-module-composition.md";
    private static final String JAVA = "java";
    private static final String TEXT = "text";

    /** A language that a reader would take for Java: {@code java} in any case, or followed by a character not a letter. */
    private static final Pattern JAVA_LIKE = Pattern.compile("java([^a-z].*)?");

    private static final Predicate<String> ANY = (String code) -> true;

    @Test
    void everyJavaBlockIsAVerbatimCopyOfAScenarioSource() throws IOException {
        List<GuideBlocks.Block> blocks = GuideBlocks.of(GuideBlocks.read(PAGE));
        List<GuideBlocks.Block> java = javaBlocks(blocks, ANY);

        assertFalse(java.isEmpty(), PAGE + " holds no Java block");
        assertEquals(List.of(), unknownJavaFences(PAGE, blocks));
        assertEquals(List.of(), missing(PAGE, java, scenarios()));
    }

    /** The two blocks of the section on finding a watch's owner with a lookup: the lookup and the watch. */
    @Test
    void everyOwnerLookupBlockOfTheWatchGuideIsAVerbatimCopyOfAScenarioSource() throws IOException {
        String page = "coordination.md";
        List<GuideBlocks.Block> blocks = GuideBlocks.of(GuideBlocks.read(page));
        List<GuideBlocks.Block> java = javaBlocks(blocks,
                (String code) -> code.contains("ownerLookup") || code.contains("@GraphLookup"));

        assertEquals(2, java.size());
        assertEquals(List.of(), unknownJavaFences(page, blocks));
        assertEquals(List.of(), missing(page, java, scenarios()));
    }

    @Test
    void everyMessageBlockIsOneTheCompositionTestsGet() throws IOException {
        List<String> shown = new ArrayList<>();
        for (GuideBlocks.Block block : GuideBlocks.of(GuideBlocks.read(PAGE))) {
            if (block.language().equals(TEXT)) {
                shown.add(block.code());
            }
        }

        assertEquals(CrossModuleCompositionTest.PAGE_MESSAGES.stream()
                .map((List<String> lines) -> String.join("\n", lines)).toList(), shown);
    }

    @Test
    void aBlankBlockQuotesNothing() {
        List<GuideBlocks.Block> java = javaBlocks(GuideBlocks.of("# Page\n\n```java\n```\n"), ANY);

        assertEquals(List.of("page.md:3"), missing("page.md", java, List.of("class A { }\n")));
    }

    /** A block that lost the first letter of its first line, or the last of its last, is no copy of whole lines. */
    @Test
    void aBlockQuotesWholeLinesOnly() {
        String page = "```java\nlass A { }\n```\n```java\nclass A {\n```\n```java\nclass A { }\n```\n";
        List<GuideBlocks.Block> java = javaBlocks(GuideBlocks.of(page), ANY);

        assertEquals(List.of("page.md:1", "page.md:4"), missing("page.md", java, List.of("class A { }\n")));
    }

    /** A Java fence may carry more information after its language; a fence that only looks like Java fails. */
    @Test
    void aJavaFenceMayCarryInformationAndALookAlikeFails() {
        List<GuideBlocks.Block> blocks = GuideBlocks.of(
                "```java title=\"A\"\nclass A { }\n```\n```Java\nclass A { }\n```\n```javascript\nlet a;\n```\n");

        assertEquals(List.of(), missing("page.md", javaBlocks(blocks, ANY), List.of("class A { }\n")));
        assertEquals(1, javaBlocks(blocks, ANY).size());
        assertEquals(List.of("page.md:4 Java"), unknownJavaFences("page.md", blocks));
    }

    /** The Java blocks of {@code blocks} whose code {@code selected} accepts. */
    private static List<GuideBlocks.Block> javaBlocks(List<GuideBlocks.Block> blocks, Predicate<String> selected) {
        return blocks.stream()
                .filter((GuideBlocks.Block block) -> block.language().equals(JAVA) && selected.test(block.code()))
                .toList();
    }

    /** Where {@code page} opens a block whose language looks like Java but is not {@code java}, with that language. */
    private static List<String> unknownJavaFences(String page, List<GuideBlocks.Block> blocks) {
        return blocks.stream()
                .filter((GuideBlocks.Block block) -> !block.language().equals(JAVA)
                        && JAVA_LIKE.matcher(block.language().toLowerCase(Locale.ROOT)).matches())
                .map((GuideBlocks.Block block) -> page + ":" + block.line() + " " + block.language())
                .toList();
    }

    /** Where {@code page} opens a block of {@code java} that no source of {@code sources} holds, as {@code page:line}. */
    private static List<String> missing(String page, List<GuideBlocks.Block> java, Collection<String> sources) {
        List<String> missing = new ArrayList<>();
        for (GuideBlocks.Block block : java) {
            if (block.code().isBlank() || sources.stream().noneMatch((String source) -> holds(source, block.code()))) {
                missing.add(page + ":" + block.line());
            }
        }
        return missing;
    }

    /**
     * Whether {@code source} holds {@code code} as whole lines, indented by none, one, two or three levels of four
     * spaces: at the start of the source or after a newline, and followed by one.
     */
    private static boolean holds(String source, String code) {
        for (int depth = 0; depth <= 12; depth += 4) {
            String lines = indented(code, depth) + "\n";
            if (source.startsWith(lines) || source.contains("\n" + lines)) {
                return true;
            }
        }
        return false;
    }

    /** {@code code} with each of its lines that is not blank indented by {@code spaces} spaces. */
    private static String indented(String code, int spaces) {
        String indent = " ".repeat(spaces);
        List<String> lines = new ArrayList<>();
        for (String line : code.split("\n", -1)) {
            lines.add(line.isBlank() ? line : indent + line);
        }
        return String.join("\n", lines);
    }

    /** Every source of both sides of the scenarios. */
    private static Collection<String> scenarios() {
        return FixtureSources.read(CrossModuleSources.root()).values();
    }
}
