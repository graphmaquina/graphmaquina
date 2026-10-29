package io.github.graphmaquina.example;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The guides quote the shop as it is: every Java block of the testing guide's pages that a link to one of the shop's
 * tests introduces appears verbatim in that test, and the first four Java blocks of the guide's page on the call's flow
 * are the shop's code, verbatim once the source's Javadoc comments are left out, each with the declaration it quotes.
 * The Java blocks of the composition page's section on sub-machines over the same entity, from its mention of
 * {@code SameEntitySubmachineTest} on, appear verbatim in that test. Every Java block of the eligibility page that a
 * link to a file of the example introduces appears verbatim in that file: the checks the shop's and the billing's
 * facades declare, and the tests that ask them. Every Java block of the automatic transitions page follows a link to a
 * source or a test of the onboarding and appears verbatim in that file, with or without the file's Javadoc comments. A
 * block may sit deeper in the source than in the guide, by whole levels of four spaces. A blank block quotes nothing,
 * so it fails.
 */
class GuideCopiesTest {

    /** A Java block of the guide's section on the call's flow: the shop's source it quotes, and its declaration. */
    private record Quote(String source, String declaration) { }

    /**
     * A Java block of a guide that a link introduces: what the link names, the line the block starts at, and its code.
     */
    private record TestQuote(String source, int start, String code) { }

    private static final List<String> TESTING_PAGES =
            List.of("testing.md", "testing-samples.md", "testing-advanced.md");
    private static final List<String> SHOP_TESTS =
            List.of("OrderFlowTest", "TestingApiTest", "DiagramsTest", "GraphContractsTest");
    private static final Pattern LINK =
            Pattern.compile("\\[(" + String.join("|", SHOP_TESTS) + ")\\]\\(");
    /** A link to a file of the example, which names its path below the module's {@code src} directory. */
    private static final String EXAMPLE_PACKAGE = "/java/io/github/graphmaquina/example/";
    private static final Pattern EXAMPLE_LINK = Pattern.compile(
            "\\]\\(\\.\\./graphmaquina-example/src/((?:main|test)" + EXAMPLE_PACKAGE + "[^)]+\\.java)\\)");
    /** The files the eligibility page quotes: the two facades that declare checks, and the two tests that ask them. */
    private static final Set<String> ELIGIBILITY_SOURCES = Set.of(
            "main/java/io/github/graphmaquina/example/order/Orders.java",
            "main/java/io/github/graphmaquina/example/billing/Payments.java",
            "test/java/io/github/graphmaquina/example/EligibilityTest.java",
            "test/java/io/github/graphmaquina/example/billing/BookingFlowTest.java");
    /**
     * The Java blocks of the eligibility page that a link to a file of the example introduces: eight of its twelve, the
     * other four being those that GuideOutcomeFragmentsTest, in graphmaquina-test, compiles.
     */
    private static final int ELIGIBILITY_QUOTES = 8;
    /** A link to a source or a test of the onboarding, which the automatic transitions page quotes. */
    private static final Pattern ONBOARDING_LINK = Pattern.compile(
            "\\]\\(\\.\\./graphmaquina-example/src/((?:main|test)" + EXAMPLE_PACKAGE + "onboarding/[^)]+\\.java)\\)");
    private static final String AUTOMATIC_PAGE = "automatic-transitions.md";
    private static final Pattern JAVADOC = Pattern.compile("\n[ \t]*/\\*\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern JAVA_BLOCK = Pattern.compile("```java\n(.*?)```", Pattern.DOTALL);

    private static Path path(String property, String relative) {
        return Path.of(System.getProperty(property)).resolve(relative);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, UTF_8);
    }

