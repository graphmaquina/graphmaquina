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
 * declares them. Reads the repository that the build names in {@code graphmaquina.repository}.
 */
class DiagnosticCodeTest {

    private static final Pattern CODE = Pattern.compile("SG\\d{3}");
    /** A code taken from the enum, the one way a line of code may name a code. */
    private static final Pattern FROM_ENUM = Pattern.compile("DiagnosticCode\\.SG\\d{3}");
    private static final Pattern ROW = Pattern.compile("^\\| `(SG\\d{3})` \\|", Pattern.MULTILINE);
    /** The lines Checkstyle's LineLength also reads as comments: Javadoc, line and block comments. */
    private static final Pattern COMMENT = Pattern.compile("^\\s*(\\*|//|/\\*)");
    /** Numbers once used and then removed, from the repository's history; they have no constant to name. */
    private static final Set<Integer> RETIRED = Set.of(101, 104, 501, 506, 609);
    private static final List<String> SOURCE_TREES = List.of("src/main/java", "src/test/java");
    private static final String ENUM_SOURCE =
            "graphmaquina-core/src/main/java/io/github/graphmaquina/annotation/internal/DiagnosticCode.java";
    private static final Set<String> REGISTERED = registered();

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
        assertEquals(List.of(), names.stream().filter(name -> !CODE.matcher(name).matches()).toList());
        assertEquals(List.of(), names.stream().filter(name -> RETIRED.contains(number(name))).toList());
    }

    @Test
    void everyJavaSourceNamesCodesOnlyThroughTheEnumAndMentionsOnlyRegisteredOnes() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path source : javaSources()) {
            List<String> lines = Files.readAllLines(source, UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                offences.addAll(offences(repository().relativize(source) + ":" + (index + 1), lines.get(index)));
            }
        }
        assertEquals(List.of(), offences);
    }

    private static List<String> offences(String where, String line) {
        List<String> found = new ArrayList<>();
        String outsideTheEnum = FROM_ENUM.matcher(line).replaceAll("");
        if (!COMMENT.matcher(line).find() && CODE.matcher(outsideTheEnum).find()) {
            found.add(where + " spells a code: take it from its DiagnosticCode constant");
        }
        CODE.matcher(line).results().map(MatchResult::group).filter(code -> !REGISTERED.contains(code))
                .forEach(code -> found.add(where + " mentions " + code + ", which DiagnosticCode does not hold"));
        return found;
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
            for (Path tree : modules.flatMap(module -> SOURCE_TREES.stream().map(module::resolve))
                    .filter(Files::isDirectory).sorted().toList()) {
                try (Stream<Path> files = Files.walk(tree)) {
                    files.filter(file -> file.toString().endsWith(".java") && !file.equals(declaration)).sorted()
                            .forEach(sources::add);
                }
            }
        }
        return sources;
    }

    private static Path repository() {
        return Path.of(System.getProperty("graphmaquina.repository"));
    }
}
