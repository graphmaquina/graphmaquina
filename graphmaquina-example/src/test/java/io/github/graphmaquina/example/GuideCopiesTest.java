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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The guides quote the shop as it is: every Java block of the testing guide that a link to one of the shop's tests
 * introduces appears verbatim in that test, and the four Java blocks of the guide's section on the call's flow are the
 * shop's code, verbatim once the source's Javadoc comments are left out, each with the declaration it quotes. A block
 * may sit deeper in the source than in the guide, by whole levels of four spaces. A blank block quotes nothing, so it
 * fails.
 */
class GuideCopiesTest {

    /** A Java block of the guide's section on the call's flow: the shop's source it quotes, and its declaration. */
    private record Quote(String source, String declaration) { }

    /**
     * A Java block of the testing guide that a link to one of the shop's tests introduces: that test, the line the
     * block starts at, and its code.
     */
    private record TestQuote(String test, int start, String code) { }

    private static final List<String> SHOP_TESTS =
            List.of("OrderFlowTest", "TestingApiTest", "DiagramsTest", "GraphContractsTest");
    private static final Pattern LINK =
            Pattern.compile("\\[(" + String.join("|", SHOP_TESTS) + ")\\]\\(");
    private static final Pattern JAVADOC = Pattern.compile("\n[ \t]*/\\*\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern JAVA_BLOCK = Pattern.compile("```java\n(.*?)```", Pattern.DOTALL);

    private static Path path(String property, String relative) {
        return Path.of(System.getProperty(property)).resolve(relative);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, UTF_8);
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
        List<TestQuote> quotes = new TestQuoteReader().read(read(path("graphmaquina.docs", "testing.md")));
        List<String> missing = new ArrayList<>();
        for (TestQuote quote : quotes) {
            String source = read(path("graphmaquina.example.tests", quote.test() + ".java"));
            if (quote.code().isBlank() || !holds(source, quote.code(), 0, 4, 8, 12)) {
                missing.add("docs/testing.md:" + quote.start() + " " + quote.test());
            }
        }
        assertFalse(quotes.isEmpty(), "no block of the testing guide quotes a test of the shop");
        assertEquals(List.of(), missing);
    }

    @Test
    void theGuideQuotesTheShopsFlowCodeVerbatim() throws IOException {
        String guide = read(path("graphmaquina.docs", "guide.md"));
        String section = guide.substring(guide.indexOf("\n### The call's flow\n"));
        Matcher blocks = JAVA_BLOCK.matcher(section);
        List<Quote> quotes = List.of(new Quote("ShopFlow.java", "public final class ShopFlow {"),
                new Quote("order/OrderActions.java", "public ActionOutcome cancel("),
                new Quote("item/ItemActions.java", "public ActionOutcome restock("),
                new Quote("item/ItemActions.java", "public void applyRestock("));
        List<String> missing = new ArrayList<>();
        for (Quote quote : quotes) {
            assertTrue(blocks.find(), "the section holds fewer than four Java blocks");
            String code = JAVADOC.matcher(read(path("graphmaquina.example.sources", quote.source()))).replaceAll("");
            String block = blocks.group(1);
            if (!block.contains(quote.declaration()) || !holds(code, block, 0, 4)) {
                missing.add(quote.source() + " " + quote.declaration());
            }
        }
        assertEquals(List.of(), missing);
    }

    /**
     * Reads the testing guide line by line. A link outside a code block names the test that the Java blocks after it
     * quote, up to the next heading; a heading is any line that starts with {@code #}, even inside a code block.
     */
    private static final class TestQuoteReader {

        private final List<TestQuote> quotes = new ArrayList<>();
        private final List<String> block = new ArrayList<>();
        private Optional<String> test = Optional.empty();
        private boolean inBlock;
        private boolean isJava;
        private int start;

        List<TestQuote> read(String guide) {
            List<String> lines = List.of(guide.split("\n", -1));
            for (int index = 0; index < lines.size(); index++) {
                accept(index, lines.get(index));
            }
            return quotes;
        }

        private void accept(int index, String line) {
            if (line.startsWith("#")) {
                test = Optional.empty();
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
            Matcher link = LINK.matcher(line);
            if (link.find()) {
                test = Optional.of(link.group(1));
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
            if (isJava && test.isPresent()) {
                quotes.add(new TestQuote(test.get(), start, String.join("\n", block)));
            }
        }
    }
}
