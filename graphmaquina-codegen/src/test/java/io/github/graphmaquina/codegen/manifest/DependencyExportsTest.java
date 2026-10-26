package io.github.graphmaquina.codegen.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.ToolVersion;
import io.github.graphmaquina.codegen.model.GenerationModel;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import io.github.graphmaquina.codegen.model.SourceLocation;
import io.github.graphmaquina.codegen.source.ProducerJars;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies how the published indexes of a classpath are aggregated: a directory and an archive are read the same
 * way, each index keeps the entry it came from, an entry listed twice is read once, only the promoted name is read,
 * an entry that carries nothing is skipped, an index written by another tool version is refused instead of being
 * interpreted and so is a machine that the indexes of two entries describe.
 */
class DependencyExportsTest {

    @TempDir
    Path directory;

    private static final GenerationModel EMPTY = new GenerationModel(List.of(), List.of());

    @Test
    void readsThePublishedIndexOfADirectoryAndOfAnArchiveInCompilationOrder() throws IOException {
        Path classes = ProducerJars.published(directory.resolve("classes"), "exports.json",
                index(ToolVersion.current(), "io.github.graphmaquina:beta:main"));
        Path archive = ProducerJars.archived(directory.resolve("alpha.jar"), "META-INF/graphmaquina/exports.json",
                index(ToolVersion.current(), "io.github.graphmaquina:alpha:main"));

        List<PublishedIndex> indexes = DependencyExports.read(List.of(classes, archive,
                directory.resolve("absent"), directory.resolve("classes/META-INF/graphmaquina/exports.json")));

        assertEquals(List.of("io.github.graphmaquina:alpha:main", "io.github.graphmaquina:beta:main"),
                indexes.stream().map((PublishedIndex published) -> published.index().compilationId()).toList());
        assertEquals(List.of("fx.OrdersGraph.Transition"), indexes.get(0).index().annotationTypes());
    }

    /** The entry is the one the classpath named, made absolute and normalized: it is what a message can point at. */
    @Test
    void keepsTheEntryOfEachIndex() throws IOException {
        Path classes = ProducerJars.published(directory.resolve("classes"), "exports.json",
                index(ToolVersion.current(), "io.github.graphmaquina:beta:main"));
        Path archive = ProducerJars.archived(directory.resolve("alpha.jar"), "META-INF/graphmaquina/exports.json",
                index(ToolVersion.current(), "io.github.graphmaquina:alpha:main"));

        List<PublishedIndex> indexes =
                DependencyExports.read(List.of(directory.resolve("other/../classes"), archive));

        assertEquals(List.of(archive.toAbsolutePath().normalize(), classes.toAbsolutePath().normalize()),
                indexes.stream().map(PublishedIndex::entry).toList());
    }

    @Test
    void refusesAMachineTwoEntriesDescribe() throws IOException {
        Path first = ProducerJars.archived(directory.resolve("a/docs.jar"), "META-INF/graphmaquina/exports.json",
                describing("io.github.graphmaquina:a:main", "fx.Docs"));
        Path second = ProducerJars.archived(directory.resolve("b/docs.jar"), "META-INF/graphmaquina/exports.json",
                describing("io.github.graphmaquina:b:main", "fx.Docs"));

        CodegenException failure =
                assertThrows(CodegenException.class, () -> DependencyExports.read(List.of(first, second)));

        assertEquals("SG602", failure.code());
        assertEquals("the machine fx.Docs is described by the export indexes of " + first + " and " + second
                + ": keep one artifact of its producer on the classpath", failure.getMessage());
        CodegenException reversed =
                assertThrows(CodegenException.class, () -> DependencyExports.read(List.of(second, first)));
        assertEquals("the machine fx.Docs is described by the export indexes of " + second + " and " + first
                + ": keep one artifact of its producer on the classpath", reversed.getMessage());
    }

    @Test
    void acceptsTwoEntriesThatDescribeDifferentMachines() throws IOException {
        Path docs = ProducerJars.archived(directory.resolve("a/docs.jar"), "META-INF/graphmaquina/exports.json",
                describing("io.github.graphmaquina:a:main", "fx.Docs"));
        Path orders = ProducerJars.archived(directory.resolve("b/orders.jar"), "META-INF/graphmaquina/exports.json",
                describing("io.github.graphmaquina:b:main", "fx.Orders"));

        assertEquals(List.of(docs, orders),
                DependencyExports.read(List.of(orders, docs)).stream().map(PublishedIndex::entry).toList());
    }

    /** Two entries disagreeing is the fault, not one index that lists a machine twice: that is no copy of anything. */
    @Test
    void doesNotBlameTwoEntriesForAMachineOneIndexListsTwice() throws IOException {
        ExportIndex once = describing("io.github.graphmaquina:a:main", "fx.Docs");
        GraphDescriptor graph = once.model().graphs().get(0);
        ExportIndex twice = new ExportIndex(2, ToolVersion.current(), "io.github.graphmaquina:a:main",
                new GenerationModel(List.of(graph, graph), List.of()), once.annotationTypes());
        Path archive =
                ProducerJars.archived(directory.resolve("docs.jar"), "META-INF/graphmaquina/exports.json", twice);

        assertEquals(List.of(archive),
                DependencyExports.read(List.of(archive)).stream().map(PublishedIndex::entry).toList());
    }

