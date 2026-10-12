package io.github.graphmaquina.maven;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins {@link SourcePatterns} to the semantics of the directory scanner of maven-compiler-plugin, which decides
 * what javac compiles and therefore what the generator must parse. That scanner spells a path with the file separator
 * of the platform it runs on, so every test names the separator it matches for: {@code /}, or {@code \} as on
 * Windows. The differential tests compare the patterns with {@link PlexusScanner}, the scanner of plexus-utils 4.0.2
 * transcribed with its separator made a parameter, under both separators.
 */
class SourcePatternsTest {

    /** The files of the tree the differential tests scan, their names separated by {@code /}. */
    private static final List<String> TREE = List.of("Root.java", "fx/Orders.java", "fx/drafts/Draft.java",
            "fx/drafts/deeper/Deep.java", "other/Other.java", "io/Io.java",
            "io/github/graphmaquina/generated/bootstrap/GraphCompilation0123456789abcdef.java", "fx/._Junk.java",
            "fx/.git/Junk.java", "a b/Space.java");

    /**
     * The includes the differential tests try, one at a time: separators leading, doubled, trailing and of either
     * kind, the spellings {@code %ant[...]} and {@code %regex[...]}, too short ones among them, and regular
     * expressions whose separators plexus-utils rewrites differently for each platform.
     */
    private static final List<String> INCLUDES = List.of("**/*.java", "**", "fx/**", "/fx/**", "\\fx\\**",
            "/io/github/graphmaquina/generated/**", "io/github/graphmaquina/generated/**", "/**/*.java",
            "/*/Orders.java", "fx/drafts/", "/fx/drafts/", "fx\\", "%ant[/fx/**]", "%ant[fx/drafts/]", "%ant[*]",
            "%ant[**]", "%regex[fx/drafts/.*]", "%regex[fx\\\\drafts\\\\.*]", "%regex[fx\\/drafts\\/.*]",
            "%regex[[^/]*\\.java]", "%regex[\\]", "%regex[/]", "%regex[a/", "%regex[.*]", " fx/** ", "FX/**",
            "fx/Draft?.java", "io/**", "/io/**", "//fx//**", "*/*", "/*", "io/*/graphmaquina/**", "a b/**");

    /** The excludes the differential tests try, one at a time, beside each include and beside none. */
    private static final List<String> EXCLUDES = List.of("fx/drafts", "fx/drafts/**", "/fx/drafts/**", "io/github",
            "%regex[.*Draft.*]", "%regex[fx/drafts]", "%regex[fx\\\\drafts]", "\\fx\\drafts\\", "/io",
            "**/deeper");

    @Test
    void aDoubleStarNameMatchesAnyNumberOfNamesNoneIncluded() {
        assertTrue(SourcePatterns.matches("**/*.java", "Root.java", '/'));
        assertTrue(SourcePatterns.matches("**/*.java", "fx/deep/Nested.java", '/'));
        assertTrue(SourcePatterns.matches("fx/**/Draft.java", "fx/Draft.java", '/'));
        assertTrue(SourcePatterns.matches("fx/**/Draft.java", "fx/a/b/Draft.java", '/'));
        assertFalse(SourcePatterns.matches("fx/**/Draft.java", "other/Draft.java", '/'));
    }

    @Test
    void aStarAndAQuestionMarkStayWithinOneName() {
        assertTrue(SourcePatterns.matches("fx/*.java", "fx/Orders.java", '/'));
        assertFalse(SourcePatterns.matches("fx/*.java", "fx/drafts/Draft.java", '/'));
        assertTrue(SourcePatterns.matches("fx/Draft?.java", "fx/Draft1.java", '/'));
        assertFalse(SourcePatterns.matches("fx/Draft?.java", "fx/Draft12.java", '/'));
        assertTrue(SourcePatterns.matches("**/*Test*.java", "fx/OrdersTests.java", '/'));
    }

