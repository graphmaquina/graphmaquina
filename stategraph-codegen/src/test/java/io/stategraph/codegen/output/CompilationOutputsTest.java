package io.stategraph.codegen.output;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.stategraph.codegen.CodegenException;
import io.stategraph.codegen.GenerationRequest;
import io.stategraph.codegen.ToolVersion;
import io.stategraph.codegen.manifest.CompileManifest;
import io.stategraph.codegen.manifest.ExportIndex;
import io.stategraph.codegen.manifest.ManifestCodec;
import io.stategraph.codegen.manifest.OwnedOutputs;
import io.stategraph.codegen.model.GenerationModel;
import io.stategraph.codegen.source.SourceFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the lifecycle a build runs around javac: what {@code prepare} retires before the compiler starts and
 * what {@code complete} seals once it succeeded.
 *
 * <p>Every root is a temporary directory of its own, so nothing here can pass because two outputs happen to
 * share a parent. The inventory is the only authority on what may be deleted: a class file whose name starts
 * like an owned one but which no inventory lists survives {@code prepare} byte for byte.</p>
 */
class CompilationOutputsTest {

    private static final String CARRIER = "io.stategraph.generated.bootstrap.GraphCompilation_0123456789abcdef";
    private static final String CARRIER_SOURCE =
            "io/stategraph/generated/bootstrap/GraphCompilation_0123456789abcdef.java";
    private static final String CARRIER_BINARY =
            "io/stategraph/generated/bootstrap/GraphCompilation_0123456789abcdef.class";
    private static final String PUBLISHED = "META-INF/stategraph/exports.json";
    private static final String PENDING = "META-INF/stategraph/pending-exports.json";
    private static final String INVENTORY = "owned-outputs.json";
    private static final String PENDING_INVENTORY = "owned-outputs.pending.json";

    @TempDir
    Path project;

    @TempDir
    Path sourcesOutput;

    @TempDir
    Path processorSourcesOutput;

    @TempDir
    Path classesOutput;

    @TempDir
    Path workDirectory;

