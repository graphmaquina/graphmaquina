package io.github.graphmaquina.annotation.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps {@link DiagnosticCode} the one declaration of every diagnostic code: the diagnostics table documents exactly
 * its codes, its numbers follow one another and never reuse a retired one, and no Java source of any module, main or
 * test, names a code except as a constant of the enum or mentions a code it does not hold. A line that is a comment
 * may name a code in prose, but a comment trailing code may not, and the enum's own source is the one file that
 * declares them. Reads the repository that the build names in {@code graphmaquina.repository}, and pins in its own
 * tests what the rule flags and what it passes.
 */
class DiagnosticCodeTest {

    private static final Pattern CODE = Pattern.compile("SG\\d{3}");
    /**
     * A code taken from the enum, the one way a line of code may name a code: the name of the enum starts at the
     * beginning of an identifier, as a qualified name or a bare one does, and no longer name ends in it.
     */
    private static final Pattern FROM_ENUM = Pattern.compile("(?<![\\p{L}\\p{N}_$])DiagnosticCode\\.SG\\d{3}");
    private static final Pattern ROW = Pattern.compile("^\\| `(SG\\d{3})` \\|", Pattern.MULTILINE);
    /**
     * The lines Checkstyle's LineLength also reads as comments: a line that begins with {@code *}, as a Javadoc line
     * does, or with {@code //}, or that opens a block comment which nothing but comment follows on the line. No
     * line inside a text block is read as one, whatever it begins with.
     */
    private static final Pattern COMMENT = Pattern.compile("^\\s*(\\*|//|/\\*(?!.*\\*/\\s*\\S))");
    private static final String TEXT_BLOCK_DELIMITER = "\"\"\"";
    private static final String SPELLS = " spells a code: take it from its DiagnosticCode constant";
    /** Numbers once used and then removed, from the repository's history; they have no constant to name. */
    private static final Set<Integer> RETIRED = Set.of(101, 104, 501, 506, 609);
    private static final List<String> SOURCE_TREES = List.of("src/main/java", "src/test/java");
    private static final String ENUM_SOURCE =
            "graphmaquina-core/src/main/java/io/github/graphmaquina/annotation/internal/DiagnosticCode.java";
    private static final Set<String> REGISTERED = registered();
    /** A registered code, assembled so that this file spells none itself. */
    private static final String HELD = "SG" + "602";
    /** A code the enum does not hold, assembled likewise. */
    private static final String UNHELD = "SG" + "999";

    @Test
    void theDiagnosticsTableDocumentsExactlyTheRegisteredCodes() throws IOException {
        Matcher rows = ROW.matcher(Files.readString(repository().resolve("docs/diagnostics.md"), UTF_8));
        Set<String> documented = new TreeSet<>();
        while (rows.find()) {
            documented.add(rows.group(1));
        }
        assertEquals(REGISTERED, documented);
    }

    @Test
    void codesFollowTheNumericOrderAndNeverTakeARetiredNumber() {
        List<String> names = Arrays.stream(DiagnosticCode.values()).map(Enum::name).toList();
        assertEquals(names.stream().sorted().toList(), names);
        assertEquals(List.of(), names.stream().filter((String name) -> !CODE.matcher(name).matches()).toList());
        assertEquals(List.of(), names.stream().filter((String name) -> RETIRED.contains(number(name))).toList());
    }

