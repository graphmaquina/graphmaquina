package io.github.graphmaquina.codegen.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.GenerationResult;
import io.github.graphmaquina.codegen.StateGraphCodegen;
import io.github.graphmaquina.codegen.ToolVersion;
import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import io.github.graphmaquina.codegen.source.SourceFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that one generation is published as a coherent set: the manifest describes exactly the files on disk,
 * a run that changes nothing rewrites the same bytes, deleting a reference retires the file it owned, a file the
 * generation does not own is left alone, and a type that would overwrite an original source is refused.
 */
class GenerationOutputTest {

    @TempDir
    Path directory;

    private static final Map<String, String> ORIGINALS = originals();

    private static Map<String, String> originals() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocStatus", """
            package fx;
            public enum DocStatus { DRAFT, PUBLISHED }
            """);
        sources.put("fx.Doc", """
            package fx;
            import io.github.graphmaquina.annotation.GraphProperty;
            public class Doc {
                @GraphProperty
                private DocStatus status;
            }
            """);
        sources.put("fx.Docs", """
            package fx;
            import io.github.graphmaquina.annotation.GenerateGraphApi;
            import io.github.graphmaquina.api.Machine;
            @GenerateGraphApi
            public interface Docs extends Machine<Doc, DocStatus> {}
            """);
        sources.put("fx.DocActions", """
            package fx;
            import io.github.graphmaquina.annotation.GraphAction;
            public class DocActions {
                @GraphAction
                public Doc publish(Doc doc) { return doc; }
            }
            """);
        return Map.copyOf(sources);
    }

    @Test
    void publishesEverySourceAndAManifestThatDescribesThem() throws IOException {
        SourceFiles.write(sources(), ORIGINALS);
        GenerationResult result = StateGraphCodegen.generate(request());

        assertEquals(directory.resolve("work").resolve("annotation-manifest.json").toAbsolutePath(),
                result.annotationManifest());
        assertEquals(List.of("fx/DocActionsRefs.java", "fx/DocRefs.java", "fx/DocsGraph.java"),
                result.generatedSources().stream().map(file -> output().relativize(file).toString())
                        .filter(name -> name.startsWith("fx")).sorted().toList());

        CompileManifest manifest = ManifestCodec.readCompile(result.annotationManifest());
        assertEquals(1, manifest.schemaVersion());
        assertEquals(ToolVersion.current(), manifest.toolVersion());
        assertEquals("io.github.graphmaquina:test:main", manifest.local().compilationId());
        assertEquals(List.of(), manifest.dependencies());
        assertEquals(directory.resolve("work").resolve("processor-inventory.json").toAbsolutePath().toString(),
                manifest.processorInventory());
        assertTrue(manifest.ownedSources().contains("fx/DocsGraph.java"), manifest.ownedSources().toString());
        assertTrue(manifest.ownedSources().stream().anyMatch(
                owned -> owned.startsWith("io/github/graphmaquina/generated/bootstrap/GraphCompilation_")),
                manifest.ownedSources().toString());
        assertTrue(manifest.local().annotationTypes().contains("fx.DocsGraph.Rows"),
                manifest.local().annotationTypes().toString());
        assertEquals(published(), manifest.ownedSources().stream().sorted().toList());
    }

    @Test
    void rewritesTheSameBytesWhenNothingChanged() throws IOException {
        SourceFiles.write(sources(), ORIGINALS);
        StateGraphCodegen.generate(request());
        Map<String, String> first = contents();

        StateGraphCodegen.generate(request());

        assertEquals(first, contents());
    }

    @Test
    void retiresTheSourcesOfADeletedReferenceAndKeepsWhatItDoesNotOwn() throws IOException {
        SourceFiles.write(sources(), ORIGINALS);
        StateGraphCodegen.generate(request());
        assertTrue(Files.isRegularFile(output().resolve("fx/DocActionsRefs.java")));
        Path manual = output().resolve("fx/Handwritten.java");
        Files.writeString(manual, "package fx;\npublic final class Handwritten {}\n", StandardCharsets.UTF_8);
        String manualBefore = Files.readString(manual, StandardCharsets.UTF_8);
        Path original = sources().resolve("fx/Docs.java");
        String originalBefore = Files.readString(original, StandardCharsets.UTF_8);

        Files.delete(sources().resolve("fx/DocActions.java"));
        StateGraphCodegen.generate(request());

        assertFalse(Files.exists(output().resolve("fx/DocActionsRefs.java")));
        assertTrue(Files.isRegularFile(output().resolve("fx/DocsGraph.java")));
        assertEquals(manualBefore, Files.readString(manual, StandardCharsets.UTF_8));
        assertEquals(originalBefore, Files.readString(original, StandardCharsets.UTF_8));
        assertFalse(ManifestCodec.readCompile(request().workDirectory().resolve("annotation-manifest.json"))
                .ownedSources().contains("fx/DocActionsRefs.java"));
    }

    @Test
    void refusesAGeneratedTypeAnOriginalSourceAlreadyDeclares() throws IOException {
        SourceFiles.write(sources(), ORIGINALS);
        SourceFiles.write(sources(), Map.of("fx.DocsGraph", "package fx;\npublic final class DocsGraph {}\n"));

        CodegenException failure =
                assertThrows(CodegenException.class, () -> StateGraphCodegen.generate(request()));

        assertEquals("SG603", failure.code());
        assertTrue(failure.getMessage().contains("fx.DocsGraph"), failure.getMessage());
        assertTrue(failure.getMessage().contains(sources().resolve("fx/DocsGraph.java").toString()),
                failure.getMessage());
        assertFalse(Files.exists(directory.resolve("work").resolve("annotation-manifest.json")));
    }

    @Test
    void deletesTheManifestWhenTheRunFails() throws IOException {
        SourceFiles.write(sources(), ORIGINALS);
        StateGraphCodegen.generate(request());
        assertTrue(Files.isRegularFile(directory.resolve("work").resolve("annotation-manifest.json")));

        SourceFiles.write(sources(), Map.of("fx.DocsGraph", "package fx;\npublic final class DocsGraph {}\n"));
        assertThrows(CodegenException.class, () -> StateGraphCodegen.generate(request()));

        assertFalse(Files.exists(directory.resolve("work").resolve("annotation-manifest.json")));
    }

    @Test
    void retiresDeletedReferencesAfterRepeatedFailedGenerations() throws IOException {
        SourceFiles.write(sources(), ORIGINALS);
        StateGraphCodegen.generate(request());
        Path manual = output().resolve("fx/Handwritten.java");
        String handwritten = "package fx; public final class Handwritten {}\n";
        Files.writeString(manual, handwritten);

        SourceFiles.write(sources(), Map.of("fx.DocsGraph", "package fx; public final class DocsGraph {}"));
        for (int attempt = 0; attempt < 2; attempt++) {
            CodegenException failure = assertThrows(CodegenException.class,
                    () -> StateGraphCodegen.generate(request()));
            assertEquals("SG603", failure.code());
            assertFalse(Files.exists(request().workDirectory().resolve("annotation-manifest.json")));
        }
        Files.delete(sources().resolve("fx/DocsGraph.java"));
        Files.delete(sources().resolve("fx/DocActions.java"));
        GenerationResult result = StateGraphCodegen.generate(request());

        assertFalse(Files.exists(output().resolve("fx/DocActionsRefs.java")));
        assertEquals(handwritten, Files.readString(manual));
        assertEquals(published().stream().filter(name -> !name.equals("fx/Handwritten.java")).toList(),
                ManifestCodec.readCompile(result.annotationManifest()).ownedSources().stream().sorted().toList());
    }

    @Test
    void refusesToOverwriteAnUnownedFileInTheGeneratedSourceDirectory() throws IOException {
        SourceFiles.write(sources(), ORIGINALS);
        Path manual = output().resolve("fx/DocActionsRefs.java");
        Files.createDirectories(manual.getParent());
        String handwritten = "package fx; public final class DocActionsRefs {}\n";
        Files.writeString(manual, handwritten);

        for (int attempt = 0; attempt < 2; attempt++) {
            CodegenException failure = assertThrows(CodegenException.class,
                    () -> StateGraphCodegen.generate(request()));
            assertEquals("SG603", failure.code());
            assertEquals(handwritten, Files.readString(manual));
        }
    }

    @Test
    void refusesAGeneratedTypeAlreadyPresentInADependency() throws IOException {
        SourceFiles.write(sources(), ORIGINALS);
        Path work = directory.resolve("dependency");
        Path jar = SourceFiles.compileToJar(work,
                Map.of("fx.DocsGraph", "package fx; public final class DocsGraph {}"),
                directory.resolve("dependency.jar"));
        for (Path dependency : List.of(jar, work.resolve("jar-classes"))) {
            GenerationRequest original = request();
            List<Path> classpath = new ArrayList<>(original.classpath());
            classpath.add(dependency);
            GenerationRequest withDependency = new GenerationRequest(original.projectDirectory(), original.sources(),
                    classpath, original.sourcesOutput(), original.processorSourcesOutput(), original.classesOutput(),
                    original.workDirectory(), original.compilationId());
            CodegenException failure = assertThrows(CodegenException.class,
                    () -> StateGraphCodegen.generate(withDependency));
            assertEquals("SG603", failure.code());
            assertTrue(failure.getMessage().contains("fx.DocsGraph"));
            assertTrue(failure.getMessage().contains(dependency.toString()));
        }
    }

    private GenerationRequest request() {
        return new GenerationRequest(directory, List.of(sources()), SourceFiles.classpath(),
                directory.resolve("generated"), directory.resolve("processor-generated"),
                directory.resolve("classes"), directory.resolve("work"), "io.github.graphmaquina:test:main");
    }

    private Path sources() {
        return directory.resolve("sources");
    }

    private Path output() {
        return directory.resolve("generated").toAbsolutePath();
    }

    /** Every published file, relative to the generated source root, with forward slashes. */
    private List<String> published() throws IOException {
        try (Stream<Path> walk = Files.walk(output())) {
            return walk.filter(Files::isRegularFile)
                    .map(file -> output().relativize(file).toString().replace('\\', '/'))
                    .sorted().toList();
        }
    }

    /** Every published file with its text, so two runs can be compared as a whole. */
    private Map<String, String> contents() throws IOException {
        Map<String, String> contents = new TreeMap<>();
        for (String name : published()) {
            try {
                contents.put(name, Files.readString(output().resolve(name), StandardCharsets.UTF_8));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
        contents.put("annotation-manifest.json", Files.readString(
                directory.resolve("work").resolve("annotation-manifest.json"), StandardCharsets.UTF_8));
        return contents;
    }
}
