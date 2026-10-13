package io.github.graphmaquina.codegen.output;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.ToolVersion;
import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import io.github.graphmaquina.codegen.manifest.OwnedOutputs;
import io.github.graphmaquina.codegen.model.GeneratedSource;
import io.github.graphmaquina.codegen.model.GenerationModel;
import io.github.graphmaquina.codegen.source.SourceFiles;
import io.github.graphmaquina.codegen.source.SymbolicLinks;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the lifecycle a build runs around javac: what {@code prepare} retires before the compiler starts and
 * what {@code complete} seals once it succeeded.
 *
 * <p>Every root is a temporary directory of its own, so nothing here can pass because two outputs happen to
 * share a parent. The inventory decides what may be deleted: a class file that no inventory lists survives
 * {@code prepare} byte for byte, unless an interrupted cycle left it and it was compiled from one of the units the
 * inventories, the manifest or the processor inventory name. A class file whose name merely starts like such a
 * unit's, as {@code fx/DocsGraph$Manual.class} of a top-level class beside {@code fx.DocsGraph}, was not.</p>
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

    /**
     * Deletes the symbolic links a test left in its roots. Several lead from one root into another, and JUnit warns
     * about a link that leads out of the directory it is deleting, depending on which root it deletes first.
     */
    @AfterEach
    void deleteSymbolicLinks() throws IOException {
        for (Path root : List.of(project, sourcesOutput, processorSourcesOutput, classesOutput, workDirectory)) {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path link : paths.filter(Files::isSymbolicLink).toList()) {
                    Files.delete(link);
                }
            }
        }
    }

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

    /**
     * An entry that leads up out of its root, or that has a root of its own, as {@code /outside.class} has here and
     * {@code \outside.class} or {@code C:outside.class} would have on Windows, is refused with {@code SG603}.
     */
    @ParameterizedTest
    @ValueSource(strings = {"../outside.class", "/outside.class"})
    void refusesAnInventoryPathThatEscapesItsRoot(String entry) throws IOException {
        writeManifest(List.of(CARRIER_SOURCE));
        ManifestCodec.writeOwned(workDirectory.resolve(INVENTORY), new OwnedOutputs(2,
                List.of(), List.of(entry), List.of(), List.of()));

        CodegenException failure =
                assertThrows(CodegenException.class, () -> CompilationOutputs.prepare(request()));

        assertEquals("SG603", failure.code());
        assertTrue(failure.getMessage().contains(entry), failure.getMessage());
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

    /**
     * An entry no path can spell, such as one holding the NUL character, is refused like an entry outside its root,
     * with {@code SG603} naming it, in the sealed and in the pending inventory, before any output is changed.
     */
    @ParameterizedTest
    @ValueSource(strings = {INVENTORY, PENDING_INVENTORY})
    void refusesAnInventoryPathThatNoPathCanSpell(String inventoryName) throws IOException {
        writeManifest(List.of(CARRIER_SOURCE));
        ManifestCodec.writeOwned(workDirectory.resolve(inventoryName), new OwnedOutputs(2,
                List.of("fx/Old_\u0000.java"), List.of(), List.of(), List.of()));
        Path kept = write(classesOutput.resolve(PUBLISHED), new byte[] {1});

        CodegenException failure =
                assertThrows(CodegenException.class, () -> CompilationOutputs.prepare(request()));

        assertEquals("SG603", failure.code());
        assertTrue(failure.getMessage().contains("fx/Old_"), failure.getMessage());
        assertTrue(Files.exists(kept));
    }

    /**
     * After an interrupted cycle, an inventory entry that is not the kind of file its list holds, a binary or an
     * excluded binary that is no class file or a source that is no Java source, is refused with {@code SG603} naming the
     * inventory and the entry, before the pending inventory is rewritten: the next preparation refuses it again, rather
     * than failing on what this one wrote. So is an owned source of the manifest that is no Java source, with
     * {@code SG602} naming the manifest.
     */
    @ParameterizedTest
    @CsvSource({
        "owned-outputs.json, binaries, fx, class file",
        "owned-outputs.json, excludedBinaries, a, class file",
        "owned-outputs.json, sources, x, source file",
        "owned-outputs.pending.json, binaries, fx/.class, class file",
        "annotation-manifest.json, ownedSources, x, ''"})
    void refusesAnEntryThatIsNotTheKindOfFileItsListHolds(String file, String list, String entry, String kind)
            throws IOException {
        writeManifest(List.of(CARRIER_SOURCE));
        Map<String, List<String>> lists = new LinkedHashMap<>();
        for (String name : List.of("sources", "binaries", "excludedBinaries")) {
            lists.put(name, name.equals(list) ? List.of(entry) : List.of());
        }
        OwnedOutputs inventory = new OwnedOutputs(2, lists.get("sources"), lists.get("binaries"), List.of(),
                lists.get("excludedBinaries"));
        Path manifest = workDirectory.resolve("annotation-manifest.json");
        Path pending = workDirectory.resolve(PENDING_INVENTORY);
        if (file.equals(INVENTORY)) {
            ManifestCodec.writeOwned(workDirectory.resolve(INVENTORY), inventory);
            ManifestCodec.writeOwned(pending, new OwnedOutputs(2, List.of(), List.of(), List.of(), List.of()));
        } else if (file.equals(PENDING_INVENTORY)) {
            ManifestCodec.writeOwned(pending, inventory);
        } else {
            Files.writeString(manifest, Files.readString(manifest).replace("\"" + CARRIER_SOURCE + "\"",
                    "\"" + CARRIER_SOURCE + "\", \"" + entry + "\""));
            ManifestCodec.writeOwned(pending, new OwnedOutputs(2, List.of(), List.of(), List.of(), List.of()));
        }
        byte[] interrupted = Files.readAllBytes(pending);

        Exception failure = assertThrows(Exception.class, () -> CompilationOutputs.prepare(request()));

        assertArrayEquals(interrupted, Files.readAllBytes(pending), failure.toString());
        CodegenException refusal = assertInstanceOf(CodegenException.class, failure);
        if (file.equals(manifest.getFileName().toString())) {
            assertEquals("SG602", refusal.code());
            assertEquals("inventory path '" + entry + "' names no Java source in " + manifest, refusal.getMessage());
        } else {
            assertEquals("SG603", refusal.code());
            Path root = kind.equals("class file") ? classesOutput : processorSourcesOutput;
            assertEquals("the inventory " + file + " claims '" + entry + "', which is not a " + kind + " under "
                    + root.toAbsolutePath().normalize(), refusal.getMessage());
        }
    }

    /**
     * A symbolic link inside the class output that leads outside it would take an inventory entry's file out of the
     * root, whether the entry goes through the link or through the link and back up. The entry is refused with
     * {@code SG603} before any output is changed, and the files outside survive.
     */
    @ParameterizedTest
    @ValueSource(strings = {"link/x.class", "link/../x.class"})
    void refusesAnInventoryPathThatASymbolicLinkTakesOutOfItsRoot(String entry) throws IOException {
        writeManifest(List.of(CARRIER_SOURCE));
        Path outside = Files.createDirectories(project.resolve("outside/deep/er"));
        Path throughLink = write(outside.resolve("x.class"), new byte[] {1});
        Path upFromLink = write(outside.getParent().resolve("x.class"), new byte[] {2});
        SymbolicLinks.create(classesOutput.resolve("link"), outside);
        ManifestCodec.writeOwned(workDirectory.resolve(INVENTORY), new OwnedOutputs(2,
                List.of(), List.of(entry), List.of(), List.of()));
        Path kept = write(classesOutput.resolve(PUBLISHED), new byte[] {1});

        CodegenException failure =
                assertThrows(CodegenException.class, () -> CompilationOutputs.prepare(request()));

        assertEquals("SG603", failure.code());
        assertTrue(Files.exists(throughLink));
        assertTrue(Files.exists(upFromLink));
        assertTrue(Files.exists(kept));
    }

    /**
     * A file that {@code prepare} removes without an entry of the sealed or pending inventory naming it, here a source
     * the processor inventory names and the published index, is left alone when a symbolic link inside its root takes
     * it out of that root.
     */
    @Test
    void leavesAFileThatASymbolicLinkInsideItsRootTakesOutOfIt() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE, "fx/DocsGraph.java"));
        CompilationOutputs.prepare(request());
        Path linkedSources = Files.createDirectories(project.resolve("outside/sources"));
        Path source = write(linkedSources.resolve("Old_.java"), new byte[] {1});
        SymbolicLinks.create(processorSourcesOutput.resolve("fx"), linkedSources);
        Path linkedMeta = Files.createDirectories(project.resolve("outside/meta/graphmaquina"));
        Path index = write(linkedMeta.resolve("exports.json"), new byte[] {2});
        SymbolicLinks.create(classesOutput.resolve("META-INF"), linkedMeta.getParent());
        ManifestCodec.writeProcessorInventory(workDirectory.resolve("processor-inventory.json"), List.of("fx.Old_"));

        CompilationOutputs.prepare(request());

        assertTrue(Files.exists(source));
        assertTrue(Files.exists(index));
    }

    /**
     * After a removal, the emptied directories are pruned only while each stands under its root by its real path: a
     * directory reached through a link that leaves the root, here a link outside that leads back into it, stays.
     */
    @Test
    void prunesNoDirectoryThatASymbolicLinkTakesOutOfItsRoot() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE));
        Path inside = Files.createDirectories(classesOutput.resolve("inside"));
        write(inside.resolve("x.class"), new byte[] {1});
        Path outside = Files.createDirectories(project.resolve("outside"));
        Path back = SymbolicLinks.create(outside.resolve("back"), inside);
        SymbolicLinks.create(classesOutput.resolve("out"), outside);
        ManifestCodec.writeOwned(workDirectory.resolve(INVENTORY), new OwnedOutputs(2,
                List.of(), List.of("out/back/x.class"), List.of(), List.of()));

        CompilationOutputs.prepare(request());

        assertFalse(Files.exists(inside.resolve("x.class")));
        assertTrue(Files.isSymbolicLink(back));
    }

    /**
     * A manifest whose processor inventory no path can spell, such as one holding the NUL character, is refused with
     * {@code SG602} naming the manifest, both by a preparation after an interrupted cycle, which reads that inventory,
     * and by the completion, before either changes an output.
     */
    @ParameterizedTest
    @ValueSource(strings = {"prepare", "complete"})
    void refusesAManifestWhoseProcessorInventoryNoPathCanSpell(String step) throws IOException {
        writeManifest(List.of(CARRIER_SOURCE));
        CompilationOutputs.prepare(request());
        writeProvisionalIndex();
        Path manifest = workDirectory.resolve("annotation-manifest.json");
        String inventory = workDirectory.resolve("processor-inventory.json").toString()
                .replace("processor-inventory.json", "processor-inventory\u0000.json");
        Files.writeString(manifest, Files.readString(manifest, StandardCharsets.UTF_8)
                .replace("processor-inventory.json", "processor-inventory\\u0000.json"), StandardCharsets.UTF_8);
        byte[] pending = Files.readAllBytes(workDirectory.resolve(PENDING_INVENTORY));

        Exception failure = assertThrows(Exception.class, () -> {
            if (step.equals("prepare")) {
                CompilationOutputs.prepare(request());
            } else {
                CompilationOutputs.complete(request());
            }
        });

        CodegenException refusal = assertInstanceOf(CodegenException.class, failure);
        assertEquals("SG602", refusal.code());
        assertEquals("processor inventory '" + inventory + "' is not a path in " + manifest, refusal.getMessage());
        assertArrayEquals(pending, Files.readAllBytes(workDirectory.resolve(PENDING_INVENTORY)));
        assertTrue(Files.exists(classesOutput.resolve(PENDING)));
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
        assertTrue(Files.isRegularFile(classesOutput.resolve("fx/DocsGraph$Manual.class")));
        assertArrayEquals(index, Files.readAllBytes(classesOutput.resolve(PUBLISHED)));
        assertFalse(Files.exists(classesOutput.resolve(PENDING)));
        assertFalse(Files.exists(workDirectory.resolve(PENDING_INVENTORY)));
        assertEquals(List.of("annotation-manifest.json", INVENTORY, "processor-inventory.json"),
                sorted(names(workDirectory)));
    }

    /**
     * A symbolic link inside the class output that leads outside it takes the index {@code complete} would publish,
     * which the inventory it seals lists, out of the class output: the compilation is refused with {@code SG603}
     * before any output is changed.
     */
    @Test
    void refusesToCompleteWhenASymbolicLinkTakesTheIndexOutOfTheClassOutput() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE, "fx/DocsGraph.java"));
        CompilationOutputs.prepare(request());
        compileOwnedAndManualSources();
        Path outside = Files.createDirectories(project.resolve("outside"));
        SymbolicLinks.create(classesOutput.resolve("META-INF"), outside);
        byte[] index = writeProvisionalIndex();
        writeProcessorInventory(List.of("fx.Docs_"));

        CodegenException failure =
                assertThrows(CodegenException.class, () -> CompilationOutputs.complete(request()));

        assertEquals("SG603", failure.code());
        assertArrayEquals(index, Files.readAllBytes(outside.resolve("graphmaquina/pending-exports.json")));
        assertFalse(Files.exists(outside.resolve("graphmaquina/exports.json")));
        assertTrue(Files.isRegularFile(classesOutput.resolve(CARRIER_BINARY)));
        assertTrue(Files.isRegularFile(workDirectory.resolve(PENDING_INVENTORY)));
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

    /**
     * The binaries an interrupted cycle left of the units it owned are retired, while a class file whose name merely
     * starts like an owned unit's, that of the top-level {@code fx.DocsGraph$Manual}, is read and kept.
     */
    @Test
    void retiresTheClassFilesAnInterruptedCompilationLeftBehind() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE, "fx/DocsGraph.java"));
        CompilationOutputs.prepare(request());
        compileOwnedAndManualSources();
        byte[] manualBytes = Files.readAllBytes(classesOutput.resolve("fx/Manual.class"));
        byte[] namedAlikeBytes = Files.readAllBytes(classesOutput.resolve("fx/DocsGraph$Manual.class"));

        CompilationOutputs.prepare(request());

        assertFalse(Files.exists(classesOutput.resolve(CARRIER_BINARY)), "the carrier of an interrupted cycle");
        assertFalse(Files.exists(classesOutput.resolve("io/github/graphmaquina")));
        assertFalse(Files.exists(classesOutput.resolve("fx/DocsGraph.class")));
        assertFalse(Files.exists(classesOutput.resolve("fx/DocsGraph$Transition.class")));
        assertArrayEquals(manualBytes, Files.readAllBytes(classesOutput.resolve("fx/Manual.class")));
        assertArrayEquals(namedAlikeBytes, Files.readAllBytes(classesOutput.resolve("fx/DocsGraph$Manual.class")));
    }

    /**
     * The pending inventory lists the generator's sources beside the processor's, but they stand under the
     * generated source output. After an interrupted cycle, a generation that no longer writes one of them has already
     * retired it from there itself, so a file of the same path in the processor's source output, which another
     * annotation processor may have written, is not the generator's and survives.
     */
    @Test
    void keepsAFileOfTheProcessorSourceOutputNamedLikeASourceAnInterruptedGenerationOwned() throws IOException {
        generate(request(), List.of(CARRIER, "fx.OldRefs"));
        CompilationOutputs.prepare(request());
        generate(request(), List.of(CARRIER, "fx.NewRefs"));
        byte[] foreign = "package fx;\nfinal class OldRefs { }\n".getBytes(StandardCharsets.UTF_8);
        Path other = write(processorSourcesOutput.resolve("fx/OldRefs.java"), foreign);

        CompilationOutputs.prepare(request());

        assertArrayEquals(foreign, Files.readAllBytes(other));
        assertFalse(Files.exists(sourcesOutput.resolve("fx/OldRefs.java")));
        assertTrue(Files.isRegularFile(sourcesOutput.resolve("fx/NewRefs.java")));
    }

    /**
     * A source the sealed inventory lists is the processor's, so it is retired from the processor's source output
     * even when the generation now writes a source of the same path under its own output, which stays.
     */
    @Test
    void retiresAProcessorSourceEvenWhenTheGenerationWritesASourceOfTheSamePath() throws IOException {
        generate(request(), List.of(CARRIER, "fx.Docs_"));
        ManifestCodec.writeOwned(workDirectory.resolve(INVENTORY), new OwnedOutputs(2,
                List.of("fx/Docs_.java"), List.of(), List.of(PUBLISHED), List.of()));
        Path stale = write(processorSourcesOutput.resolve("fx/Docs_.java"),
                "package fx;\npublic class Docs_ { }\n".getBytes(StandardCharsets.UTF_8));

        CompilationOutputs.prepare(request());

        assertFalse(Files.exists(stale));
        assertTrue(Files.isRegularFile(sourcesOutput.resolve("fx/Docs_.java")));
    }

    /** Where the two source outputs are one directory, a source of the generation there is its own and stays. */
    @Test
    void keepsTheGeneratedSourceWhereBothSourceOutputsAreOneDirectory() throws IOException {
        GenerationRequest shared = new GenerationRequest(project, List.of(project.resolve("originals")),
                SourceFiles.classpath(), sourcesOutput, sourcesOutput, classesOutput, workDirectory, "fx:test:main");
        ManifestCodec.writeOwned(workDirectory.resolve(INVENTORY), new OwnedOutputs(2,
                List.of("fx/Docs_.java"), List.of(), List.of(PUBLISHED), List.of()));
        generate(shared, List.of(CARRIER, "fx.Docs_"));

        CompilationOutputs.prepare(shared);

        assertTrue(Files.isRegularFile(sourcesOutput.resolve("fx/Docs_.java")));
    }

    /**
     * One directory spelled two ways is still one directory: when the processor's source output names the generated
     * source output through a symbolic link, or in another case on a volume that does not tell names apart by case, a
     * source of the generation there is its own and stays.
     */
    @ParameterizedTest
    @ValueSource(strings = {"symbolic link", "case"})
    void keepsTheGeneratedSourceWhereBothSourceOutputsAreOneDirectorySpelledTwoWays(String spelling)
            throws IOException {
        Path alias = spelling.equals("case")
                ? sourcesOutput.resolveSibling(otherCase(sourcesOutput.getFileName().toString()))
                : SymbolicLinks.create(project.resolve("processor-alias"), sourcesOutput);
        assumeTrue(!alias.equals(sourcesOutput) && Files.isDirectory(alias) && Files.isSameFile(alias, sourcesOutput),
                "only a case-insensitive volume finds a directory under another case");
        GenerationRequest shared = new GenerationRequest(project, List.of(project.resolve("originals")),
                SourceFiles.classpath(), sourcesOutput, alias, classesOutput, workDirectory, "fx:test:main");
        ManifestCodec.writeOwned(workDirectory.resolve(INVENTORY), new OwnedOutputs(2,
                List.of("fx/Docs_.java"), List.of(), List.of(PUBLISHED), List.of()));
        generate(shared, List.of(CARRIER, "fx.Docs_"));

        CompilationOutputs.prepare(shared);

        assertTrue(Files.isRegularFile(sourcesOutput.resolve("fx/Docs_.java")));
    }

    /**
     * A companion the processor generated for the first time in a javac run that was interrupted is in no inventory
     * of this tool, but the processor inventory that run left names it. The next preparation, which comes before
     * javac could generate it again, retires its source and its class files, nested ones included, and leaves the
     * source and the class file of a type that inventory does not name, such as one another processor wrote.
     */
    @Test
    void retiresWhatTheProcessorFirstGeneratedInAnInterruptedCompilation() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE, "fx/DocsGraph.java"));
        CompilationOutputs.prepare(request());
        compileProcessorSources();
        ManifestCodec.writeProcessorInventory(workDirectory.resolve("processor-inventory.json"), List.of("fx.Old_"));

        CompilationOutputs.prepare(request());

        assertFalse(Files.exists(processorSourcesOutput.resolve("fx/Old_.java")));
        assertFalse(Files.exists(classesOutput.resolve("fx/Old_.class")));
        assertFalse(Files.exists(classesOutput.resolve("fx/Old_$Builder.class")));
        assertTrue(Files.isRegularFile(processorSourcesOutput.resolve("fx/Other_.java")));
        assertTrue(Files.isRegularFile(classesOutput.resolve("fx/Other_.class")));
    }

    /**
     * Nothing says what the processor generated without a processor inventory, with one the codec refuses, such as
     * one the interrupted javac stopped writing halfway or one holding a number whose exponent is out of range, or
     * with one that names anything but a qualified or binary Java name, as the processor writes them: a name holding
     * the NUL character, which javac leaves out of every identifier it reads and no path can hold, a path, an empty
     * name or a Windows path. The preparation goes on and deletes nothing such an inventory names, not even what its
     * valid names name.
     */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "half-written", "an exponent out of range", "NUL in a name", "a path",
        "an empty name", "a Windows path"})
    void retiresNothingThatOnlyAnUnusableProcessorInventoryWouldName(String inventory) throws IOException {
        writeManifest(List.of(CARRIER_SOURCE, "fx/DocsGraph.java"));
        CompilationOutputs.prepare(request());
        compileProcessorSources();
        Path file = workDirectory.resolve("processor-inventory.json");
        switch (inventory) {
            case "missing" -> { }
            case "half-written" -> Files.writeString(file,
                    "{\n  \"schemaVersion\": 2,\n  \"generatedTypes\": [\n    \"fx.Old_\"", StandardCharsets.UTF_8);
            case "an exponent out of range" -> Files.writeString(file,
                    "{\n  \"schemaVersion\": 1e99999999999,\n  \"generatedTypes\": [\n    \"fx.Old_\"\n  ]\n}\n",
                    StandardCharsets.UTF_8);
            case "NUL in a name" -> ManifestCodec.writeProcessorInventory(file, List.of("fx.Old_", "fx.Old_\u0000"));
            case "a path" -> ManifestCodec.writeProcessorInventory(file, List.of("fx.Old_", "../outside/Victim"));
            case "an empty name" -> ManifestCodec.writeProcessorInventory(file, List.of("fx.Old_", ""));
            case "a Windows path" -> ManifestCodec.writeProcessorInventory(file, List.of("fx.Old_", "C:\\x"));
            default -> throw new IllegalArgumentException(inventory);
        }

        CompilationOutputs.prepare(request());

        assertTrue(Files.isRegularFile(processorSourcesOutput.resolve("fx/Old_.java")));
        assertTrue(Files.isRegularFile(classesOutput.resolve("fx/Old_.class")));
        assertTrue(Files.isRegularFile(classesOutput.resolve("fx/Old_$Builder.class")));
    }

    /**
     * javac leaves out of an identifier only the ignorable characters that fit in one {@code char}, so a supplementary
     * one, such as U+E0001, stays in the name of a type the processor generates. A processor inventory naming such a
     * type is used like any other: its source and its class file are retired.
     */
    @Test
    void retiresATypeWhoseNameHoldsASupplementaryCharacterJavacKeeps() throws IOException {
        String simpleName = "Tag" + Character.toString(0xE0001) + "_";
        writeManifest(List.of(CARRIER_SOURCE, "fx/DocsGraph.java"));
        CompilationOutputs.prepare(request());
        List<Path> files = SourceFiles.write(processorSourcesOutput,
                Map.of("fx." + simpleName, "package fx;\npublic final class " + simpleName + " { }\n"));
        SourceFiles.Compilation compilation = SourceFiles.compileStrict(files, classesOutput);
        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
        assertTrue(Files.isRegularFile(classesOutput.resolve("fx/" + simpleName + ".class")));
        ManifestCodec.writeProcessorInventory(workDirectory.resolve("processor-inventory.json"),
                List.of("fx." + simpleName));

        CompilationOutputs.prepare(request());

        assertFalse(Files.exists(processorSourcesOutput.resolve("fx/" + simpleName + ".java")));
        assertFalse(Files.exists(classesOutput.resolve("fx/" + simpleName + ".class")));
    }

    /**
     * The sources the processor inventory names keep the same exception as the sealed inventory's: where both source
     * outputs are one directory, a source the generation writes there stays, even when the processor inventory an
     * interrupted cycle left names its type.
     */
    @Test
    void keepsTheGeneratedSourceThatTheProcessorInventoryOfAnInterruptedCompilationNames() throws IOException {
        GenerationRequest shared = new GenerationRequest(project, List.of(project.resolve("originals")),
                SourceFiles.classpath(), sourcesOutput, sourcesOutput, classesOutput, workDirectory, "fx:test:main");
        generate(shared, List.of(CARRIER, "fx.Docs_"));
        CompilationOutputs.prepare(shared);
        ManifestCodec.writeProcessorInventory(workDirectory.resolve("processor-inventory.json"), List.of("fx.Docs_"));

        CompilationOutputs.prepare(shared);

        assertTrue(Files.isRegularFile(sourcesOutput.resolve("fx/Docs_.java")));
    }

    /**
     * The processor inventory is read only after an interrupted cycle: once a cycle completed, the sealed inventory
     * says what the processor generated, so a source the processor inventory names, but the sealed inventory does
     * not, stays.
     */
    @Test
    void readsNoProcessorInventoryAfterACompletedCycle() throws IOException {
        writeManifest(List.of(CARRIER_SOURCE, "fx/DocsGraph.java"));
        ManifestCodec.writeOwned(workDirectory.resolve(INVENTORY), new OwnedOutputs(2,
                List.of("fx/Docs_.java"), List.of(), List.of(PUBLISHED), List.of()));
        ManifestCodec.writeProcessorInventory(workDirectory.resolve("processor-inventory.json"),
                List.of("fx.Docs_", "fx.Other_"));
        Path other = write(processorSourcesOutput.resolve("fx/Other_.java"),
                "package fx;\npublic final class Other_ { }\n".getBytes(StandardCharsets.UTF_8));

        CompilationOutputs.prepare(request());

        assertTrue(Files.isRegularFile(other));
    }

    /** The request every test runs against: five roots that share no parent but the temporary file system. */
    private GenerationRequest request() {
        return new GenerationRequest(project, List.of(project.resolve("originals")), SourceFiles.classpath(),
                sourcesOutput, processorSourcesOutput, classesOutput, workDirectory, "fx:test:main");
    }

    /** Writes the manifest of a compilation that owns {@code ownedSources}, as {@link #manifest} describes it. */
    private void writeManifest(List<String> ownedSources) throws IOException {
        ManifestCodec.writeCompile(workDirectory.resolve("annotation-manifest.json"), manifest(ownedSources));
    }

    /** A manifest describing a compilation that owns {@code ownedSources} and writes its inventory in work. */
    private CompileManifest manifest(List<String> ownedSources) {
        ExportIndex local = new ExportIndex(2, ToolVersion.current(), "fx:test:main",
                new GenerationModel(List.of(), List.of()), List.of());
        return new CompileManifest(2, ToolVersion.current(), local, List.of(),
                workDirectory.resolve("processor-inventory.json").toString(), ownedSources);
    }

    /**
     * Publishes, as the generator does, a generation that writes a source for each of {@code types} and owns them:
     * the sources the previous generation owned are retired from the generated source output first.
     */
    private void generate(GenerationRequest request, List<String> types) throws IOException {
        List<GeneratedSource> sources = new ArrayList<>();
        List<String> owned = new ArrayList<>();
        for (String type : types) {
            sources.add(new GeneratedSource(type, "// " + type + "\n"));
            owned.add(type.replace('.', '/') + ".java");
        }
        GenerationOutput.publish(request, sources, manifest(owned), List.of());
    }

    /**
     * Writes into the processor's source output, as annotation processors do, {@code fx.Old_}, a companion with a
     * nested builder, and {@code fx.Other_}, and compiles both into the class output, as an interrupted javac may have.
     */
    private void compileProcessorSources() {
        List<Path> files = SourceFiles.write(processorSourcesOutput, Map.of(
                "fx.Old_", """
                        package fx;
                        public final class Old_ {
                            private Old_() { }
                            public static final class Builder { }
                        }
                        """,
                "fx.Other_", """
                        package fx;
                        public final class Other_ { }
                        """));
        SourceFiles.Compilation compilation = SourceFiles.compileStrict(files, classesOutput);
        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
    }

    /** {@code name} with its first letter that has another case in that case; {@code name} itself when none has. */
    private static String otherCase(String name) {
        for (int index = 0; index < name.length(); index++) {
            char letter = name.charAt(index);
            char other = Character.isUpperCase(letter) ? Character.toLowerCase(letter) : Character.toUpperCase(letter);
            if (other != letter) {
                return name.substring(0, index) + other + name.substring(index + 1);
            }
        }
        return name;
    }

    /**
     * Compiles into the class output, as javac would, the generator's {@code fx.DocsGraph} and the carrier,
     * {@code fx.Docs_} as the annotation processor would write it, and two classes no generation owns:
     * {@code fx.Manual}, and {@code fx.DocsGraph$Manual}, a top-level class of its own file whose class file is named
     * like a nested class of {@code fx.DocsGraph}.
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
        sources.put("fx.DocsGraph$Manual", """
                package fx;
                public class DocsGraph$Manual { }
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
