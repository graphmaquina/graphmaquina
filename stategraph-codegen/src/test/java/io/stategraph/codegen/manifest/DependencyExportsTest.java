package io.stategraph.codegen.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.stategraph.codegen.CodegenException;
import io.stategraph.codegen.ToolVersion;
import io.stategraph.codegen.model.GenerationModel;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies how the published indexes of a classpath are aggregated: a directory and an archive are read the same
 * way, only the promoted name is read, an entry that carries nothing is skipped and an index written by another
 * tool version is refused instead of being interpreted.
 */
class DependencyExportsTest {

    @TempDir
    Path directory;

    private static final GenerationModel EMPTY = new GenerationModel(List.of(), List.of());

    @Test
    void readsThePublishedIndexOfADirectoryAndOfAnArchiveInCompilationOrder() throws IOException {
        Path classes = published(directory.resolve("classes"), "exports.json",
                index(ToolVersion.current(), "io.stategraph:beta:main"));
        Path archive = archived(directory.resolve("alpha.jar"), "META-INF/stategraph/exports.json",
                index(ToolVersion.current(), "io.stategraph:alpha:main"));

        List<ExportIndex> indexes = DependencyExports.read(List.of(classes, archive,
                directory.resolve("absent"), directory.resolve("classes/META-INF/stategraph/exports.json")));

        assertEquals(List.of("io.stategraph:alpha:main", "io.stategraph:beta:main"),
                indexes.stream().map(ExportIndex::compilationId).toList());
        assertEquals(List.of("fx.OrdersGraph.Row"), indexes.get(0).annotationTypes());
    }

    @Test
    void ignoresTheIndexAProcessorHasNotPromotedYet() throws IOException {
        Path classes = published(directory.resolve("classes"), "pending-exports.json",
                index(ToolVersion.current(), "io.stategraph:pending:main"));

        assertEquals(List.of(), DependencyExports.read(List.of(classes)));
    }

    @Test
    void refusesAnIndexWrittenByAnotherToolVersion() throws IOException {
        Path classes = published(directory.resolve("classes"), "exports.json",
                index("0.0.1-OTHER", "io.stategraph:old:main"));

        CodegenException failure =
                assertThrows(CodegenException.class, () -> DependencyExports.read(List.of(classes)));

        assertEquals("SG602", failure.code());
        assertTrue(failure.getMessage().contains(classes.toString()), failure.getMessage());
        assertTrue(failure.getMessage().contains("0.0.1-OTHER"), failure.getMessage());
    }

    @Test
    void refusesAnIndexOfAnotherSchema() throws IOException {
        Path classes = Files.createDirectories(directory.resolve("classes/META-INF/stategraph"));
        Files.writeString(classes.resolve("exports.json"),
                "{\"schemaVersion\": 99, \"toolVersion\": \"" + ToolVersion.current()
                    + "\", \"compilationId\": \"x\", \"model\": {\"graphs\": [], \"symbols\": []},"
                    + " \"annotationTypes\": []}\n", StandardCharsets.UTF_8);

        CodegenException failure = assertThrows(CodegenException.class,
                () -> DependencyExports.read(List.of(directory.resolve("classes"))));

        assertEquals("SG602", failure.code());
        assertTrue(failure.getMessage().contains(directory.resolve("classes").toString()),
                failure.getMessage());
    }

    private static ExportIndex index(String toolVersion, String compilationId) {
        return new ExportIndex(1, toolVersion, compilationId, EMPTY, List.of("fx.OrdersGraph.Row"));
    }

    /** Writes {@code index} under {@code root} as the named file of the published metadata directory. */
    private static Path published(Path root, String name, ExportIndex index) throws IOException {
        Path metadata = Files.createDirectories(root.resolve("META-INF/stategraph"));
        ManifestCodec.writeExports(metadata.resolve(name), index);
        return root;
    }

    /** Packs {@code index} into a jar at {@code jar}, under the entry {@code name}. */
    private static Path archived(Path jar, String name, ExportIndex index) throws IOException {
        Path staged = Files.createDirectories(jar.getParent().resolve("staged")).resolve("exports.json");
        ManifestCodec.writeExports(staged, index);
        try (OutputStream target = Files.newOutputStream(jar);
                JarOutputStream out = new JarOutputStream(target)) {
            out.putNextEntry(new JarEntry(name));
            out.write(Files.readAllBytes(staged));
            out.closeEntry();
        }
        return jar;
    }
}