    @Test
    void retiresTheOutputsTheInventoryListsAndLeavesEveryOtherFileAlone() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE));
        ManifestCodec.writeOwned(workDirectory.resolve(INVENTORY), new OwnedOutputs(1,
                List.of("fx/Docs_.java"),
                List.of("fx/OldRefs.class", "fx/OldRefs$Run.class", "fx/Old_.class"),
                List.of(PUBLISHED), List.of()));
        byte[] manualBytes = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 65};
        Path manualClass = write(classesOutput.resolve("fx/OldRefs$Manual.class"), manualBytes);
        write(classesOutput.resolve("fx/OldRefs.class"), manualBytes);
        write(classesOutput.resolve("fx/OldRefs$Run.class"), manualBytes);
        write(classesOutput.resolve("fx/Old_.class"), manualBytes);
        write(classesOutput.resolve(PUBLISHED), "{}".getBytes(StandardCharsets.UTF_8));
        write(classesOutput.resolve(PENDING), "{}".getBytes(StandardCharsets.UTF_8));
        write(processorSourcesOutput.resolve("fx/Docs_.java"), "class Docs_ {}".getBytes(StandardCharsets.UTF_8));

        CompilationOutputs.prepare(request());

        assertFalse(Files.exists(classesOutput.resolve(PUBLISHED)));
        assertFalse(Files.exists(classesOutput.resolve(PENDING)));
        assertFalse(Files.exists(classesOutput.resolve("META-INF")));
        assertFalse(Files.exists(classesOutput.resolve("fx/OldRefs.class")));
        assertFalse(Files.exists(classesOutput.resolve("fx/OldRefs$Run.class")));
        assertFalse(Files.exists(classesOutput.resolve("fx/Old_.class")));
        assertFalse(Files.exists(processorSourcesOutput.resolve("fx/Docs_.java")));
        assertArrayEquals(manualBytes, Files.readAllBytes(manualClass));
    }

    @Test
    void recordsWhatItIsAboutToRetireBeforeItDeletesAnything() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE, "fx/DocsGraph.java"));
        ManifestCodec.writeOwned(workDirectory.resolve(INVENTORY), new OwnedOutputs(1,
                List.of("fx/Docs_.java"), List.of("fx/Old_.class"), List.of(PUBLISHED),
                List.of("io/stategraph/generated/bootstrap/GraphCompilation_fedcba9876543210.class")));

        CompilationOutputs.prepare(request());

        OwnedOutputs pending = ManifestCodec.readOwned(workDirectory.resolve(PENDING_INVENTORY));
        assertEquals(List.of("fx/DocsGraph.java", "fx/Docs_.java", CARRIER_SOURCE),
                pending.sources());
        assertEquals(List.of("fx/Old_.class"), pending.binaries());
        assertEquals(List.of(PUBLISHED), pending.resources());
        assertEquals(List.of("io/stategraph/generated/bootstrap/GraphCompilation_fedcba9876543210.class"),
                pending.excludedBinaries());
    }

    @Test
    void refusesToPrepareACompilationTheGeneratorNeverDescribed() {
        CodegenException failure =
                assertThrows(CodegenException.class, () -> CompilationOutputs.prepare(request()));

        assertEquals("SG602", failure.code());
        assertTrue(failure.getMessage().contains("annotation-manifest.json"), failure.getMessage());
    }

    @Test
    void refusesAnInventoryPathThatEscapesItsRoot() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE));
        ManifestCodec.writeOwned(workDirectory.resolve(INVENTORY), new OwnedOutputs(1,
                List.of(), List.of("../outside.class"), List.of(), List.of()));

        CodegenException failure =
                assertThrows(CodegenException.class, () -> CompilationOutputs.prepare(request()));

        assertEquals("SG603", failure.code());
        assertTrue(failure.getMessage().contains("../outside.class"), failure.getMessage());
    }

    /**
     * A blank entry resolves to the output root itself, so accepting one would hand the whole class output to
     * the step that deletes what the previous cycle owned.
     */
    @Test
    void refusesABlankInventoryPath() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE));
        ManifestCodec.writeOwned(workDirectory.resolve(INVENTORY), new OwnedOutputs(1,
                List.of(), List.of(""), List.of(), List.of()));
        Path kept = write(classesOutput.resolve("fx/Kept.class"), new byte[] {1});

        CodegenException failure =
                assertThrows(CodegenException.class, () -> CompilationOutputs.prepare(request()));

        assertEquals("SG603", failure.code());
        assertTrue(failure.getMessage().contains("claims ''"), failure.getMessage());
        assertTrue(Files.exists(kept), failure.getMessage());
    }

    @Test
    void refusesToCompleteACompilationThatWasNeverPrepared() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE));

        CodegenException failure =
                assertThrows(CodegenException.class, () -> CompilationOutputs.complete(request()));

        assertEquals("SG602", failure.code());
        assertTrue(failure.getMessage().contains(PENDING_INVENTORY), failure.getMessage());
    }

    @Test
    void refusesToCompleteACompilationWhoseProvisionalIndexIsMissing() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE));
        CompilationOutputs.prepare(request());

        CodegenException failure =
                assertThrows(CodegenException.class, () -> CompilationOutputs.complete(request()));

        assertEquals("SG602", failure.code());
        assertTrue(failure.getMessage().contains("pending-exports.json"), failure.getMessage());
    }

    @Test
    void refusesToCompleteACompilationPreparedForAnotherGeneration() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE));
        CompilationOutputs.prepare(request());
        writeManifest(List.of(CARRIER_SOURCE, "fx/DocsGraph.java"));

        CodegenException failure =
                assertThrows(CodegenException.class, () -> CompilationOutputs.complete(request()));

        assertEquals("SG602", failure.code());
        assertTrue(failure.getMessage().contains("another generation"), failure.getMessage());
    }

    @Test
    void sealsTheInventoryOfWhatTheCompilationProducedAndRetiresTheCarrier() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE, "fx/DocsGraph.java"));
        CompilationOutputs.prepare(request());
        compileOwnedAndManualSources();
        byte[] index = writeProvisionalIndex();
        writeProcessorInventory(List.of("fx.Docs_"));

        CompilationOutputs.complete(request());

        OwnedOutputs owned = ManifestCodec.readOwned(workDirectory.resolve(INVENTORY));
        assertEquals(List.of("fx/Docs_.java"), owned.sources());
        assertEquals(new TreeSet<>(List.of("fx/DocsGraph.class", "fx/DocsGraph$Row.class", "fx/Docs_.class")),
                new TreeSet<>(owned.binaries()));
        assertEquals(List.of(PUBLISHED), owned.resources());
        assertEquals(List.of(CARRIER_BINARY), owned.excludedBinaries());

        assertFalse(Files.exists(classesOutput.resolve(CARRIER_BINARY)));
        assertFalse(Files.exists(classesOutput.resolve("io/stategraph")));
        assertTrue(Files.isRegularFile(classesOutput.resolve("fx/Manual.class")));
        assertArrayEquals(index, Files.readAllBytes(classesOutput.resolve(PUBLISHED)));
        assertFalse(Files.exists(classesOutput.resolve(PENDING)));
        assertFalse(Files.exists(workDirectory.resolve(PENDING_INVENTORY)));
        assertEquals(List.of("annotation-manifest.json", INVENTORY, "processor-inventory.json"),
                sorted(names(workDirectory)));
    }

    @Test
    void refusesAClassOutputThatCarriesNoBinaryOfItsOwnCarrier() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE, "fx/DocsGraph.java"));
        CompilationOutputs.prepare(request());
        compileOwnedAndManualSources(List.of("-g:none"));
        byte[] index = writeProvisionalIndex();
        writeProcessorInventory(List.of("fx.Docs_"));

        CodegenException failure =
                assertThrows(CodegenException.class, () -> CompilationOutputs.complete(request()));

        assertEquals("SG602", failure.code());
        assertTrue(failure.getMessage().contains(CARRIER), failure.getMessage());
        assertTrue(failure.getMessage().contains("-g:none"), failure.getMessage());
        assertFalse(Files.exists(classesOutput.resolve(PUBLISHED)));
        assertFalse(Files.exists(workDirectory.resolve(INVENTORY)));
        assertTrue(Files.isRegularFile(workDirectory.resolve(PENDING_INVENTORY)));
        assertTrue(Files.isRegularFile(classesOutput.resolve(CARRIER_BINARY)));
        assertTrue(Files.isRegularFile(classesOutput.resolve("fx/DocsGraph.class")));
        assertTrue(Files.isRegularFile(classesOutput.resolve("fx/Manual.class")));
        assertArrayEquals(index, Files.readAllBytes(classesOutput.resolve(PENDING)));
    }

    @Test
    void retiresTheClassFilesAnInterruptedCompilationLeftBehind() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE, "fx/DocsGraph.java"));
        CompilationOutputs.prepare(request());
        compileOwnedAndManualSources();
        byte[] manualBytes = Files.readAllBytes(classesOutput.resolve("fx/Manual.class"));

        CompilationOutputs.prepare(request());

        assertFalse(Files.exists(classesOutput.resolve(CARRIER_BINARY)), "the carrier of an interrupted cycle");
        assertFalse(Files.exists(classesOutput.resolve("io/stategraph")));
        assertFalse(Files.exists(classesOutput.resolve("fx/DocsGraph.class")));
        assertFalse(Files.exists(classesOutput.resolve("fx/DocsGraph$Row.class")));
        assertArrayEquals(manualBytes, Files.readAllBytes(classesOutput.resolve("fx/Manual.class")));
    }

    /** The request every test runs against: five roots that share no parent but the temporary file system. */
    private GenerationRequest request() {
        return new GenerationRequest(project, List.of(project.resolve("originals")), SourceFiles.classpath(),
                sourcesOutput, processorSourcesOutput, classesOutput, workDirectory, "fx:test:main");
    }

    /** A manifest describing a compilation that owns {@code ownedSources} and writes its inventory in work. */
    private void writeManifest(List<String> ownedSources) throws IOException {
        ExportIndex local = new ExportIndex(1, ToolVersion.current(), "fx:test:main",
                new GenerationModel(List.of(), List.of()), List.of());
        ManifestCodec.writeCompile(workDirectory.resolve("annotation-manifest.json"),
                new CompileManifest(1, ToolVersion.current(), local, List.of(),
                        workDirectory.resolve("processor-inventory.json").toString(), ownedSources));
    }

    /** Compiles the two owned units and one unowned type into the class output, as javac would. */
    private void compileOwnedAndManualSources() {
        compileOwnedAndManualSources(List.of());
    }

    /** The same, under {@code options} of the build's own choosing, such as a debug setting. */
    private void compileOwnedAndManualSources(List<String> options) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocsGraph", """
                package fx;
                public class DocsGraph {
                    public static class Row { }
                }
                """);
        sources.put(CARRIER, """
                package io.stategraph.generated.bootstrap;
                public final class GraphCompilation_0123456789abcdef {
                    private GraphCompilation_0123456789abcdef() { }
                }
                """);
        sources.put("fx.Manual", """
                package fx;
                public class Manual { }
                """);
        List<Path> files = SourceFiles.write(project.resolve("originals"), sources);
        Map<String, String> generated = Map.of("fx.Docs_", """
                package fx;
                public class Docs_ { }
                """);
        List<Path> processorFiles = SourceFiles.write(processorSourcesOutput, generated);
        SourceFiles.Compilation compilation = SourceFiles.compileStrict(
                Stream.concat(files.stream(), processorFiles.stream()).toList(), classesOutput, options);
        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
    }

    /** The provisional index the processor writes through the Filer, and the bytes it must be promoted with. */
    private byte[] writeProvisionalIndex() throws IOException {
        Path pending = classesOutput.resolve(PENDING);
        Files.createDirectories(pending.getParent());
        ManifestCodec.writeExports(pending, new ExportIndex(1, ToolVersion.current(), "fx:test:main",
                new GenerationModel(List.of(), List.of()), List.of()));
        return Files.readAllBytes(pending);
    }

    /** The inventory the processor writes at the absolute path the manifest names. */
    private void writeProcessorInventory(List<String> generatedTypes) throws IOException {
        StringBuilder json = new StringBuilder("{\n  \"schemaVersion\": 1,\n  \"generatedTypes\": [");
        String separator = "\n    ";
        for (String name : generatedTypes) {
            json.append(separator).append('"').append(name).append('"');
            separator = ",\n    ";
        }
        json.append(generatedTypes.isEmpty() ? "]\n}\n" : "\n  ]\n}\n");
        Files.writeString(workDirectory.resolve("processor-inventory.json"), json.toString(),
                StandardCharsets.UTF_8);
    }

    private static Path write(Path file, byte[] content) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, content);
            return file;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString()).toList();
        }
    }

    private static List<String> sorted(List<String> values) {
        return new TreeSet<>(values).stream().toList();
    }
}
