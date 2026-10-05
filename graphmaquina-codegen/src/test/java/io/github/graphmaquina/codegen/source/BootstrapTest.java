package io.github.graphmaquina.codegen.source;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.codegen.GenerationRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies what the one walk of the original sources exposes besides the model: every file it read, the path that
 * file stands at below its source root, and the top-level types it declares. That is what the publication checks
 * a generated type against, so it must reach every source the build compiles, the ones behind symbolic links
 * included, exactly as the walk that produced the model reached them.
 */
class BootstrapTest {

    private static final String HELPERS = """
        package fx;
        public class Helpers {}
        final class DocRefs {}
        """;

    @TempDir
    Path project;

    /** A file of a source directory stands at its path below that directory; only its top-level types count. */
    @Test
    void exposesEverySourceItReadWithItsPathAndItsTopLevelTypes() {
        Path root = project.resolve("src");
        SourceFiles.write(root, Map.of("fx.Helpers", HELPERS,
                "fx.Order", "package fx;\npublic class Order {\n    static final class Line {}\n}\n"));

        List<Bootstrap.OriginalSource> sources = Bootstrap.read(request(List.of(root))).sources();

        assertEquals(List.of(
                new Bootstrap.OriginalSource(root.resolve("fx/Helpers.java"), "src/fx/Helpers.java",
                        "fx/Helpers.java", List.of("fx.Helpers", "fx.DocRefs")),
                new Bootstrap.OriginalSource(root.resolve("fx/Order.java"), "src/fx/Order.java", "fx/Order.java",
                        List.of("fx.Order"))),
                sources);
    }

    /** A source root that is itself a link is walked through, as javac reads the files behind it. */
    @Test
    void exposesTheDeclarationsUnderASourceRootThatIsASymbolicLink() throws IOException {
        Path shared = project.resolve("shared");
        SourceFiles.write(shared, Map.of("fx.Helpers", HELPERS));
        Path root = SymbolicLinks.create(project.resolve("src"), shared);

        List<Bootstrap.OriginalSource> sources = Bootstrap.read(request(List.of(root))).sources();

        assertEquals(List.of(new Bootstrap.OriginalSource(root.resolve("fx/Helpers.java"), "src/fx/Helpers.java",
                "fx/Helpers.java", List.of("fx.Helpers", "fx.DocRefs"))), sources);
    }

    /** A package directory that is a link inside a real source root is walked through as well. */
    @Test
    void exposesTheDeclarationsInAPackageDirectoryThatIsASymbolicLink() throws IOException {
        Path shared = project.resolve("shared");
        SourceFiles.write(shared, Map.of("fx.Helpers", HELPERS));
        Path root = Files.createDirectories(project.resolve("src"));
        SymbolicLinks.create(root.resolve("fx"), shared.resolve("fx"));

        List<Bootstrap.OriginalSource> sources = Bootstrap.read(request(List.of(root))).sources();

        assertEquals(List.of(new Bootstrap.OriginalSource(root.resolve("fx/Helpers.java"), "src/fx/Helpers.java",
                "fx/Helpers.java", List.of("fx.Helpers", "fx.DocRefs"))), sources);
    }

    /**
     * A link back to a directory that contains it has no end to walk. The walk skips it, as Gradle skips it, and
     * still exposes every declaration it reached on its way.
     */
    @Test
    void skipsASymbolicLinkThatLeadsBackToADirectoryContainingIt() throws IOException {
        Path root = project.resolve("src");
        SourceFiles.write(root, Map.of("fx.Helpers", HELPERS));
        SymbolicLinks.create(root.resolve("fx/again"), root);

        List<Bootstrap.OriginalSource> sources = Bootstrap.read(request(List.of(root))).sources();

        assertEquals(List.of(new Bootstrap.OriginalSource(root.resolve("fx/Helpers.java"), "src/fx/Helpers.java",
                "fx/Helpers.java", List.of("fx.Helpers", "fx.DocRefs"))), sources);
    }

    /**
     * A file the request names itself has no source root the request names, so it stands where its package
     * declaration puts it: below a root, in the directories of its package, under its own file name. That is where
     * javac and every build look for it, whatever directory it lies in.
     */
    @Test
    void placesAListedSourceWhereItsPackageDeclarationPutsIt() throws IOException {
        Path legacy = SourceFiles.write(project.resolve("src"), Map.of("legacy.fx.DocsGraph",
                "package legacy.fx;\npublic final class DocsGraph {}\n")).get(0);
        Path misplaced = SourceFiles.write(project.resolve("src"), Map.of("weird.Odd",
                "package fx;\nfinal class Odd {}\n")).get(0);
        Path loose = Files.writeString(Files.createDirectories(project.resolve("loose")).resolve("Loose.java"),
                "final class Loose {}\n", StandardCharsets.UTF_8);

        List<Bootstrap.OriginalSource> sources = Bootstrap.read(request(List.of(legacy, misplaced, loose))).sources();

        assertEquals(List.of("legacy/fx/DocsGraph.java", "fx/Odd.java", "Loose.java"),
                sources.stream().map(Bootstrap.OriginalSource::path).toList());
        assertEquals(List.of(List.of("legacy.fx.DocsGraph"), List.of("fx.Odd"), List.of("Loose")),
                sources.stream().map(Bootstrap.OriginalSource::types).toList());
    }

    /** The sources are read in the order the request names them, the files of each directory in sorted order. */
    @Test
    void readsTheSourcesInTheOrderTheRequestNamesThemEachDirectorySorted() {
        Path first = project.resolve("first");
        Path second = project.resolve("second");
        SourceFiles.write(first, Map.of("zz.Last", "package zz;\nfinal class Last {}\n",
                "aa.First", "package aa;\nfinal class First {}\n"));
        SourceFiles.write(second, Map.of("bb.Middle", "package bb;\nfinal class Middle {}\n"));

        List<Bootstrap.OriginalSource> sources = Bootstrap.read(request(List.of(second, first))).sources();

        assertEquals(List.of("second/bb/Middle.java", "first/aa/First.java", "first/zz/Last.java"),
                sources.stream().map(Bootstrap.OriginalSource::origin).toList());
    }

    /** A request over {@code sources} whose outputs share nothing with them. */
    private GenerationRequest request(List<Path> sources) {
        return new GenerationRequest(project, sources, SourceFiles.classpath(), project.resolve("generated"),
                project.resolve("processor-generated"), project.resolve("classes"), project.resolve("work"),
                "fx:test:main");
    }
}