    @Test
    void aTrailingSeparatorSelectsEverythingBelowAndNothingElseDoes() {
        assertTrue(SourcePatterns.matches("fx/drafts/", "fx/drafts/Draft.java", '/'));
        assertTrue(SourcePatterns.matches("fx\\drafts\\", "fx/drafts/deeper/Draft.java", '/'));
        assertFalse(SourcePatterns.matches("fx/drafts", "fx/drafts/Draft.java", '/'));
    }

    /**
     * Matching a path ignores a leading separator of the pattern. Whether the scanner of the compiler reaches that
     * path is another question, which {@link #theScannerEntersADirectoryOnlyWhenAnIncludeMatchesItOrCouldMatchBelowIt}
     * answers: it reaches {@code fx/Orders.java} for {@code /fx/**}, which matches the directory {@code fx} itself.
     */
    @Test
    void matchingIsCaseSensitiveAndIgnoresALeadingSeparator() {
        assertFalse(SourcePatterns.matches("FX/**", "fx/Orders.java", '/'));
        assertTrue(SourcePatterns.matches("/fx/**", "fx/Orders.java", '/'));
    }

    @Test
    void regularExpressionsAndExplicitAntPatternsAreRead() {
        assertTrue(SourcePatterns.matches("%regex[fx/drafts/.*]", "fx/drafts/Draft.java", '/'));
        assertFalse(SourcePatterns.matches("%regex[drafts/.*]", "fx/drafts/Draft.java", '/'));
        assertTrue(SourcePatterns.matches("%ant[fx/drafts/**]", "fx/drafts/Draft.java", '/'));
    }

    /**
     * plexus-utils appends {@code **} to a pattern that ends with a separator before it unwraps {@code %ant[...]}, so
     * the rule applies to a plain pattern only: {@code %ant[fx/drafts/]} names the path {@code fx/drafts} itself, and
     * no file below it.
     */
    @Test
    void aTrailingSeparatorInsideAnAntPatternSelectsNothingBelowIt() {
        assertFalse(SourcePatterns.matches("%ant[fx/drafts/]", "fx/drafts/Draft.java", '/'));
        assertFalse(SourcePatterns.matches("%ant[fx\\drafts\\]", "fx/drafts/deeper/Draft.java", '/'));
        assertTrue(SourcePatterns.matches("%ant[fx/drafts/]", "fx/drafts", '/'));
    }

    /**
     * plexus-utils reads {@code %ant[...]} and {@code %regex[...]} only when at least two characters stand between
     * the brackets. A shorter one is a plain pattern, brackets included, so {@code %ant[*]} matches no source at the
     * root, while {@code %ant[**]} matches every source.
     */
    @Test
    void aSpelledPatternNeedsTwoCharactersBetweenItsBrackets() {
        assertFalse(SourcePatterns.matches("%ant[*]", "Root.java", '/'));
        assertTrue(SourcePatterns.matches("%ant[*]", "%ant[Root]", '/'));
        assertTrue(SourcePatterns.matches("%ant[**]", "fx/Orders.java", '/'));
    }

    /**
     * Where the file separator is {@code /}, plexus-utils turns every pair of backslashes of a {@code %regex[...]}
     * pattern into the separator, so an escaped backslash of the regular expression stands for a separator.
     */
    @Test
    void anEscapedBackslashOfARegularExpressionStandsForASeparator() {
        assertTrue(SourcePatterns.matches("%regex[fx\\\\drafts\\\\.*]", "fx/drafts/Draft.java", '/'));
        assertFalse(SourcePatterns.matches("%regex[fx\\\\drafts\\\\.*]", "fx/Orders.java", '/'));
    }

    /**
     * plexus-utils trims a pattern with {@link String#trim()}, which removes the control characters and the space
     * around it and keeps any other white space, such as an em space.
     */
    @Test
    void aPatternIsTrimmedTheWayStringTrimTrimsIt() {
        assertTrue(SourcePatterns.matches("\u0001 fx/** \t", "fx/Orders.java", '/'));
        assertFalse(SourcePatterns.matches("\u2003fx/**", "fx/Orders.java", '/'));
    }

