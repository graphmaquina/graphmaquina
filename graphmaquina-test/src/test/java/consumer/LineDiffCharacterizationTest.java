package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.test.internal.LineDiff;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Pins, character for character, what {@link LineDiff#render} returns over fixed corpora: random and mutated texts
 * with every line ending and with or without a final line feed, a real line that reads like the missing-feed marker,
 * middles exactly at and just past the million-cell bound, texts of a few lines and of hundreds with predictable and
 * with repetitive lines, and diffs past the two-hundred-line cut. Each corpus is pinned by the SHA-256 of its rendered
 * diffs, taken on the code before the alignment moved to integer arrays (v2.0.9, e5521d52).
 */
class LineDiffCharacterizationTest {

    private static final List<String> VOCABULARY = List.of("alpha", "beta", "gamma", "delta", "", "  alpha",
            "alpha  ", "\\ No newline at end of file");

    private static final List<String> ENDINGS = List.of("\n", "\n", "\n", "\r\n", "\r");

    @Test
    void randomAndMutatedTextsKeepTheirDiffs() {
        Random random = new Random(0xD1FF_0001L);
        List<String[]> pairs = new ArrayList<>();
        for (int sample = 0; sample < 6_000; sample++) {
            String expected = text(random, random.nextInt(60));
            pairs.add(new String[] {expected, random.nextInt(3) == 0 ? mutate(random, expected)
                    : text(random, random.nextInt(60))});
        }
        assertEquals("4f6612d68a99c7b8f87f0471f62941e6051ce9e11422daf42c190d812c4e7cbb",
                digest(pairs));
    }

    @Test
    void edgeTextsKeepTheirDiffs() {
        String marker = "\\ No newline at end of file";
        assertEquals("83f6fec4cc8fb0c21c3855d6c3974cdf572e00cf83c180777eba6a8b226de8d3",
                digest(List.of(new String[] {"", ""}, new String[] {"a\n", "a\n"},
                new String[] {"", "a\n"}, new String[] {"a\n", ""}, new String[] {"a", "a\n"},
                new String[] {"a\n", "a"}, new String[] {"a\r\nb\n", "a\nb\n"}, new String[] {"\n", ""},
                new String[] {"\n\n", "\n"}, new String[] {"x\n" + marker + "\n", "x\n" + marker},
                new String[] {"x\n" + marker, "x\n" + marker + "\n"}, new String[] {"\r", "\r\n"},
                new String[] {"a\rb", "a\nb\n"})));
    }

    @Test
    void middlesAtAndPastTheMillionCellBoundKeepTheirDiffs() {
        List<String[]> pairs = new ArrayList<>();
        for (int[] size : new int[][] {{999, 999}, {1_000, 999}, {999, 1_000}, {998, 1_001}, {1_001, 1_001}}) {
            Random random = new Random(size[0] * 31L + size[1]);
            pairs.add(new String[] {block(random, "left", size[0], 3), block(random, "right", size[1], 2)});
        }
        assertEquals("f55203dcabaccdd3ba944c7288992e8829de4e88ad9c5ce69f94880972e4eb5a",
                digest(pairs));
    }

    @Test
    void predictableAndRepetitiveTextsOfManySizesKeepTheirDiffs() {
        List<String[]> pairs = new ArrayList<>();
        for (int lines : new int[] {1, 2, 8, 32, 128, 512, 990}) {
            pairs.add(predictable(lines));
            pairs.add(repetitive(new Random(lines), lines));
        }
        assertEquals("3dc3ed2fa741d1c5ab5860925352fd58ba276f2213a292d94f871baf32925d74",
                digest(pairs));
    }

    @Test
    void diffsPastTwoHundredLinesKeepTheirCut() {
        List<String[]> pairs = new ArrayList<>();
        for (int lines : new int[] {60, 66, 67, 99, 100, 101, 300}) {
            StringBuilder expected = new StringBuilder();
            StringBuilder actual = new StringBuilder();
            for (int i = 0; i < lines; i++) {
                expected.append("expected ").append(i).append('\n');
                actual.append(i % 9 == 0 ? "expected " : "actual ").append(i).append('\n');
            }
            pairs.add(new String[] {expected.toString(), actual.toString()});
        }
        assertEquals("0a9ed6cc1b6bfc9461d22b7667176620acc3edf0569b0689c75b7df36747d926",
                digest(pairs));
    }

    /** Every tenth line changed, the rest unique, so equal lines are rare and predictable. */
    private static String[] predictable(int lines) {
        StringBuilder expected = new StringBuilder();
        StringBuilder actual = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            expected.append("line ").append(i).append('\n');
            actual.append(i % 10 == 3 ? "changed " : "line ").append(i).append('\n');
        }
        return new String[] {expected.toString(), actual.toString()};
    }

    /** Every line drawn from twelve, so equal lines are frequent and unpredictable. */
    private static String[] repetitive(Random random, int lines) {
        StringBuilder expected = new StringBuilder();
        StringBuilder actual = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            expected.append("word ").append(random.nextInt(12)).append('\n');
            actual.append("word ").append(random.nextInt(12)).append('\n');
        }
        return new String[] {expected.toString(), actual.toString()};
    }

    /**
     * A middle block of exactly {@code lines} lines between a common head and tail: its first and last lines name the
     * side, so neither the common prefix nor the common suffix reaches into it, and the lines between them repeat.
     */
    private static String block(Random random, String side, int lines, int modulus) {
        StringBuilder text = new StringBuilder("head\nfirst ").append(side).append('\n');
        for (int i = 2; i < lines; i++) {
            text.append(VOCABULARY.get(random.nextInt(4))).append(i % modulus).append('\n');
        }
        return text.append("last ").append(side).append("\ntail\n").toString();
    }

    private static String text(Random random, int lines) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            text.append(VOCABULARY.get(random.nextInt(VOCABULARY.size())));
            if (i < lines - 1 || random.nextInt(5) != 0) {
                text.append(ENDINGS.get(random.nextInt(ENDINGS.size())));
            }
        }
        return text.toString();
    }

    /** The same text with a few lines removed or inserted, so the diff has long common stretches. */
    private static String mutate(Random random, String text) {
        StringBuilder mutated = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            int roll = random.nextInt(10);
            if (roll == 1) {
                mutated.append(VOCABULARY.get(random.nextInt(VOCABULARY.size()))).append('\n');
            }
            if (roll != 0) {
                mutated.append(line).append('\n');
            }
        }
        return mutated.toString();
    }

    private static String digest(List<String[]> pairs) {
        MessageDigest digest = sha256();
        for (String[] pair : pairs) {
            digest.update(LineDiff.render(pair[0], pair[1]).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException(absent);
        }
    }
}
