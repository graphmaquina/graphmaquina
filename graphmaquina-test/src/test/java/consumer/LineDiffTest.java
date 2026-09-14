package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.test.internal.LineDiff;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contract of the readable difference. A single changed line prints a window around it, never both
 * documents; the line numbers and markers follow the unified format; only carriage returns are normalized,
 * so a trailing blank or a missing final line feed is itself a difference; and both the alignment table and
 * the printed text stay bounded, which is what keeps a diff readable when a whole file changes.
 */
class LineDiffTest {

    private static final String OMITTED = "more diff lines omitted";

    // --- the premise of this task: one change prints a window, not the document ----------------------

    @Test
    void oneChangedLineInALongDocumentPrintsOnlyItsWindow() {
        StringBuilder expected = new StringBuilder();
        StringBuilder actual = new StringBuilder();
        for (int line = 1; line <= 50; line++) {
            expected.append("l").append(line).append('\n');
            actual.append(line == 25 ? "changed" : "l" + line).append('\n');
        }
        assertEquals("""
                @@ -22,7 +22,7 @@
                 l22
                 l23
                 l24
                -l25
                +changed
                 l26
                 l27
                 l28
                """, LineDiff.render(expected.toString(), actual.toString()));
    }

    // --- the shapes of a change ---------------------------------------------------------------------

    @Test
    void replacementKeepsThreeLinesOfContextOnEachSide() {
        assertEquals("""
                @@ -1,7 +1,7 @@
                 a
                 b
                 c
                -d
                +D
                 e
                 f
                 g
                """, LineDiff.render("a\nb\nc\nd\ne\nf\ng\n", "a\nb\nc\nD\ne\nf\ng\n"));
    }

    @Test
    void insertionCountsOneMoreLineOnTheRight() {
        assertEquals("""
                @@ -1,3 +1,4 @@
                 a
                +X
                 b
                 c
                """, LineDiff.render("a\nb\nc\n", "a\nX\nb\nc\n"));
    }

    @Test
    void deletionCountsOneFewerLineOnTheRight() {
        assertEquals("""
                @@ -1,3 +1,2 @@
                 a
                -b
                 c
                """, LineDiff.render("a\nb\nc\n", "a\nc\n"));
    }

    @Test
    void twoDistantChangesBecomeTwoHunks() {
        StringBuilder expected = new StringBuilder();
        StringBuilder actual = new StringBuilder();
        for (int line = 1; line <= 20; line++) {
            expected.append("l").append(line).append('\n');
            actual.append(line == 2 || line == 18 ? "x" + line : "l" + line).append('\n');
        }
        assertEquals("""
                @@ -1,5 +1,5 @@
                 l1
                -l2
                +x2
                 l3
                 l4
                 l5
                @@ -15,6 +15,6 @@
                 l15
                 l16
                 l17
                -l18
                +x18
                 l19
                 l20
                """, LineDiff.render(expected.toString(), actual.toString()));
    }

    @Test
    void nearbyChangesStayInOneHunk() {
        assertEquals("""
                @@ -1,7 +1,7 @@
                 a
                -b
                +B
                 c
                 d
                 e
                -f
                +F
                 g
                """, LineDiff.render("a\nb\nc\nd\ne\nf\ng\n", "a\nB\nc\nd\ne\nF\ng\n"));
    }

    // --- what is normalized and what is not ---------------------------------------------------------

    @Test
    void equalTextsRenderNothing() {
        assertEquals("", LineDiff.render("a\nb\n", "a\nb\n"));
    }

    @Test
    void onlyLineTerminatorsAreNormalized() {
        assertEquals("", LineDiff.render("a\r\nb\r\n", "a\nb\n"));
        assertEquals("", LineDiff.render("a\rb\r", "a\nb\n"));
        assertEquals("@@ -1,2 +1,2 @@\n-a \n+a\n b\n", LineDiff.render("a \nb\n", "a\nb\n"));
    }

    @Test
    void aMissingFinalLineFeedIsItselfADifference() {
        assertEquals("""
                @@ -1,2 +1,3 @@
                 a
                 b
                +\\ No newline at end of file
                """, LineDiff.render("a\nb\n", "a\nb"));
        assertEquals("""
                @@ -1,3 +1,2 @@
                 a
                 b
                -\\ No newline at end of file
                """, LineDiff.render("a\nb", "a\nb\n"));
    }

    @Test
    void aLiteralMarkerLineDoesNotHideAnExpectedMissingFinalLineFeed() {
        String literal = "a\n\\ No newline at end of file\n";

        assertFalse(LineDiff.render(literal, "a").isEmpty());
    }

    @Test
    void aLiteralMarkerLineDoesNotHideAnActualMissingFinalLineFeed() {
        String literal = "a\n\\ No newline at end of file\n";

        assertFalse(LineDiff.render("a", literal).isEmpty());
    }

    @Test
    void identicalLiteralMarkerLinesRenderNothing() {
        String literal = "a\n\\ No newline at end of file\n";

        assertEquals("", LineDiff.render(literal, literal));
    }

    @Test
    void anEmptyTextIsAllAdditions() {
        assertEquals("""
                @@ -0,0 +1,2 @@
                +a
                +b
                """, LineDiff.render("", "a\nb\n"));
    }

    // --- bounded memory and bounded output ----------------------------------------------------------

    @Test
    void aLongDifferenceIsTruncatedAndSaysHowMuchItDropped() {
        StringBuilder expected = new StringBuilder();
        StringBuilder actual = new StringBuilder();
        for (int line = 0; line < 300; line++) {
            expected.append("a").append(line).append('\n');
            actual.append("b").append(line).append('\n');
        }
        List<String> lines = List.of(LineDiff.render(expected.toString(), actual.toString()).split("\n", -1));
        assertEquals(202, lines.size());
        assertEquals("@@ -1,300 +1,300 @@", lines.get(0));
        assertEquals("-a0", lines.get(1));
        assertEquals("-a198", lines.get(199));
        assertEquals("... 401 " + OMITTED + "; compare the golden resource named by this failure in full",
                lines.get(200));
        assertEquals("", lines.get(201));
    }

    @Test
    void aBlockTooLargeToAlignIsReportedAsOneDeletionAndOneAddition() {
        StringBuilder expected = new StringBuilder();
        StringBuilder actual = new StringBuilder();
        for (int line = 0; line < 1200; line++) {
            expected.append("L").append(line).append('\n');
            actual.append(line % 2 == 0 ? "X" + line : "L" + line).append('\n');
        }
        String diff = LineDiff.render(expected.toString(), actual.toString());
        List<String> lines = new ArrayList<>(List.of(diff.split("\n", -1)));
        assertEquals(202, lines.size());
        assertEquals("@@ -1,1200 +1,1200 @@", lines.get(0));
        assertTrue(lines.get(200).startsWith("... 2200 " + OMITTED), lines.get(200));
        assertFalse(lines.subList(1, 200).stream().anyMatch(line -> line.startsWith(" ")),
                "the aligned path would have kept the odd lines as context");
        assertTrue(lines.subList(1, 200).stream().allMatch(line -> line.startsWith("-")), diff);
    }

    // --- arguments ----------------------------------------------------------------------------------

    @Test
    void nullTextsAreArgumentErrors() {
        assertEquals("expected", assertThrows(NullPointerException.class,
                () -> LineDiff.render(null, "a\n")).getMessage());
        assertEquals("actual", assertThrows(NullPointerException.class,
                () -> LineDiff.render("a\n", null)).getMessage());
    }
}
