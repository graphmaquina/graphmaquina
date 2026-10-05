package io.github.graphmaquina.codegen.output;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.ToolVersion;
import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import io.github.graphmaquina.codegen.manifest.OwnedOutputs;
import io.github.graphmaquina.codegen.model.GenerationModel;
import io.github.graphmaquina.codegen.source.SourceFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the lifecycle a build runs around javac: what {@code prepare} retires before the compiler starts and
 * what {@code complete} seals once it succeeded.
 *
 * <p>Every root is a temporary directory of its own, so nothing here can pass because two outputs happen to
 * share a parent. The inventory decides what may be deleted: a class file that no inventory lists survives
 * {@code prepare} byte for byte, unless an interrupted cycle left it and it was compiled from one of the units the
 * inventory lists.</p>
 */
class CompilationOutputsTest {

    private static final String CARRIER = "io.github.graphmaquina.generated.bootstrap.GraphCompilation0123456789abcdef";
    private static final String CARRIER_SOURCE =
            "io/github/graphmaquina/generated/bootstrap/GraphCompilation0123456789abcdef.java";
    private static final String CARRIER_BINARY =
            "io/github/graphmaquina/generated/bootstrap/GraphCompilation0123456789abcdef.class";
    private static final String PUBLISHED = "META-INF/graphmaquina/exports.json";
    private static final String PENDING = "META-INF/graphmaquina/pending-exports.json";
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