    @Test
    void everyJavaSourceNamesCodesOnlyThroughTheEnumAndMentionsOnlyRegisteredOnes() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path source : javaSources()) {
            offences.addAll(offences(repository().relativize(source).toString(), Files.readAllLines(source, UTF_8)));
        }
        assertEquals(List.of(), offences);
    }

    @Test
    void aCodeSpelledOutsideTheEnumIsFlaggedOnEveryLineThatIsNotACommentLine() {
        for (String line : lines(
                "assertEquals(\"#\", code);",
                "assertEquals(\"[#] Bookings\", message);",
                "foo(); // # is named after code",
                "/* note */ assertEquals(\"#\", code);",
                "    package fx; /** Entity of the # fixture. */",
                "assertEquals(XDiagnosticCode.#.name(), code);",
                "assertEquals(\"x\" + DiagnosticCode.# + \"#\", code);")) {
            assertEquals(List.of("here" + SPELLS), offences("here", line, false), line);
        }
    }

    @Test
    void aCodeTakenFromTheEnumAndACommentLineAreNotFlagged() {
        for (String line : lines(
                "assertEquals(\"[\" + DiagnosticCode.# + \"]\", message);",
                "assertEquals(DiagnosticCode.#.name(), code);",
                "assertEquals(io.github.graphmaquina.annotation.internal.DiagnosticCode.#.name(), code);",
                "     * {@code #} is named in prose.",
                "    // # is named in prose.",
                "    /* # is named in prose. */",
                "    /** # is named in prose. */")) {
            assertEquals(List.of(), offences("here", line, false), line);
        }
    }

    @Test
    void everyLineOfATextBlockIsCodeWhateverItStartsWith() {
        List<String> source = lines("String text = \"\"\"", "    * #", "    // #", "    package fx; #", "    \"\"\";",
                "// #");
        assertEquals(List.of("here:2" + SPELLS, "here:3" + SPELLS, "here:4" + SPELLS), offences("here", source));
    }

    @Test
    void aCommentLineNamingTheDelimiterOpensNoTextBlockAndAnUnclosedOneIsReported() {
        assertEquals(List.of("here:2" + SPELLS),
                offences("here", lines("// a text block opens with \"\"\"", "String text = \"#\";")));
        assertEquals(List.of("here ends inside a text block: the guard cannot tell its lines from comments"),
                offences("here", lines("String text = \"\"\"", "    body")));
    }

    @Test
    void aCodeTheEnumDoesNotHoldIsMentionedEvenInACommentLine() {
        assertEquals(List.of("here mentions " + UNHELD + ", which DiagnosticCode does not hold"),
                offences("here", "    // " + UNHELD + " is named in prose.", false));
    }

    /**
     * The offences of the {@code lines} of the source that {@code source} names: a line inside a text block is code,
     * and a source that ends inside one is an offence too, since its comment lines could not be told from its code.
     */
    private static List<String> offences(String source, List<String> lines) {
        List<String> found = new ArrayList<>();
        boolean insideTextBlock = false;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            found.addAll(offences(source + ":" + (index + 1), line, insideTextBlock));
            insideTextBlock ^= togglesTextBlock(line, insideTextBlock);
        }
        if (insideTextBlock) {
            found.add(source + " ends inside a text block: the guard cannot tell its lines from comments");
        }
        return found;
    }

    private static List<String> offences(String where, String line, boolean insideTextBlock) {
        List<String> found = new ArrayList<>();
        boolean comment = !insideTextBlock && COMMENT.matcher(line).find();
        if (!comment && CODE.matcher(FROM_ENUM.matcher(line).replaceAll("")).find()) {
            found.add(where + SPELLS);
        }
        CODE.matcher(line).results().map(MatchResult::group).filter((String code) -> !REGISTERED.contains(code))
                .forEach((String code) -> found.add(where + " mentions " + code
                        + ", which DiagnosticCode does not hold"));
        return found;
    }

    /** Whether the delimiter of a text block occurs an odd number of times on a line that is not a comment line. */
    private static boolean togglesTextBlock(String line, boolean insideTextBlock) {
        boolean code = insideTextBlock || !COMMENT.matcher(line).find();
        return code && (line.split(TEXT_BLOCK_DELIMITER, -1).length - 1) % 2 == 1;
    }

    /** The {@code templates}, each with {@code #} read as a code the enum holds. */
    private static List<String> lines(String... templates) {
        return Arrays.stream(templates).map((String template) -> template.replace("#", HELD)).toList();
    }

    private static int number(String code) {
        return Integer.parseInt(code.substring("SG".length()));
    }

    private static Set<String> registered() {
        Set<String> names = new TreeSet<>();
        for (DiagnosticCode code : DiagnosticCode.values()) {
            names.add(code.name());
        }
        return names;
    }

    /**
     * Every Java source under a {@code src/main/java} or {@code src/test/java} directory of a module at the
     * repository's root, but the source of {@link DiagnosticCode} itself.
     */
    private static List<Path> javaSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        Path declaration = repository().resolve(ENUM_SOURCE);
        try (Stream<Path> modules = Files.list(repository())) {
            for (Path tree : modules.flatMap((Path module) -> SOURCE_TREES.stream().map(module::resolve))
                    .filter(Files::isDirectory).sorted().toList()) {
                try (Stream<Path> files = Files.walk(tree)) {
                    files.filter((Path file) -> file.toString().endsWith(".java") && !file.equals(declaration))
                            .sorted().forEach(sources::add);
                }
            }
        }
        return sources;
    }

    private static Path repository() {
        return Path.of(System.getProperty("graphmaquina.repository"));
    }
}