    @Test
    void selectsTheJavaSourcesIncludedAndNotExcluded(@TempDir Path root) throws IOException {
        for (String source : List.of("fx/Orders.java", "fx/drafts/Draft.java", "fx/notes.txt", "other/Other.java")) {
            Path file = root.resolve(source);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "", StandardCharsets.UTF_8);
        }

        SourcePatterns patterns = new SourcePatterns(List.of("fx/**"), List.of("fx/drafts/**"), '/');

        assertTrue(patterns.narrow());
        assertEquals(List.of(root.resolve("fx/Orders.java")), patterns.select(root));
        assertEquals(List.of(), patterns.select(root.resolve("absent")));
        assertFalse(new SourcePatterns(List.of(), List.of(), '/').narrow());
    }

    /**
     * The scanner of the compiler enters a directory below the root only when an include matches that directory and
     * no exclude does, or when an include could match a path below it, which plexus-utils judges false for every
     * include that starts with a separator where the file separator is {@code /}. Such an include still matches a path
     * as if it had no leading separator, so {@code /fx/**} selects the sources under {@code fx}, a directory it
     * matches, while {@code /io/github/graphmaquina/generated/**} selects nothing, since it matches no directory above
     * those sources, and {@code /**}{@code /*.java} selects only the sources at the root. An excluded directory is
     * entered only when an include could match a path below it. Every selection here is the one the DirectoryScanner
     * of plexus-utils 4.0.2 makes where the file separator is {@code /}.
     */
    @Test
    void theScannerEntersADirectoryOnlyWhenAnIncludeMatchesItOrCouldMatchBelowIt(@TempDir Path root)
            throws IOException {
        String carrier = "io/github/graphmaquina/generated/bootstrap/GraphCompilation0123456789abcdef.java";
        for (String source : List.of("Root.java", "fx/Orders.java", "fx/drafts/Draft.java", "other/Other.java",
                carrier)) {
            Path file = root.resolve(source);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "", StandardCharsets.UTF_8);
        }
        List<Path> fx = List.of(root.resolve("fx/Orders.java"), root.resolve("fx/drafts/Draft.java"));

        assertEquals(fx, new SourcePatterns(List.of("/fx/**", "/io/github/graphmaquina/generated/**"), List.of(), '/')
                .select(root));
        assertEquals(fx, new SourcePatterns(List.of("\\fx\\**", "\\io\\github\\graphmaquina\\generated\\**"),
                List.of(), '/').select(root));
        assertEquals(List.of(root.resolve("fx/Orders.java"), root.resolve("fx/drafts/Draft.java"),
                root.resolve(carrier)), new SourcePatterns(List.of("/fx/**", "io/github/graphmaquina/generated/**"),
                List.of(), '/').select(root));
        assertEquals(List.of(root.resolve("Root.java")), new SourcePatterns(List.of("/**/*.java"), List.of(), '/')
                .select(root));
        assertEquals(List.of(), new SourcePatterns(List.of("/io/**"), List.of("io/github"), '/').select(root));
        assertEquals(List.of(root.resolve("fx/Orders.java")), new SourcePatterns(List.of("/fx/**"),
                List.of("fx/drafts"), '/').select(root));
        assertEquals(fx, new SourcePatterns(List.of("fx/**"), List.of("fx/drafts"), '/').select(root));
        assertEquals(List.of(root.resolve(carrier)), new SourcePatterns(List.of("/io/github/graphmaquina/generated/**",
                "%regex[nothing.*]"), List.of(), '/').select(root));
    }

    /**
     * Whether one source is selected, which the check of the carrier asks before the carrier exists, follows the same
     * walk: an include that matches the path is not enough when the scanner never enters a directory above it, as
     * where the file separator is {@code /}, and the first such directory is named.
     */
    @Test
    void oneSourceIsSelectedOnlyWhenTheScannerEntersEveryDirectoryAboveIt() {
        String carrier = "io/github/graphmaquina/generated/bootstrap/GraphCompilation0123456789abcdef.java";
        SourcePatterns leading = new SourcePatterns(List.of("/io/github/graphmaquina/generated/**"), List.of(), '/');
        SourcePatterns plain = new SourcePatterns(List.of("io/github/graphmaquina/generated/**"), List.of(), '/');

        assertTrue(SourcePatterns.matches("/io/github/graphmaquina/generated/**", carrier, '/'));
        assertFalse(leading.selects(carrier));
        assertEquals(Optional.of("io"), leading.skippedDirectory(carrier));
        assertTrue(plain.selects(carrier));
        assertEquals(Optional.empty(), plain.skippedDirectory(carrier));
        assertEquals(Optional.of("io/github"),
                new SourcePatterns(List.of("/io/**"), List.of("io/github"), '/').skippedDirectory(carrier));
    }

    /**
     * Where the file separator is {@code \}, as on Windows, plexus-utils turns every {@code /} of an include into
     * {@code \} before it reads the include, and judges whether the include could match below a directory a second
     * time, over the separator {@code /}, which the include then no longer starts with. So an include that starts with
     * a separator lets the scanner into a directory as if it had none: {@code /io/github/graphmaquina/generated/**}
     * selects the carrier, which the scanner reaches through every directory above it, {@code /fx/**} the drafts under
     * the excluded directory {@code fx/drafts}, and {@code /**}{@code /*.java} every source. Every selection here is
     * the one plexus-utils 4.0.2 makes on such a platform.
     */
    @Test
    void whereTheSeparatorIsABackslashAnIncludeThatStartsWithOneLetsTheScannerIn(@TempDir Path root)
            throws IOException {
        String carrier = "io/github/graphmaquina/generated/bootstrap/GraphCompilation0123456789abcdef.java";
        for (String source : List.of("Root.java", "fx/Orders.java", "fx/drafts/Draft.java", "other/Other.java",
                carrier)) {
            Path file = root.resolve(source);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "", StandardCharsets.UTF_8);
        }
        List<Path> fx = List.of(root.resolve("fx/Orders.java"), root.resolve("fx/drafts/Draft.java"));
        SourcePatterns leading = new SourcePatterns(List.of("/io/github/graphmaquina/generated/**"), List.of(), '\\');

        assertTrue(leading.selects(carrier));
        assertEquals(Optional.empty(), leading.skippedDirectory(carrier));
        assertEquals(List.of(root.resolve("fx/Orders.java"), root.resolve("fx/drafts/Draft.java"),
                root.resolve(carrier)), new SourcePatterns(List.of("/fx/**", "/io/github/graphmaquina/generated/**"),
                List.of(), '\\').select(root));
        assertEquals(fx, new SourcePatterns(List.of("/fx/**"), List.of("fx/drafts"), '\\').select(root));
        assertEquals(List.of(root.resolve("Root.java"), root.resolve("fx/Orders.java"),
                root.resolve("fx/drafts/Draft.java"), root.resolve(carrier), root.resolve("other/Other.java")),
                new SourcePatterns(List.of("/**/*.java"), List.of(), '\\').select(root));
    }

    /**
     * plexus-utils rewrites the separators of a {@code %regex[...]} pattern for the platform, and matches it against
     * the path spelled with the file separator: where that is {@code /}, every pair of backslashes becomes {@code /};
     * where it is {@code \}, every {@code /} becomes a pair of backslashes, an escaped backslash. Either way {@code /}
     * and an escaped backslash stand for the separator. An escaped {@code /}, which Java reads as {@code /}, becomes
     * three backslashes where the separator is {@code \}, a literal backslash followed by an escape of the next
     * character, so {@code fx\/drafts\/.*} matches the drafts only where the separator is {@code /}.
     */
    @Test
    void aRegularExpressionIsRewrittenForTheSeparatorOfThePlatform() {
        String draft = "fx/drafts/Draft.java";

        assertTrue(SourcePatterns.matches("%regex[fx/drafts/.*]", draft, '\\'));
        assertTrue(SourcePatterns.matches("%regex[fx\\\\drafts\\\\.*]", draft, '\\'));
        assertTrue(SourcePatterns.matches("%regex[fx\\/drafts\\/.*]", draft, '/'));
        assertFalse(SourcePatterns.matches("%regex[fx\\/drafts\\/.*]", draft, '\\'));
    }

    /**
     * Under either separator, the patterns select exactly the files of a tree that the scanner of plexus-utils
     * includes with them: each of {@link #INCLUDES}, or none, beside each of {@link #EXCLUDES}, or none.
     */
    @Test
    void selectsTheFilesTheScannerOfPlexusUtilsIncludesUnderEitherSeparator() {
        for (char separator : new char[] {'/', '\\'}) {
            for (List<String> includes : listsOfOne(INCLUDES)) {
                for (List<String> excludes : listsOfOne(EXCLUDES)) {
                    SourcePatterns patterns = new SourcePatterns(includes, excludes, separator);
                    Set<String> selected = new TreeSet<>();
                    for (String file : TREE) {
                        if (patterns.selects(file)) {
                            selected.add(file);
                        }
                    }

                    assertEquals(new PlexusScanner(includes, excludes, separator).includedFiles(Set.copyOf(TREE)),
                            selected, "separator " + separator + ", includes " + includes + ", excludes " + excludes);
                }
            }
        }
    }

    /**
     * Under either separator, a pattern matches the path of a file exactly when the scanner of plexus-utils finds that
     * file included by it, whatever the directories above it.
     */
    @Test
    void matchesAPathAsTheScannerOfPlexusUtilsMatchesItUnderEitherSeparator() {
        List<String> patterns = new ArrayList<>(INCLUDES);
        patterns.addAll(EXCLUDES);
        for (char separator : new char[] {'/', '\\'}) {
            for (String pattern : patterns) {
                PlexusScanner scanner = new PlexusScanner(List.of(pattern), List.of(), separator);
                for (String path : TREE) {
                    assertEquals(scanner.isIncluded(path), SourcePatterns.matches(pattern, path, separator),
                            "separator " + separator + ", pattern " + pattern + ", path " + path);
                }
            }
        }
    }

    /**
     * plexus-utils also judges whether an include could match below a directory with every backslash of the path read
     * as a separator, so a directory named {@code \fx} counts as {@code /fx}, which an include that starts with a
     * separator can match the start of. The test runs only where the separator is {@code /}, and is aborted where such
     * a name cannot be created.
     */
    @Test
    void aBackslashInTheNameOfADirectoryIsAlsoReadAsASeparator(@TempDir Path root) throws IOException {
        assumeTrue(File.separatorChar == '/', "a backslash is a separator here");
        Path named = root.resolve("\\fx/Y.java");
        try {
            Files.createDirectories(named.getParent());
            Files.writeString(named, "", StandardCharsets.UTF_8);
        } catch (InvalidPathException | FileSystemException unsupported) {
            Assumptions.abort("a directory named \\fx cannot be created here: " + unsupported);
        }
        Files.createDirectories(root.resolve("other"));
        Files.writeString(root.resolve("other/Y.java"), "", StandardCharsets.UTF_8);

        assertEquals(List.of(named), new SourcePatterns(List.of("/*/Y.java"), List.of(), '/').select(root));
    }

    /**
     * The scanner of the compiler adds the default excludes of plexus-utils on top of the excludes of an execution,
     * so the files these patterns select leave out, as javac leaves them out, an AppleDouble companion, an editor's
     * lock file, a darcs backup and anything inside the directory of a version control system, plexus-utils' longer
     * list of them included. A root the execution does not narrow is selected this way only when javac compiles a
     * source under it that the generator's walk leaves out; otherwise it is handed to the generator whole, and the
     * generator walks it with Ant's default excludes, not with plexus-utils' longer list.
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

        SourcePatterns patterns = new SourcePatterns(List.of(), List.of("fx/drafts/**"), '/');

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

        SourcePatterns patterns = new SourcePatterns(List.of(), List.of("fx/drafts/**"), '/');

        assertEquals(List.of(orders), patterns.select(root));
    }

    /** The empty list, followed by a list of each of {@code patterns} alone. */
    private static List<List<String>> listsOfOne(List<String> patterns) {
        List<List<String>> lists = new ArrayList<>();
        lists.add(List.of());
        for (String pattern : patterns) {
            lists.add(List.of(pattern));
        }
        return lists;
    }
}
