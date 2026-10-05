package io.github.graphmaquina.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link SourcePatterns} to the semantics of the directory scanner of maven-compiler-plugin, which decides
 * what javac compiles and therefore what the generator must parse.
 */
class SourcePatternsTest {

    @Test
    void aDoubleStarNameMatchesAnyNumberOfNamesNoneIncluded() {
        assertTrue(SourcePatterns.matches("**/*.java", "Root.java"));
        assertTrue(SourcePatterns.matches("**/*.java", "fx/deep/Nested.java"));
        assertTrue(SourcePatterns.matches("fx/**/Draft.java", "fx/Draft.java"));
        assertTrue(SourcePatterns.matches("fx/**/Draft.java", "fx/a/b/Draft.java"));
        assertFalse(SourcePatterns.matches("fx/**/Draft.java", "other/Draft.java"));
    }

    @Test
    void aStarAndAQuestionMarkStayWithinOneName() {
        assertTrue(SourcePatterns.matches("fx/*.java", "fx/Orders.java"));
        assertFalse(SourcePatterns.matches("fx/*.java", "fx/drafts/Draft.java"));
        assertTrue(SourcePatterns.matches("fx/Draft?.java", "fx/Draft1.java"));
        assertFalse(SourcePatterns.matches("fx/Draft?.java", "fx/Draft12.java"));
        assertTrue(SourcePatterns.matches("**/*Test*.java", "fx/OrdersTests.java"));
    }

    @Test
    void aTrailingSeparatorSelectsEverythingBelowAndNothingElseDoes() {
        assertTrue(SourcePatterns.matches("fx/drafts/", "fx/drafts/Draft.java"));
        assertTrue(SourcePatterns.matches("fx\\drafts\\", "fx/drafts/deeper/Draft.java"));
        assertFalse(SourcePatterns.matches("fx/drafts", "fx/drafts/Draft.java"));
    }

    @Test
    void matchingIsCaseSensitiveAndIgnoresALeadingSeparator() {
        assertFalse(SourcePatterns.matches("FX/**", "fx/Orders.java"));
        assertTrue(SourcePatterns.matches("/fx/**", "fx/Orders.java"));
    }

    @Test
    void regularExpressionsAndExplicitAntPatternsAreRead() {
        assertTrue(SourcePatterns.matches("%regex[fx/drafts/.*]", "fx/drafts/Draft.java"));
        assertFalse(SourcePatterns.matches("%regex[drafts/.*]", "fx/drafts/Draft.java"));
        assertTrue(SourcePatterns.matches("%ant[fx/drafts/**]", "fx/drafts/Draft.java"));
    }

    @Test
    void selectsTheJavaSourcesIncludedAndNotExcluded(@TempDir Path root) throws IOException {
        for (String source : List.of("fx/Orders.java", "fx/drafts/Draft.java", "fx/notes.txt", "other/Other.java")) {
            Path file = root.resolve(source);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "", StandardCharsets.UTF_8);
        }

        SourcePatterns patterns = new SourcePatterns(List.of("fx/**"), List.of("fx/drafts/**"));

        assertTrue(patterns.narrow());
        assertEquals(List.of(root.resolve("fx/Orders.java")), patterns.select(root));
        assertEquals(List.of(), patterns.select(root.resolve("absent")));
        assertFalse(new SourcePatterns(List.of(), List.of()).narrow());
    }
}
