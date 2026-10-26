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
 * its codes, its numbers follow one another and never reuse a retired one, and no production source of any module
 * spells a code literal or mentions a code it does not hold. Reads the repository that the build names in
 * {@code graphmaquina.repository}.
 */
class DiagnosticCodeTest {

    private static final Pattern CODE = Pattern.compile("SG\\d{3}");
    private static final Pattern LITERAL = Pattern.compile("\"SG\\d{3}\"");
    private static final Pattern ROW = Pattern.compile("^\\| `(SG\\d{3})` \\|", Pattern.MULTILINE);
    /** The lines Checkstyle's LineLength also reads as comments: Javadoc, line and block comments. */
    private static final Pattern COMMENT = Pattern.compile("^\\s*(\\*|//|/\\*)");
    /** Numbers once used and then removed, from the repository's history. */
    private static final Set<String> RETIRED = Set.of("SG101", "SG104", "SG501", "SG506", "SG609");
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
        assertEquals(List.of(), names.stream().filter(RETIRED::contains).toList());
    }

    @Test
    void productionSourcesSpellNoCodeLiteralAndMentionOnlyRegisteredCodes() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path source : productionSources()) {
            List<String> lines = Files.readAllLines(source, UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                offences.addAll(offences(repository().relativize(source) + ":" + (index + 1), lines.get(index)));
            }
        }
        assertEquals(List.of(), offences);
    }

    private static List<String> offences(String where, String line) {
        List<String> found = new ArrayList<>();
        if (!COMMENT.matcher(line).find() && LITERAL.matcher(line).find()) {
            found.add(where + " spells a code literal: name its DiagnosticCode constant");
        }
        CODE.matcher(line).results().map(MatchResult::group).filter(code -> !REGISTERED.contains(code))
                .forEach(code -> found.add(where + " mentions " + code + ", which DiagnosticCode does not hold"));
        return found;
    }

    private static Set<String> registered() {
        Set<String> names = new TreeSet<>();
        for (DiagnosticCode code : DiagnosticCode.values()) {
            names.add(code.name());
        }
        return names;
    }

    /** Every Java source under a {@code src/main/java} directory of a module at the repository's root. */
    private static List<Path> productionSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repository())) {
            for (Path main : modules.map(module -> module.resolve("src/main/java")).filter(Files::isDirectory)
                    .sorted().toList()) {
                try (Stream<Path> files = Files.walk(main)) {
                    files.filter(file -> file.toString().endsWith(".java")).sorted().forEach(sources::add);
                }
            }
        }
        return sources;
    }

    private static Path repository() {
        return Path.of(System.getProperty("graphmaquina.repository"));
    }
}
