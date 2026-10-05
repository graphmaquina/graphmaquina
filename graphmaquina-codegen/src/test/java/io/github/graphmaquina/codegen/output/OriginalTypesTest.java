package io.github.graphmaquina.codegen.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.source.SourceFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies which original sources the check for generated types declared by hand reads: every {@code .java}
 * file the build compiles, the ones reached through symbolic links included, exactly as the generator's own walk
 * of the sources reaches them.
 */
class OriginalTypesTest {

    private static final String HELPERS = """
        package fx;
        public class Helpers {}
        final class DocRefs {}
        """;

    @TempDir
    Path project;

    /** A source root that is itself a link is walked through, as javac reads the files behind it. */
    @Test
    void findsADeclarationUnderASourceRootThatIsASymbolicLink() throws IOException {
        Path shared = project.resolve("shared");
        SourceFiles.write(shared, Map.of("fx.Helpers", HELPERS));
        Path root = SymbolicLinks.create(project.resolve("src"), shared);

        Map<String, String> declaring = OriginalTypes.declaring(request(root), Set.of("fx.DocRefs"));

        assertEquals(Set.of("fx.DocRefs"), declaring.keySet());
        assertTrue(declaring.get("fx.DocRefs").endsWith("Helpers.java"), declaring.toString());
    }

    /** A package directory that is a link inside a real source root is walked through as well. */
    @Test
    void findsADeclarationInAPackageDirectoryThatIsASymbolicLink() throws IOException {
        Path shared = project.resolve("shared");
        SourceFiles.write(shared, Map.of("fx.Helpers", HELPERS));
        Path root = Files.createDirectories(project.resolve("src"));
        SymbolicLinks.create(root.resolve("fx"), shared.resolve("fx"));

        Map<String, String> declaring = OriginalTypes.declaring(request(root), Set.of("fx.DocRefs"));

        assertEquals(Set.of("fx.DocRefs"), declaring.keySet());
        assertTrue(declaring.get("fx.DocRefs").endsWith("Helpers.java"), declaring.toString());
    }

    /** A link back to a directory that contains it has no end to walk, so it is reported, naming the link. */
    @Test
    void reportsASymbolicLinkThatLeadsBackToADirectoryContainingIt() throws IOException {
        Path root = project.resolve("src");
        SourceFiles.write(root, Map.of("fx.Helpers", HELPERS));
        Path loop = SymbolicLinks.create(root.resolve("fx/again"), root);

        UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                () -> OriginalTypes.declaring(request(root), Set.of("fx.DocRefs")));

        assertEquals("cannot read the original sources under " + root + ": the symbolic link " + loop
                + " leads back to a directory that contains it", failure.getMessage());
    }

    /** A request over {@code root} whose outputs share nothing with it. */
    private GenerationRequest request(Path root) {
        return new GenerationRequest(project, List.of(root), List.of(), project.resolve("generated"),
                project.resolve("processor-generated"), project.resolve("classes"), project.resolve("work"),
                "fx:test:main");
    }
}
