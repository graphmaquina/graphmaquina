package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Which declarations of a round the processor reads, and in which order: every facade of the round, then every set,
 * each in the order javac lists the round's elements, which follows the order of their sources, which the harness
 * sorts. The companion names are reserved in that order, so the first of two declarations that claim one name keeps
 * it, and the declarations are then built in that order, so their diagnostics come in it too.
 */
class RoundDeclarationOrderTest {

    private static final String ARCHIVE = """
            package fx;
            import io.github.graphmaquina.annotation.StateGraphs;
            import io.github.graphmaquina.api.Machines;
            @StateGraphs
            public interface Archive extends Machines {
                Docs docs();
            }
            """;

    private static final String SHELF = """
            package fx;
            import io.github.graphmaquina.annotation.StateGraphs;
            import io.github.graphmaquina.api.Machines;
            @StateGraphs
            public interface Shelf extends Machines {
                String label();
            }
            """;

    /**
     * Two machines and four sets of one round: {@code Pages} is refused, its companion name taken by a type, before
     * any set reserves a name, and {@code Library} reserves its companion's name before {@code _Library} claims it;
     * then {@code Pages}, whose policy is blank, is still built, before the sets, of which {@code Shelf} declares an
     * accessor that returns no machine. Only the companions of {@code Docs} and of {@code Archive}, the one set that
     * holds no refused machine, are written.
     */
    @Test
    void reservesAndBuildsTheFacadesThenTheSetsOfARoundInTheOrderOfTheirSources() throws IOException {
        Map<String, String> sources = Fixtures.withLibrary(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY,
                Fixtures.LIBRARY);
        sources.put("fx.Pages", Fixtures.PAGES.replace("otherwise = \"a page is finished\"", "otherwise = \" \""));
        sources.put("fx.Archive", ARCHIVE);
        sources.put("fx._Library", Fixtures.LIBRARY.replace("interface Library ", "interface _Library "));
        sources.put("fx.PagesRuntime", "package fx;\npublic final class PagesRuntime { }\n");
        sources.put("fx.Shelf", SHELF);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(List.of(
                "ERROR Pages.java:11:8 [" + DiagnosticCode.SG603 + "] the generated runtime fx.PagesRuntime for"
                        + " fx.Pages conflicts with existing type fx.PagesRuntime",
                "ERROR _Library.java:5:8 [" + DiagnosticCode.SG603 + "] the generated runtime fx.LibraryRuntime is"
                        + " claimed by both fx.Library and fx._Library",
                "ERROR Pages.java:10:51 [" + DiagnosticCode.SG103 + "] the policy of OPEN has a blank reason",
                "ERROR Shelf.java:6:12 [" + DiagnosticCode.SG510 + "] label must be a no-argument accessor returning"
                        + " a @GenerateGraphApi interface"),
                result.located(), result.describe());
        assertEquals(List.of("fx.ArchiveRuntime.java", "fx.DocsRuntime.java"), generated(result));
    }

    /** The qualified names of the sources the processor wrote, sorted. */
    private static List<String> generated(Compilation.Result result) throws IOException {
        try (Stream<Path> walk = Files.walk(result.output())) {
            return walk.filter(Files::isRegularFile)
                    .map((Path file) -> result.output().relativize(file).toString().replace('/', '.'))
                    .sorted()
                    .toList();
        }
    }
}
