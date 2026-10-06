package io.github.graphmaquina.maven;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
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

    /**
     * plexus-utils appends {@code **} to a pattern that ends with a separator before it unwraps {@code %ant[...]}, so
     * the rule applies to a plain pattern only: {@code %ant[fx/drafts/]} names the path {@code fx/drafts} itself, and
     * no file below it.
     */
    @Test
    void aTrailingSeparatorInsideAnAntPatternSelectsNothingBelowIt() {
        assertFalse(SourcePatterns.matches("%ant[fx/drafts/]", "fx/drafts/Draft.java"));
        assertFalse(SourcePatterns.matches("%ant[fx\\drafts\\]", "fx/drafts/deeper/Draft.java"));
        assertTrue(SourcePatterns.matches("%ant[fx/drafts/]", "fx/drafts"));
    }

    /**
     * plexus-utils reads {@code %ant[...]} and {@code %regex[...]} only when at least two characters stand between
     * the brackets. A shorter one is a plain pattern, brackets included, so {@code %ant[*]} matches no source at the
     * root, while {@code %ant[**]} matches every source.
     */
    @Test
    void aSpelledPatternNeedsTwoCharactersBetweenItsBrackets() {
        assertFalse(SourcePatterns.matches("%ant[*]", "Root.java"));
        assertTrue(SourcePatterns.matches("%ant[*]", "%ant[Root]"));
        assertTrue(SourcePatterns.matches("%ant[**]", "fx/Orders.java"));
    }

    /**
     * plexus-utils turns every pair of backslashes of a {@code %regex[...]} pattern into the separator, so an escaped
     * backslash of the regular expression stands for a separator.
     */
    @Test
    void anEscapedBackslashOfARegularExpressionStandsForASeparator() {
        assertTrue(SourcePatterns.matches("%regex[fx\\\\drafts\\\\.*]", "fx/drafts/Draft.java"));
        assertFalse(SourcePatterns.matches("%regex[fx\\\\drafts\\\\.*]", "fx/Orders.java"));
    }

    /**
     * plexus-utils trims a pattern with {@link String#trim()}, which removes the control characters and the space
     * around it and keeps any other white space, such as an em space.
     */
    @Test
    void aPatternIsTrimmedTheWayStringTrimTrimsIt() {
        assertTrue(SourcePatterns.matches("\u0001 fx/** \t", "fx/Orders.java"));
        assertFalse(SourcePatterns.matches("\u2003fx/**", "fx/Orders.java"));
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

    /**
     * The scanner of the compiler adds the default excludes of plexus-utils on top of the excludes of an execution,
     * so the files these patterns select leave out, as javac leaves them out, an AppleDouble companion, an editor's
     * lock file, a darcs backup and anything inside the directory of a version control system, plexus-utils' longer
     * list of them included. Only a root the execution narrows is selected this way: a root the execution does not
     * narrow is handed to the generator whole, and the generator walks it with Ant's default excludes, not with
     * plexus-utils' longer list.
     */
    @Test
    void leavesOutWhatTheDefaultExcludesName(@TempDir Path root) throws IOException {
        List<String> excluded = List.of("fx/._Orders.java", "fx/.#Orders.java", ".git/fx/Junk.java",
                "fx/.svn/Junk.java", "fx/.hg/Junk.java", "fx/.bzr/Junk.java", "fx/CVS/Junk.java", "fx/SCCS/Junk.java",
                "fx/RCS/Junk.java", "fx/BitKeeper/Junk.java", "fx/ChangeSet/Junk.java", "fx/_darcs/Junk.java",
                "fx/.metadata/Junk.java", "fx/.arch-ids/Junk.java", "fx/.darcsrepo/Junk.java",
                "fx/-darcs-backup1.java");
        for (String source : excluded) {
            Path file = root.resolve(source);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "", StandardCharsets.UTF_8);
        }
        Files.writeString(root.resolve("fx/Orders.java"), "", StandardCharsets.UTF_8);

        SourcePatterns patterns = new SourcePatterns(List.of(), List.of("fx/drafts/**"));

        assertEquals(List.of(root.resolve("fx/Orders.java")), patterns.select(root));
    }

    /**
     * A symbolic link that leads back into a directory the walk is already inside is skipped, as the generator's
     * own walk skips it, so the walk ends and selects every source once. The test is aborted where symbolic links
     * cannot be created.
     */
    @Test
    void skipsALinkThatLeadsBackIntoADirectoryTheWalkIsInside(@TempDir Path root) throws IOException {
        Path orders = root.resolve("fx/Orders.java");
        Files.createDirectories(orders.getParent());
        Files.writeString(orders, "", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(root.resolve("fx/again"), root.resolve("fx"));
        } catch (UnsupportedOperationException | FileSystemException unsupported) {
            Assumptions.abort("symbolic links cannot be created here: " + unsupported);
        }

        SourcePatterns patterns = new SourcePatterns(List.of(), List.of("fx/drafts/**"));

        assertEquals(List.of(orders), patterns.select(root));
    }
}