    @ParameterizedTest
    @ValueSource(strings = {INVENTORY, PENDING_INVENTORY})
    void refusesRetiredInventoriesBeforeChangingAnyOutput(String inventoryName) throws IOException {
        writeManifest(List.of(CARRIER_SOURCE));
        Path inventory = workDirectory.resolve(inventoryName);
        ManifestCodec.writeOwned(inventory, new OwnedOutputs(1,
                List.of("fx/Old.java"), List.of("fx/Old.class"), List.of(PUBLISHED), List.of()));
        Map<Path, byte[]> original = new LinkedHashMap<>();
        original.put(inventory, Files.readAllBytes(inventory));
        original.put(workDirectory.resolve("annotation-manifest.json"),
                Files.readAllBytes(workDirectory.resolve("annotation-manifest.json")));
        for (Path file : List.of(processorSourcesOutput.resolve("fx/Old.java"),
                classesOutput.resolve("fx/Old.class"), classesOutput.resolve("fx/Manual.class"),
                classesOutput.resolve(PUBLISHED), classesOutput.resolve(PENDING))) {
            byte[] bytes = ("unchanged " + file.getFileName()).getBytes(StandardCharsets.UTF_8);
            write(file, bytes);
            original.put(file, bytes);
        }
        List<String> workFiles = sorted(names(workDirectory));

        CodegenException failure = assertThrows(CodegenException.class, () -> CompilationOutputs.prepare(request()));

        assertEquals("SG602", failure.code());
        assertTrue(failure.getMessage().contains("clean"), failure.getMessage());
        for (Map.Entry<Path, byte[]> entry : original.entrySet()) {
            assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()), entry.getKey().toString());
        }
        assertEquals(workFiles, sorted(names(workDirectory)));
    }

    /** Current-schema inventories retire owned files while preserving every unlisted user file. */
    @Test
    void retiresTheOutputsTheInventoryListsAndLeavesEveryOtherFileAlone() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE));
        ManifestCodec.writeOwned(workDirectory.resolve(INVENTORY), new OwnedOutputs(2,
                List.of("fx/Docs_.java"),
                List.of("fx/OldGraph$Transition.class", "fx/OldGraph$Transitions.class",
                        "fx/OldGraph$Policy.class", "fx/OldGraph$Policies.class"),
                List.of(PUBLISHED), List.of()));
        byte[] manualBytes = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 65};
        Path manualClass = write(classesOutput.resolve("fx/MachineGraph$Manual.class"), manualBytes);
        Path userClass = write(classesOutput.resolve("fx/Order.class"), manualBytes);
        write(classesOutput.resolve("fx/OldGraph$Transition.class"), manualBytes);
        write(classesOutput.resolve("fx/OldGraph$Transitions.class"), manualBytes);
        write(classesOutput.resolve("fx/OldGraph$Policy.class"), manualBytes);
        write(classesOutput.resolve("fx/OldGraph$Policies.class"), manualBytes);
        write(classesOutput.resolve(PUBLISHED), "{}".getBytes(StandardCharsets.UTF_8));
        write(classesOutput.resolve(PENDING), "{}".getBytes(StandardCharsets.UTF_8));
        write(processorSourcesOutput.resolve("fx/Docs_.java"), "class Docs_ {}".getBytes(StandardCharsets.UTF_8));

        CompilationOutputs.prepare(request());

        assertFalse(Files.exists(classesOutput.resolve(PUBLISHED)));
        assertFalse(Files.exists(classesOutput.resolve(PENDING)));
        assertFalse(Files.exists(classesOutput.resolve("META-INF")));
        assertFalse(Files.exists(classesOutput.resolve("fx/OldGraph$Transition.class")));
        assertFalse(Files.exists(classesOutput.resolve("fx/OldGraph$Transitions.class")));
        assertFalse(Files.exists(classesOutput.resolve("fx/OldGraph$Policy.class")));
        assertFalse(Files.exists(classesOutput.resolve("fx/OldGraph$Policies.class")));
        assertFalse(Files.exists(processorSourcesOutput.resolve("fx/Docs_.java")));
        assertArrayEquals(manualBytes, Files.readAllBytes(manualClass));
        assertArrayEquals(manualBytes, Files.readAllBytes(userClass));
    }

    @Test
    void recordsWhatItIsAboutToRetireBeforeItDeletesAnything() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE, "fx/DocsGraph.java"));
        ManifestCodec.writeOwned(workDirectory.resolve(INVENTORY), new OwnedOutputs(2,
                List.of("fx/Docs_.java"), List.of("fx/Old_.class"), List.of(PUBLISHED),
                List.of("io/github/graphmaquina/generated/bootstrap/GraphCompilation_fedcba9876543210.class")));

        CompilationOutputs.prepare(request());

        OwnedOutputs pending = ManifestCodec.readOwned(workDirectory.resolve(PENDING_INVENTORY));
        assertEquals(List.of("fx/DocsGraph.java", "fx/Docs_.java", CARRIER_SOURCE),
                pending.sources());
        assertEquals(List.of("fx/Old_.class"), pending.binaries());
        assertEquals(List.of(PUBLISHED), pending.resources());
        assertEquals(List.of("io/github/graphmaquina/generated/bootstrap/GraphCompilation_fedcba9876543210.class"),
                pending.excludedBinaries());
    }

    /**
     * The pending inventory is the evidence an interrupted cycle leaves, so it is replaced whole, never rewritten
     * where it lies: a second link to the previous file still reads what that file said before.
     */
    @Test
    void replacesThePendingInventoryInsteadOfRewritingItInPlace() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE, "fx/DocsGraph.java"));
        Path pending = workDirectory.resolve(PENDING_INVENTORY);
        ManifestCodec.writeOwned(pending,
                new OwnedOutputs(2, List.of(CARRIER_SOURCE), List.of(), List.of(), List.of()));
        byte[] interrupted = Files.readAllBytes(pending);
        Path snapshot = Files.createLink(workDirectory.resolve("snapshot.json"), pending);

        CompilationOutputs.prepare(request());

        assertArrayEquals(interrupted, Files.readAllBytes(snapshot));
        assertEquals(List.of("fx/DocsGraph.java", CARRIER_SOURCE), ManifestCodec.readOwned(pending).sources());
    }

    /** A truncated pending inventory names itself, so the user knows which file stands in the way. */
    @Test
    void namesAPendingInventoryThatIsNotWellFormedJson() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE));
        Path pending = workDirectory.resolve(PENDING_INVENTORY);
        Files.writeString(pending, "{\n  \"schemaVersion\": 2,\n  \"sources\": [\n", StandardCharsets.UTF_8);

        CodegenException failure = assertThrows(CodegenException.class, () -> CompilationOutputs.prepare(request()));

        assertEquals("SG602", failure.code());
        assertTrue(failure.getMessage().startsWith("malformed JSON in " + pending + ": "), failure.getMessage());
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
        ManifestCodec.writeOwned(workDirectory.resolve(INVENTORY), new OwnedOutputs(2,
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
        ManifestCodec.writeOwned(workDirectory.resolve(INVENTORY), new OwnedOutputs(2,
                List.of(), List.of(""), List.of(), List.of()));
        Path kept = write(classesOutput.resolve("fx/Kept.class"), new byte[] {1});

        CodegenException failure =
                assertThrows(CodegenException.class, () -> CompilationOutputs.prepare(request()));

        assertEquals("SG603", failure.code());
        assertTrue(failure.getMessage().contains("claims ''"), failure.getMessage());
        assertTrue(Files.exists(kept), failure.getMessage());
    }

    @Test
    void refusesInventoryPathsThatNormalizeToTheOutputRoot() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE));
        Path kept = write(classesOutput.resolve("fx/Kept.class"), new byte[] {1});
        for (String entry : List.of(".", "fx/..")) {
            ManifestCodec.writeOwned(workDirectory.resolve(INVENTORY), new OwnedOutputs(2,
                    List.of(), List.of(entry), List.of(), List.of()));
            CodegenException failure = assertThrows(CodegenException.class,
                    () -> CompilationOutputs.prepare(request()));
            assertEquals("SG603", failure.code());
            assertTrue(Files.exists(kept));
        }
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
        assertEquals(new TreeSet<>(List.of("fx/DocsGraph.class", "fx/DocsGraph$Transition.class", "fx/Docs_.class")),
                new TreeSet<>(owned.binaries()));
        assertEquals(List.of(PUBLISHED), owned.resources());
        assertEquals(List.of(CARRIER_BINARY), owned.excludedBinaries());

        assertFalse(Files.exists(classesOutput.resolve(CARRIER_BINARY)));
        assertFalse(Files.exists(classesOutput.resolve("io/github/graphmaquina")));
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

    /**
     * {@code -g:none} is not the only setting that leaves the {@code SourceFile} attribute out: a {@code -g:} list
     * without {@code source} does too, and the refusal names that case as well.
     */
    @Test
    void namesEveryDebugSettingThatLeavesTheSourceFileAttributeOut() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE, "fx/DocsGraph.java"));
        CompilationOutputs.prepare(request());
        compileOwnedAndManualSources(List.of("-g:lines,vars"));
        writeProvisionalIndex();
        writeProcessorInventory(List.of("fx.Docs_"));

        CodegenException failure =
                assertThrows(CodegenException.class, () -> CompilationOutputs.complete(request()));

        assertEquals("SG602", failure.code());
        assertTrue(failure.getMessage().contains("-g:lines,vars"), failure.getMessage());
        assertTrue(failure.getMessage().contains("-g:source"), failure.getMessage());
    }

    /**
     * A class file the interrupted cycle cannot own, here one javac left truncated, is never read: only the files
     * named after an owned unit, in that unit's package directory, can be its binaries.
     */
    @Test
    void retiresAnInterruptedCompilationWithoutReadingClassFilesItCannotOwn() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE, "fx/DocsGraph.java"));
        CompilationOutputs.prepare(request());
        compileOwnedAndManualSources();
        Path unrelated = classesOutput.resolve("fx/Manual.class");
        byte[] truncated = Arrays.copyOf(Files.readAllBytes(unrelated), 30);
        Files.write(unrelated, truncated);

        CompilationOutputs.prepare(request());

        assertFalse(Files.exists(classesOutput.resolve(CARRIER_BINARY)));
        assertFalse(Files.exists(classesOutput.resolve("fx/DocsGraph.class")));
        assertFalse(Files.exists(classesOutput.resolve("fx/DocsGraph$Transition.class")));
        assertArrayEquals(truncated, Files.readAllBytes(unrelated));
    }

    /** A resource that merely ends in {@code .class}, as Maven may copy into the class output, is never read. */
    @Test
    void sealsTheInventoryWithoutReadingAResourceNamedLikeAClassFile() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE, "fx/DocsGraph.java"));
        CompilationOutputs.prepare(request());
        compileOwnedAndManualSources();
        byte[] text = "plain text fixture\n".getBytes(StandardCharsets.UTF_8);
        Path resource = write(classesOutput.resolve("samples/legacy.class"), text);
        writeProvisionalIndex();
        writeProcessorInventory(List.of("fx.Docs_"));

        CompilationOutputs.complete(request());

        assertEquals(new TreeSet<>(List.of("fx/DocsGraph.class", "fx/DocsGraph$Transition.class", "fx/Docs_.class")),
                new TreeSet<>(ManifestCodec.readOwned(workDirectory.resolve(INVENTORY)).binaries()));
        assertArrayEquals(text, Files.readAllBytes(resource));
    }

    @Test
    void retiresTheClassFilesAnInterruptedCompilationLeftBehind() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE, "fx/DocsGraph.java"));
        CompilationOutputs.prepare(request());
        compileOwnedAndManualSources();
        byte[] manualBytes = Files.readAllBytes(classesOutput.resolve("fx/Manual.class"));

        CompilationOutputs.prepare(request());

        assertFalse(Files.exists(classesOutput.resolve(CARRIER_BINARY)), "the carrier of an interrupted cycle");
        assertFalse(Files.exists(classesOutput.resolve("io/github/graphmaquina")));
        assertFalse(Files.exists(classesOutput.resolve("fx/DocsGraph.class")));
        assertFalse(Files.exists(classesOutput.resolve("fx/DocsGraph$Transition.class")));
        assertArrayEquals(manualBytes, Files.readAllBytes(classesOutput.resolve("fx/Manual.class")));
    }

    /** The request every test runs against: five roots that share no parent but the temporary file system. */
    private GenerationRequest request() {
        return new GenerationRequest(project, List.of(project.resolve("originals")), SourceFiles.classpath(),
                sourcesOutput, processorSourcesOutput, classesOutput, workDirectory, "fx:test:main");
    }

    /** A manifest describing a compilation that owns {@code ownedSources} and writes its inventory in work. */
    private void writeManifest(List<String> ownedSources) throws IOException {
        ExportIndex local = new ExportIndex(2, ToolVersion.current(), "fx:test:main",
                new GenerationModel(List.of(), List.of()), List.of());
        ManifestCodec.writeCompile(workDirectory.resolve("annotation-manifest.json"),
                new CompileManifest(2, ToolVersion.current(), local, List.of(),
                        workDirectory.resolve("processor-inventory.json").toString(), ownedSources));
    }

    /**
     * Compiles into the class output, as javac would, the generator's {@code fx.DocsGraph} and the carrier,
     * {@code fx.Docs_} as the annotation processor would write it, and {@code fx.Manual}, which no generation owns.
     */
    private void compileOwnedAndManualSources() {
        compileOwnedAndManualSources(List.of());
    }

    /** The same, under {@code options} of the build's own choosing, such as a debug setting. */
    private void compileOwnedAndManualSources(List<String> options) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocsGraph", """
                package fx;
                public class DocsGraph {
                    public static class Transition { }
                }
                """);
        sources.put(CARRIER, """
                package io.github.graphmaquina.generated.bootstrap;
                public final class GraphCompilation0123456789abcdef {
                    private GraphCompilation0123456789abcdef() { }
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
        ManifestCodec.writeExports(pending, new ExportIndex(2, ToolVersion.current(), "fx:test:main",
                new GenerationModel(List.of(), List.of()), List.of()));
        return Files.readAllBytes(pending);
    }

    /** The inventory the processor writes at the absolute path the manifest names. */
    private void writeProcessorInventory(List<String> generatedTypes) throws IOException {
        StringBuilder json = new StringBuilder("{\n  \"schemaVersion\": 2,\n  \"generatedTypes\": [");
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