    /** An entry that a classpath lists twice, spelled in two ways, is one dependency and not two copies of it. */
    @Test
    void readsAnEntryListedTwiceOnce() throws IOException {
        Path archive = ProducerJars.archived(directory.resolve("docs.jar"), "META-INF/graphmaquina/exports.json",
                describing("io.github.graphmaquina:a:main", "fx.Docs"));

        List<PublishedIndex> indexes =
                DependencyExports.read(List.of(archive, archive, directory.resolve("other/../docs.jar")));

        assertEquals(List.of(archive), indexes.stream().map(PublishedIndex::entry).toList());
    }

    @Test
    void ignoresTheIndexAProcessorHasNotPromotedYet() throws IOException {
        Path classes = ProducerJars.published(directory.resolve("classes"), "pending-exports.json",
                index(ToolVersion.current(), "io.github.graphmaquina:pending:main"));

        assertEquals(List.of(), DependencyExports.read(List.of(classes)));
    }

    @Test
    void refusesAnIndexWrittenByAnotherToolVersion() throws IOException {
        Path classes = ProducerJars.published(directory.resolve("classes"), "exports.json",
                index("0.0.1-OTHER", "io.github.graphmaquina:old:main"));

        CodegenException failure =
                assertThrows(CodegenException.class, () -> DependencyExports.read(List.of(classes)));

        assertEquals("SG602", failure.code());
        assertTrue(failure.getMessage().contains(classes.toString()), failure.getMessage());
        assertTrue(failure.getMessage().contains("0.0.1-OTHER"), failure.getMessage());
    }

    @Test
    void refusesAnIndexOfAnotherSchema() throws IOException {
        Path classes = Files.createDirectories(directory.resolve("classes/META-INF/graphmaquina"));
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

    /** A truncated index is reported against the classpath entry that carries it, which is what a user can fix. */
    @Test
    void namesTheEntryWhoseIndexIsNotWellFormedJson() throws IOException {
        Path metadata = Files.createDirectories(directory.resolve("classes/META-INF/graphmaquina"));
        Files.writeString(metadata.resolve("exports.json"),
                "{\"schemaVersion\": 2, \"toolVersion\": \"" + ToolVersion.current(), StandardCharsets.UTF_8);

        CodegenException failure = assertThrows(CodegenException.class,
                () -> DependencyExports.read(List.of(directory.resolve("classes"))));

        assertEquals("SG602", failure.code());
        assertTrue(failure.getMessage().startsWith("the StateGraph export index published by "
                + directory.resolve("classes") + " cannot be read: malformed JSON in "), failure.getMessage());
    }

    /**
     * An index that escapes an unpaired surrogate is well-formed strict JSON that no UTF-8 manifest could carry on, so
     * it is refused when it is read, against the classpath entry that carries it.
     */
    @Test
    void namesTheEntryWhoseIndexHoldsAnUnpairedSurrogate() throws IOException {
        Path metadata = Files.createDirectories(directory.resolve("classes/META-INF/graphmaquina"));
        Files.writeString(metadata.resolve("exports.json"), "{\"schemaVersion\": 2, \"toolVersion\": \""
                + ToolVersion.current() + "\", \"compilationId\": \"other:lib:main\", \"model\": {\"graphs\": [],"
                + " \"symbols\": []}, \"annotationTypes\": [\"lib.Orders\\ud800.Transition\"]}",
                StandardCharsets.US_ASCII);

        CodegenException failure = assertThrows(CodegenException.class,
                () -> DependencyExports.read(List.of(directory.resolve("classes"))));

        assertEquals("SG602", failure.code());
        assertEquals("the StateGraph export index published by " + directory.resolve("classes") + " cannot be read:"
                + " malformed JSON in " + metadata.resolve("exports.json") + ": an unpaired surrogate \\ud800 at path"
                + " $.annotationTypes[0]", failure.getMessage());
    }

    /** An archive that cannot be opened is named, rather than surfacing the archive library's bare complaint. */
    @Test
    void namesAnArchiveItCannotOpen() throws IOException {
        Path archive = Files.writeString(directory.resolve("broken.jar"), "not an archive", StandardCharsets.UTF_8);

        IOException failure = assertThrows(IOException.class, () -> DependencyExports.read(List.of(archive)));

        assertTrue(failure.getMessage().contains(archive.toString()), failure.getMessage());
    }

    /** An index of {@code compilationId} that describes the machine {@code facade}. */
    private static ExportIndex describing(String compilationId, String facade) {
        String name = facade.substring(facade.lastIndexOf('.') + 1);
        GraphDescriptor graph = new GraphDescriptor(facade, "fx", "fx.Document", "fx." + name + "Status",
                List.of("DRAFT"), "fx." + name + "Event", List.of("PUBLISH"), List.of(),
                new SourceLocation("src/fx/" + name + ".java", 1, 1));
        return new ExportIndex(2, ToolVersion.current(), compilationId,
                new GenerationModel(List.of(graph), List.of()), List.of(facade + "Graph.Transition"));
    }

    private static ExportIndex index(String toolVersion, String compilationId) {
        return new ExportIndex(2, toolVersion, compilationId, EMPTY, List.of("fx.OrdersGraph.Transition"));
    }
}