    /** The example's file that {@link #EXAMPLE_LINK} names: a source of {@code main}, or a test of {@code test}. */
    private static Path exampleFile(String link) {
        String root = link.startsWith("main/") ? "graphmaquina.example.sources" : "graphmaquina.example.tests";
        return path(root, link.substring(link.indexOf(EXAMPLE_PACKAGE) + EXAMPLE_PACKAGE.length()));
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

    /** Whether {@code source} holds {@code block} at one of the given depths. */
    private static boolean holds(String source, String block, int... depths) {
        for (int depth : depths) {
            if (source.contains(indented(block, depth))) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theTestingGuideQuotesTheShopsTestsVerbatim() throws IOException {
        List<String> missing = new ArrayList<>();
        int quoted = 0;
        for (String page : TESTING_PAGES) {
            List<TestQuote> quotes = new TestQuoteReader(LINK).read(read(path("graphmaquina.docs", page)));
            quoted += quotes.size();
            for (TestQuote quote : quotes) {
                String source = read(path("graphmaquina.example.tests", quote.source() + ".java"));
                if (quote.code().isBlank() || !holds(source, quote.code(), 0, 4, 8, 12)) {
                    missing.add("docs/" + page + ":" + quote.start() + " " + quote.source());
                }
            }
        }
        assertTrue(quoted > 0, "no block of the testing guide quotes a test of the shop");
        assertEquals(List.of(), missing);
    }

    /**
     * The eligibility page quotes the checks of {@code Orders} and {@code Payments} and the tests that ask them, each
     * block after a link to the file it comes from, and it quotes every one of those four files, in eight blocks.
     */
    @Test
    void theEligibilityPageQuotesTheExampleVerbatim() throws IOException {
        List<TestQuote> quotes = new TestQuoteReader(EXAMPLE_LINK).read(read(path("graphmaquina.docs",
                "eligibility.md")));
        List<String> missing = new ArrayList<>();
        for (TestQuote quote : quotes) {
            if (quote.code().isBlank() || !holds(read(exampleFile(quote.source())), quote.code(), 0, 4, 8, 12)) {
                missing.add("docs/eligibility.md:" + quote.start() + " " + quote.source());
            }
        }
        assertEquals(ELIGIBILITY_QUOTES, quotes.size());
        assertEquals(ELIGIBILITY_SOURCES, quotes.stream().map(TestQuote::source).collect(Collectors.toSet()));
        assertEquals(List.of(), missing);
    }

    /**
     * Whether {@code source} holds {@code block} at one of the depths a guide may drop, as it is or once its Javadoc
     * comments are left out.
     */
    private static boolean quotes(String source, String block) {
        return holds(source, block, 0, 4, 8, 12) || holds(JAVADOC.matcher(source).replaceAll(""), block, 0, 4, 8, 12);
    }

    /**
     * Every Java block of the automatic transitions page follows a link to a source or a test of the onboarding, so
     * the page quotes as many files as it has Java blocks, and each block appears verbatim in the file it follows.
     */
    @Test
    void theAutomaticTransitionsPageQuotesTheOnboardingVerbatim() throws IOException {
        String page = read(path("graphmaquina.docs", AUTOMATIC_PAGE));
        List<TestQuote> quotes = new TestQuoteReader(ONBOARDING_LINK).read(page);
        List<String> missing = new ArrayList<>();
        for (TestQuote quote : quotes) {
            if (quote.code().isBlank() || !quotes(read(exampleFile(quote.source())), quote.code())) {
                missing.add("docs/" + AUTOMATIC_PAGE + ":" + quote.start() + " " + quote.source());
            }
        }
        assertFalse(quotes.isEmpty(), "no Java block of the page follows a link to the onboarding");
        assertEquals(JAVA_BLOCK.matcher(page).results().count(), (long) quotes.size(),
                "a Java block of the page follows no link to the onboarding");
        assertEquals(List.of(), missing);
    }

    @Test
    void theGuideQuotesTheShopsFlowCodeVerbatim() throws IOException {
        Matcher blocks = JAVA_BLOCK.matcher(read(path("graphmaquina.docs", "call-flow.md")));
        List<Quote> quotes = List.of(new Quote("ShopFlow.java", "public final class ShopFlow {"),
                new Quote("order/OrderActions.java", "public ActionOutcome cancel("),
                new Quote("item/ItemActions.java", "public ActionOutcome restock("),
                new Quote("item/ItemActions.java", "public void applyRestock("));
        List<String> missing = new ArrayList<>();
        for (Quote quote : quotes) {
            assertTrue(blocks.find(), "the page holds fewer than four Java blocks");
            String code = JAVADOC.matcher(read(path("graphmaquina.example.sources", quote.source()))).replaceAll("");
            String block = blocks.group(1);
            if (!block.contains(quote.declaration()) || !holds(code, block, 0, 4)) {
                missing.add(quote.source() + " " + quote.declaration());
            }
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void theGuideQuotesTheSameEntitySubmachineTestVerbatim() throws IOException {
        String guide = read(path("graphmaquina.docs", "composition.md"));
        int mention = guide.indexOf("`SameEntitySubmachineTest`");
        assertTrue(mention >= 0, "the composition page no longer names SameEntitySubmachineTest");
        int sectionEnd = guide.indexOf("\n## ", mention);
        Matcher blocks = JAVA_BLOCK.matcher(guide).region(mention, sectionEnd < 0 ? guide.length() : sectionEnd);
        String source = read(path("graphmaquina.example.tests", "billing/SameEntitySubmachineTest.java"));
        List<String> quoted = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        while (blocks.find()) {
            String where = "docs/composition.md:" + guide.substring(0, blocks.start()).split("\n", -1).length;
            quoted.add(where);
            if (blocks.group(1).isBlank() || !holds(source, blocks.group(1), 0, 4, 8, 12)) {
                missing.add(where + " SameEntitySubmachineTest");
            }
        }
        assertFalse(quoted.isEmpty(), "no Java block follows the guide's mention of SameEntitySubmachineTest");
        assertEquals(List.of(), missing);
    }

    /**
     * Reads a guide line by line. The first link of a line outside a code block that {@code link} matches names, as
     * its first group, the file that the Java blocks after it quote, up to the next link or heading; a heading is any
     * line that starts with {@code #}, even inside a code block.
     */
    private static final class TestQuoteReader {

        private final Pattern link;
        private final List<TestQuote> quotes = new ArrayList<>();
        private final List<String> block = new ArrayList<>();
        private Optional<String> source = Optional.empty();
        private boolean inBlock;
        private boolean isJava;
        private int start;

        TestQuoteReader(Pattern link) {
            this.link = link;
        }

        List<TestQuote> read(String guide) {
            List<String> lines = List.of(guide.split("\n", -1));
            for (int index = 0; index < lines.size(); index++) {
                accept(index, lines.get(index));
            }
            return quotes;
        }

        private void accept(int index, String line) {
            if (line.startsWith("#")) {
                source = Optional.empty();
            }
            if (!inBlock) {
                scanOutsideBlock(index, line);
            } else if (line.startsWith("```")) {
                closeBlock();
            } else {
                block.add(line);
            }
        }

        private void scanOutsideBlock(int index, String line) {
            Matcher found = link.matcher(line);
            if (found.find()) {
                source = Optional.of(found.group(1));
            }
            if (line.startsWith("```")) {
                inBlock = true;
                isJava = line.startsWith("```java");
                block.clear();
                start = index + 1;
            }
        }

        private void closeBlock() {
            inBlock = false;
            if (isJava && source.isPresent()) {
                quotes.add(new TestQuote(source.get(), start, String.join("\n", block)));
            }
        }
    }
}
