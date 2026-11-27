package io.github.graphmaquina.rust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.test.internal.LineDiff;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Differential oracle: {@link RustLineDiff#render} must return, character for character, what the untouched
 * {@link LineDiff#render} returns, including its tie-breaking, its context and hunk grouping, its handling of
 * carriage returns and of a missing final line feed, its fallback past a million cells and its two-hundred-line cap.
 */
class RustLineDiffDifferentialTest {

    private static final List<String> VOCABULARY = List.of("alpha", "beta", "gamma", "delta", "", "  alpha",
            "alpha  ", "\\ No newline at end of file");

    private static final List<String> ENDINGS = List.of("\n", "\n", "\n", "\r\n", "\r");

    @Test
    void randomTextsRenderTheSameDiff() {
        Random random = new Random(0xD1FFL);
        for (int sample = 0; sample < 4_000; sample++) {
            String expected = text(random, random.nextInt(60));
            String actual = random.nextInt(4) == 0 ? mutate(random, expected) : text(random, random.nextInt(60));
            assertEquals(LineDiff.render(expected, actual), RustLineDiff.render(expected, actual),
                    () -> expected + "\n---\n" + actual);
        }
    }

    @Test
    void identicalAndEmptyTextsRenderTheSameDiff() {
        for (String[] pair : new String[][] {{"", ""}, {"a\n", "a\n"}, {"", "a\n"}, {"a\n", ""}, {"a", "a\n"},
                {"a\n", "a"}, {"a\r\nb\n", "a\nb\n"}, {"\n", ""}, {"\n\n", "\n"}}) {
            assertEquals(LineDiff.render(pair[0], pair[1]), RustLineDiff.render(pair[0], pair[1]));
        }
    }

    @Test
    void aRealLineThatReadsLikeTheMissingFeedMarkerStaysADifferentLine() {
        String marker = "\\ No newline at end of file";
        String expected = "x\n" + marker + "\n";
        String actual = "x\n" + marker;
        assertEquals(LineDiff.render(expected, actual), RustLineDiff.render(expected, actual));
        assertEquals(LineDiff.render(actual, expected), RustLineDiff.render(actual, expected));
    }

    @Test
    void aMiddlePastAMillionCellsFallsBackTheSameWay() {
        StringBuilder expected = new StringBuilder("head\n");
        StringBuilder actual = new StringBuilder("head\n");
        for (int i = 0; i < 1_001; i++) {
            expected.append("left ").append(i % 7).append('\n');
            actual.append("right ").append(i % 5).append('\n');
        }
        expected.append("tail\n");
        actual.append("tail\n");
        assertEquals(LineDiff.render(expected.toString(), actual.toString()),
                RustLineDiff.render(expected.toString(), actual.toString()));
    }

    @Test
    void aBlockJustUnderAMillionCellsIsAlignedTheSameWay() {
        Random random = new Random(0xB16L);
        StringBuilder expected = new StringBuilder();
        StringBuilder actual = new StringBuilder();
        for (int i = 0; i < 998; i++) {
            expected.append(VOCABULARY.get(random.nextInt(4))).append(i % 3).append('\n');
            actual.append(VOCABULARY.get(random.nextInt(4))).append(i % 2).append('\n');
        }
        assertEquals(LineDiff.render(expected.toString(), actual.toString()),
                RustLineDiff.render(expected.toString(), actual.toString()));
    }

    @Test
    void aDiffPastTwoHundredLinesIsCutTheSameWay() {
        StringBuilder expected = new StringBuilder();
        StringBuilder actual = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            expected.append("expected ").append(i).append('\n');
            actual.append("actual ").append(i).append('\n');
        }
        assertEquals(LineDiff.render(expected.toString(), actual.toString()),
                RustLineDiff.render(expected.toString(), actual.toString()));
    }

    @Test
    void aNullTextIsRefusedLikeTheLibraryRefusesIt() {
        NullPointerException left = assertThrows(NullPointerException.class, () -> RustLineDiff.render(null, ""));
        assertEquals(assertThrows(NullPointerException.class, () -> LineDiff.render(null, "")).getMessage(),
                left.getMessage());
        NullPointerException right = assertThrows(NullPointerException.class, () -> RustLineDiff.render("", null));
        assertEquals(assertThrows(NullPointerException.class, () -> LineDiff.render("", null)).getMessage(),
                right.getMessage());
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

    /** The same text with a few lines replaced, removed or inserted, so the diff has long common stretches. */
    private static String mutate(Random random, String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder mutated = new StringBuilder();
        for (String line : lines) {
            int roll = random.nextInt(10);
            if (roll == 0) {
                continue;
            }
            if (roll == 1) {
                mutated.append(VOCABULARY.get(random.nextInt(VOCABULARY.size()))).append('\n');
            }
            mutated.append(line).append('\n');
        }
        return mutated.toString();
    }
}
