package io.github.graphmaquina.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds {@link SourcePatterns} to {@link ReferenceSourcePatterns}, the plainest transcription of the selection it
 * makes, over patterns and paths generated from fragments that reach every rule of that selection: separators
 * leading, doubled, trailing and of either kind, the spellings {@code %ant[...]} and {@code %regex[...]}, too short
 * and unterminated ones among them, regular expressions that do and do not compile, white space
 * {@link String#trim()} removes and white space it keeps, wildcards, the default excludes, and names that are empty
 * or hold a backslash, a line terminator or a surrogate pair. Every comparison runs under both separators, with no,
 * one and two includes and excludes, and an outcome is the value returned or the type and message of the exception
 * thrown, so that what fails, and when, is held to the reference as well.
 */
class SourcePatternsReferenceTest {

    /** The seed the inputs are generated from, so that every run compares the same inputs. */
    private static final long SEED = 20261126L;

    /** How an outcome that is an exception starts. */
    private static final String THROWS = "throws ";

    /** The file separators of the platforms the scanner of the compiler runs on. */
    private static final char[] SEPARATORS = {'/', '\\'};

    /** The names generated patterns are made of, wildcards, default excludes and an empty name among them. */
    private static final List<String> PATTERN_NAMES = List.of("fx", "drafts", "deeper", "io", "*", "?", "**",
            "*.java", "Draft?.java", "D*t.java", "*a*", "a b", "*~", ".#*", "._*", ".git", "CVS", "?.java", "??.java",
            "a?b.java", "x\\y", "");

    /** What separates two names of a generated pattern: a separator of either kind, alone or doubled. */
    private static final List<String> PATTERN_SEPARATORS = List.of("/", "\\", "//", "\\\\");

    /** What a generated pattern starts and ends with: mostly nothing, else a separator of either kind. */
    private static final List<String> ENDS = List.of("", "", "/", "\\");

    /** What surrounds a generated pattern: mostly nothing, else white space trimmed away or, an em space, kept. */
    private static final List<String> PADDING = List.of("", "", " ", "\t", "\u0001", " ");

    /**
     * The pieces generated regular expressions are made of: separators of either kind, escaped or not, wildcards, a
     * group, and pieces that compile only after others or never, such as a {@code ?} or a backslash at the start or
     * end, or an unclosed group.
     */
    private static final List<String> REGEX_PIECES = List.of("fx", "drafts", ".*", "[^/]*", "\\.java", "/", "\\\\",
            "\\/", "Draft", "(fx|other)", "a b", "[^\\\\]*", ".", "?", "\\", "(", "Root\\.java", "[a-z]+");

    /**
     * Patterns written out rather than generated: empty, blank and lone separators, spellings too short to be read
     * as one, unterminated or wrongly cased, an expression that does not compile, and spellings inside spellings.
     */
    private static final List<String> WRITTEN_PATTERNS = List.of("", " ", "/", "\\", "**", "**/", "*", "%ant[*]",
            "%ant[]", "%ant[ab]", "%ant[fx/drafts/]", "%ant[**]", "%regex[/]", "%regex[\\]", "%regex[a/", "%regex[]",
            "%regex[ab]", "%regex[", "%ant[", "%regex[.*]x", "%REGEX[.*]", "%regex[a(b]", " %regex[.*] ",
            "%ant[%regex[.*]]", "%regex[%ant[**]]", "%regex[\\\\\\]", "%regex[fx\\\\\\\\drafts.*]");

    /** Includes that let the scanner reach many sources, which half of the generated includes are drawn from. */
    private static final List<String> WIDE_INCLUDES = List.of("**/*.java", "**", "fx/**", "/fx/**", "\\fx\\**",
            "*/*", "/*", "%regex[.*]", "%regex[fx/.*]", "io/*/graphmaquina/**", "fx/drafts/", "/**/*.java", "io/**",
            "%ant[fx/**]");

    /** Excludes that leave out some of those sources, which half of the generated excludes are drawn from. */
    private static final List<String> NARROW_EXCLUDES = List.of("fx/drafts", "fx/drafts/**", "/fx/drafts/**",
            "io/github", "%regex[.*Draft.*]", "%regex[fx/drafts]", "%regex[fx\\\\drafts]", "\\fx\\drafts\\", "/io",
            "**/deeper", "**/*.java");

    /** The names generated paths are made of: an empty name, and names a default exclude or a wildcard meets. */
    private static final List<String> PATH_NAMES = List.of("fx", "drafts", "deeper", "other", "io", "github",
            "Root.java", "Orders.java", "Draft.java", "Draft1.java", "Dt.java", "a b", "a\\b", "x\\y", "._Junk.java",
            ".git", "CVS", "old~", "-darcs-backup1.java", "#x#", ".#lock.java", "😀.java", "a\nb.java",
            "%x%", ".gitignore", "a.java", "ab.java", "a b.java", "");

    /** The files of the tree the walk is compared over, named as any file system can name them. */
    private static final List<String> TREE = List.of("Root.java", "x.java", "fx/Orders.java", "fx/Draft1.java",
            "fx/notes.txt", "fx/drafts/Draft.java", "fx/drafts/deeper/Deep.java", "drafts/fx/Inverse.java",
            "other/Other.java", "io/Io.java", "io/github/graphmaquina/generated/bootstrap/G.java", "fx/._Junk.java",
            "fx/.git/Junk.java", "fx/CVS/Junk.java", "old~/Old.java", "a b/Space.java", "Drafts/D.java");

    /** Every pattern compared: the written ones, then plain, {@code %ant[...]} and {@code %regex[...]} ones. */
    private static final List<String> PATTERNS = patterns();

    /** Every path compared, its names separated by {@code /}: the files of the tree, then generated ones. */
    private static final List<String> PATHS = paths();

    /** The inputs whose outcomes differ, each with both outcomes. */
    private final List<String> mismatches = new ArrayList<>();

    /** The outcomes the reference gave, an exception counted by its kind alone. */
    private final Set<String> seen = new TreeSet<>();

    /** How many outcomes were compared. */
    private int compared;

    /**
     * Under either separator, every pattern matches every path, or fails to, as the reference does.
     */
    @Test
    void matchesAPathAsTheReferenceDoesUnderEitherSeparator() {
        for (char separator : SEPARATORS) {
            for (String pattern : PATTERNS) {
                for (String path : PATHS) {
                    compare("separator " + separator + ", pattern " + pattern + ", path " + path,
                            () -> ReferenceSourcePatterns.matches(pattern, path, separator),
                            () -> SourcePatterns.matches(pattern, path, separator));
                }
            }
        }

        assertAgreement(List.of("true", "false", THROWS));
    }

    /**
     * Under either separator, generated includes and excludes narrow the sources, select every path and skip the
     * directories above it as the reference does, read anew for every question.
     */
    @Test
    void selectsAPathAsTheReferenceDoesUnderEitherSeparator() {
        for (char separator : SEPARATORS) {
            for (Selection selection : selections(SEED + 2, 250)) {
                compareSelection(selection, separator);
            }
        }

        assertAgreement(List.of("true", "false", THROWS, "Optional.empty"));
        assertTrue(seen.stream().anyMatch((String outcome) -> outcome.startsWith("Optional[")),
                "a directory is skipped among " + seen);
    }

    /**
     * Under either separator, generated includes and excludes select the files of a tree on disk as the reference
     * does, in the same order, and nothing under a root that does not exist.
     */
    @Test
    void selectsTheFilesOfATreeAsTheReferenceDoesUnderEitherSeparator(@TempDir Path root) throws IOException {
        for (String file : TREE) {
            Path source = root.resolve(file);
            Files.createDirectories(source.getParent());
            Files.writeString(source, "", StandardCharsets.UTF_8);
        }
        for (char separator : SEPARATORS) {
            for (Selection selection : selections(SEED + 3, 150)) {
                String input = "separator " + separator + ", " + selection;
                compare(input + ", select", () -> selection.reference(separator).select(root),
                        () -> selection.production(separator).select(root));
                compare(input + ", select absent", () -> selection.reference(separator).select(root.resolve("no")),
                        () -> selection.production(separator).select(root.resolve("no")));
            }
        }

        assertAgreement(List.of("[]", THROWS));
        assertTrue(seen.size() > 10, "the selections differ among themselves: " + seen);
    }

    /** Compares what one selection answers for every path, and whether it narrows the sources. */
    private void compareSelection(Selection selection, char separator) {
        String patterns = "separator " + separator + ", " + selection;
        compare(patterns + ", narrow", () -> selection.reference(separator).narrow(),
                () -> selection.production(separator).narrow());
        for (String path : PATHS) {
            compare(patterns + ", selects " + path, () -> selection.reference(separator).selects(path),
                    () -> selection.production(separator).selects(path));
            compare(patterns + ", skipped directory of " + path,
                    () -> selection.reference(separator).skippedDirectory(path),
                    () -> selection.production(separator).skippedDirectory(path));
        }
    }

    /** Records whether the outcome of {@code production} is the outcome of {@code reference} for {@code input}. */
    private void compare(String input, Callable<Object> reference, Callable<Object> production) {
        String expected = outcome(reference);
        String actual = outcome(production);
        compared++;
        seen.add(expected.startsWith(THROWS) ? THROWS : expected);
        if (!expected.equals(actual)) {
            mismatches.add(input + ": the reference gives " + expected + ", SourcePatterns gives " + actual);
        }
    }

    /**
     * Asserts that no outcome differed, naming the first ten that did, and that the inputs reached each of
     * {@code outcomes}, so that a generation that reaches too little cannot pass unnoticed.
     */
    private void assertAgreement(List<String> outcomes) {
        assertEquals(List.of(), mismatches.subList(0, Math.min(10, mismatches.size())),
                mismatches.size() + " of " + compared + " outcomes differ");
        assertTrue(seen.containsAll(outcomes), "the inputs reach " + outcomes + " among " + seen);
    }

    /** The value {@code call} returns, or the type and message of the exception it throws. */
    private static String outcome(Callable<Object> call) {
        try {
            return String.valueOf(call.call());
        } catch (Exception failure) {
            return THROWS + failure.getClass().getName() + ": " + failure.getMessage();
        }
    }

    /** The written patterns, then three generated ones at a time: a plain, an {@code %ant[...]} and a regular one. */
    private static List<String> patterns() {
        Random random = new Random(SEED);
        List<String> patterns = new ArrayList<>(WRITTEN_PATTERNS);
        for (int count = 0; count < 150; count++) {
            String padding = pick(random, PADDING);
            patterns.add(padding + antPattern(random) + pick(random, PADDING));
            patterns.add(padding + "%ant[" + antPattern(random) + "]");
            patterns.add("%regex[" + expression(random) + "]" + padding);
        }
        return List.copyOf(patterns);
    }

    /** An Ant pattern of one to four names, maybe led and ended by a separator. */
    private static String antPattern(Random random) {
        StringBuilder pattern = new StringBuilder(pick(random, ENDS)).append(pick(random, PATTERN_NAMES));
        for (int names = random.nextInt(4); names > 0; names--) {
            pattern.append(pick(random, PATTERN_SEPARATORS)).append(pick(random, PATTERN_NAMES));
        }
        return pattern.append(pick(random, ENDS)).toString();
    }

    /** A regular expression of one to four pieces. */
    private static String expression(Random random) {
        StringBuilder expression = new StringBuilder();
        for (int pieces = 1 + random.nextInt(4); pieces > 0; pieces--) {
            expression.append(pick(random, REGEX_PIECES));
        }
        return expression.toString();
    }

    /** The files of the tree, then paths of one to four generated names. */
    private static List<String> paths() {
        Random random = new Random(SEED + 1);
        List<String> paths = new ArrayList<>(TREE);
        for (int count = 0; count < 100; count++) {
            List<String> names = new ArrayList<>();
            for (int depth = 1 + random.nextInt(4); depth > 0; depth--) {
                names.add(pick(random, PATH_NAMES));
            }
            paths.add(String.join("/", names));
        }
        return List.copyOf(paths);
    }

    /** {@code count} selections of up to two includes and up to two excludes each, generated from {@code seed}. */
    private static List<Selection> selections(long seed, int count) {
        Random random = new Random(seed);
        List<Selection> selections = new ArrayList<>();
        for (int at = 0; at < count; at++) {
            selections.add(new Selection(some(random, WIDE_INCLUDES), some(random, NARROW_EXCLUDES)));
        }
        return List.copyOf(selections);
    }

    /** Up to two patterns, each drawn from {@code preferred} or from every pattern compared, as likely as not. */
    private static List<String> some(Random random, List<String> preferred) {
        List<String> some = new ArrayList<>();
        for (int count = random.nextInt(3); count > 0; count--) {
            some.add(pick(random, random.nextBoolean() ? preferred : PATTERNS));
        }
        return List.copyOf(some);
    }

    /** One of {@code choices}. */
    private static String pick(Random random, List<String> choices) {
        return choices.get(random.nextInt(choices.size()));
    }

    /**
     * The includes and excludes of one execution, held anew, by the reference or by {@link SourcePatterns}, for each
     * question, so that an exception thrown while they are held is compared as well.
     */
    private record Selection(List<String> includes, List<String> excludes) {

        /** These patterns, held by the reference for {@code separator}. */
        ReferenceSourcePatterns reference(char separator) {
            return new ReferenceSourcePatterns(includes, excludes, separator);
        }

        /** These patterns, held by {@link SourcePatterns} for {@code separator}. */
        SourcePatterns production(char separator) {
            return new SourcePatterns(includes, excludes, separator);
        }
    }
}
