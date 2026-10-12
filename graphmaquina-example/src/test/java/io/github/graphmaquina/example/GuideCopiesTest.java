package io.github.graphmaquina.example;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        List<String> lines = List.of(read(path("graphmaquina.docs", "testing.md")).split("\n", -1));
        Optional<String> current = Optional.empty();
        boolean inBlock = false;
        boolean isJava = false;
        List<String> block = new ArrayList<>();
        int start = 0;
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.startsWith("#")) {
                current = Optional.empty();
            }
            Matcher link = LINK.matcher(line);
            if (link.find() && !inBlock) {
                current = Optional.of(link.group(1));
            }
            if (line.startsWith("```")) {
                if (!inBlock) {
                    inBlock = true;
                    isJava = line.startsWith("```java");
                    block = new ArrayList<>();
                    start = index + 1;
                    continue;
                }
                inBlock = false;
                if (isJava && current.isPresent()) {
                    String source = read(path("graphmaquina.example.tests", current.get() + ".java"));
                    String quoted = String.join("\n", block);
                    checked++;
                    if (quoted.isBlank() || !holds(source, quoted, 0, 4, 8, 12)) {
                        missing.add("docs/testing.md:" + start + " " + current.get());
                    }
                }
                continue;
            }
            if (inBlock) {
                block.add(line);
            }
        }
        assertTrue(checked > 0, "no block of the testing guide quotes a test of the shop");
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
}
