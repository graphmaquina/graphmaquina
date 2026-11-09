package io.github.graphmaquina.codegen.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.ExportRequest;
import io.github.graphmaquina.codegen.GenerationFiles;
import io.github.graphmaquina.codegen.ToolVersion;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import io.github.graphmaquina.codegen.manifest.ManifestCodec;
import io.github.graphmaquina.codegen.model.GenerationModel;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import io.github.graphmaquina.codegen.model.SourceLocation;
import io.github.graphmaquina.codegen.output.CaseFolding;
import io.github.graphmaquina.codegen.source.SourceFiles;
import io.github.graphmaquina.export.GraphView;
import io.github.graphmaquina.export.GraphvizExporter;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The export draws every machine a class output publishes through its companion's {@code structure()}, with the
 * {@code graphmaquina-core} of the classpath it is given, and owns the {@code .dot} files of its output directory.
 * The companions here are written by hand, as the processor writes their {@code structure()}.
 */
class DotExportTest {

    @TempDir
    Path directory;

    @Test
    void writesOneDotPerGraphNamedAfterTheFacadeAsTheExporterRendersIt() throws Exception {
        Path classes = compile(Map.of("p.ThingsRuntime", companion("p", "ThingsRuntime", "")));
        publish(classes, List.of(graph("p.Things", "p")));

        List<Path> written = DotExport.export(request(classes));

        Path diagram = directory.resolve("dot/p.Things.dot").toAbsolutePath();
        assertEquals(List.of(diagram), written);
        assertEquals(expected(classes, "p.ThingsRuntime"), Files.readString(diagram, StandardCharsets.UTF_8));
    }

    @Test
    void writesTheDiagramsInTheOrderOfTheIndex() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p.ThingsRuntime", companion("p", "ThingsRuntime", ""));
        sources.put("q.ThingsRuntime", companion("q", "ThingsRuntime", ""));
        Path classes = compile(sources);
        publish(classes, List.of(graph("q.Things", "q"), graph("p.Things", "p")));

        List<Path> written = DotExport.export(request(classes));

        assertEquals(List.of(directory.resolve("dot/p.Things.dot").toAbsolutePath(),
                directory.resolve("dot/q.Things.dot").toAbsolutePath()), written);
    }

    @Test
    void removesStaleDiagramsAndNothingElse() throws Exception {
        Path classes = compile(Map.of("p.ThingsRuntime", companion("p", "ThingsRuntime", "")));
        publish(classes, List.of(graph("p.Things", "p")));
        Path output = Files.createDirectories(directory.resolve("dot"));
        Files.writeString(output.resolve("p.Renamed.dot"), "digraph {}\n");
        Files.writeString(output.resolve("notes.txt"), "kept\n");
        Files.createDirectories(output.resolve("nested.dot"));

        DotExport.export(request(classes));

        assertFalse(Files.exists(output.resolve("p.Renamed.dot")));
        assertEquals("kept\n", Files.readString(output.resolve("notes.txt")));
        assertTrue(Files.isDirectory(output.resolve("nested.dot")));
        assertTrue(Files.isRegularFile(output.resolve("p.Things.dot")));
    }

    @Test
    void aCompilationWithoutMachinesWritesNothingAndRemovesStaleDiagrams() throws Exception {
        Path classes = compile(Map.of("p.ThingsRuntime", companion("p", "ThingsRuntime", "")));
        publish(classes, List.of());
        Path output = Files.createDirectories(directory.resolve("dot"));
        Files.writeString(output.resolve("p.Things.dot"), "digraph {}\n");

        assertEquals(List.of(), DotExport.export(request(classes)));
        assertFalse(Files.exists(output.resolve("p.Things.dot")));
    }

    @Test
    void aCompilationWithoutMachinesLoadsNothingSoItsClasspathNeedsNoCore() throws Exception {
        Path classes = compile(Map.of("p.ThingsRuntime", companion("p", "ThingsRuntime", "")));
        publish(classes, List.of());

        ExportRequest withoutCore = new ExportRequest(classes, List.of(classes), directory.resolve("dot"));

        assertEquals(List.of(), DotExport.export(withoutCore));
        assertTrue(Files.isDirectory(directory.resolve("dot")));
    }

    @Test
    void aClassOutputWithoutAnIndexIsRefused() throws Exception {
        Path classes = compile(Map.of("p.ThingsRuntime", companion("p", "ThingsRuntime", "")));

        CodegenException refused = assertThrows(CodegenException.class, () -> DotExport.export(request(classes)));

        assertEquals(DiagnosticCode.SG602.name(), refused.code());
        assertTrue(refused.getMessage().contains("publishes no StateGraph index at "
                + classes.resolve(GenerationFiles.PUBLISHED_INDEX)), refused.getMessage());
        assertFalse(Files.exists(directory.resolve("dot")));
    }

    @Test
    void anIndexOfAnotherToolVersionIsRefused() throws Exception {
        Path classes = compile(Map.of("p.ThingsRuntime", companion("p", "ThingsRuntime", "")));
        ProducerIndex.write(classes, new ExportIndex(GenerationFiles.SCHEMA_VERSION, "0.0.0-other", "fx:things:main",
                new GenerationModel(List.of(graph("p.Things", "p")), List.of()), List.of()));

        CodegenException refused = assertThrows(CodegenException.class, () -> DotExport.export(request(classes)));

        assertEquals(DiagnosticCode.SG602.name(), refused.code());
        assertTrue(refused.getMessage().contains("was written by tool version 0.0.0-other; this tool is "
                + ToolVersion.current()), refused.getMessage());
    }

    @Test
    void aCompanionThatCannotBeDrawnIsReportedWithItsCauseAndNothingIsWritten() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p.ThingsRuntime", companion("p", "ThingsRuntime", ""));
        sources.put("q.BrokenRuntime", companion("q", "BrokenRuntime",
                "    static { if (Boolean.parseBoolean(\"true\")) {\n"
                        + "        throw new IllegalStateException(\"no init\");\n    } }\n"));
        Path classes = compile(sources);
        publish(classes, List.of(graph("p.Things", "p"), graph("q.Broken", "q")));

        IllegalStateException failed =
                assertThrows(IllegalStateException.class, () -> DotExport.export(request(classes)));

        assertEquals("cannot draw the machine q.Broken through q.BrokenRuntime.structure()", failed.getMessage());
        assertTrue(failed.getCause() instanceof ExceptionInInitializerError, String.valueOf(failed.getCause()));
        assertEquals("no init", failed.getCause().getCause().getMessage());
        assertFalse(Files.exists(directory.resolve("dot")));
    }

    @Test
    void aClasspathWithoutTheCoreIsReported() throws Exception {
        Path classes = compile(Map.of("p.ThingsRuntime", companion("p", "ThingsRuntime", "")));
        publish(classes, List.of(graph("p.Things", "p")));

        IllegalStateException failed = assertThrows(IllegalStateException.class, () -> DotExport.export(
                new ExportRequest(classes, List.of(classes), directory.resolve("dot"))));

        assertTrue(failed.getMessage().contains("holds no usable graphmaquina-core"), failed.getMessage());
    }

    @Test
    void namesThatFoldTogetherAreRefusedOnAVolumeThatDoesNotTellThemApart() throws Exception {
        assumeTrue(CaseFolding.folds(directory), "the volume of the temporary directory tells names apart by case");
        // The facade Things nested in p.Outer and the facade Things of the package p.outer: their companions,
        // p.OuterThingsRuntime and p.outer.ThingsRuntime, are distinct files, their diagrams are not.
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("p.OuterThingsRuntime", companion("p", "OuterThingsRuntime", ""));
        sources.put("p.outer.ThingsRuntime", companion("p.outer", "ThingsRuntime", ""));
        Path classes = compile(sources);
        publish(classes, List.of(graph("p.Outer.Things", "p"), graph("p.outer.Things", "p.outer")));

        CodegenException refused = assertThrows(CodegenException.class, () -> DotExport.export(request(classes)));

        assertEquals(DiagnosticCode.SG603.name(), refused.code());
        assertTrue(refused.getMessage().startsWith(
                "the diagrams p.Outer.Things.dot and p.outer.Things.dot would take one file"), refused.getMessage());
        assertFalse(Files.exists(directory.resolve("dot")));
    }

    /**
     * A companion {@code pkg.name}, of a machine of two states and one edge whose {@code structure()} builds its
     * definition with the DSL, with {@code members} added to its body.
     */
    private static String companion(String pkg, String name, String members) {
        return """
                package %1$s;
                import io.github.graphmaquina.api.GraphDefinition;
                import io.github.graphmaquina.api.MachineKey;
                import io.github.graphmaquina.dsl.StateGraph;
                public final class %2$s {
                %3$s    public enum Status { NEW, DONE }
                    public enum Change { FINISH }
                    public static final class Thing { }
                    public static GraphDefinition<Thing, Status, Change> structure() {
                        return StateGraph.define(MachineKey.of(Thing.class, Status.class, Change.class))
                                .initial(Status.NEW).terminal(Status.DONE)
                                .statePolicy(Status.NEW, "finish it first")
                                .transition(Change.FINISH, Status.NEW, Status.DONE).endTransition()
                                .build();
                    }
                }
                """.formatted(pkg, name, members);
    }

    /** The index entry of the machine {@code facade}, declared in {@code pkg}. */
    private static GraphDescriptor graph(String facade, String pkg) {
        return new GraphDescriptor(facade, pkg, pkg + ".Thing", pkg + ".Status", List.of("NEW", "DONE"),
                pkg + ".Change", List.of("FINISH"), List.of(), new SourceLocation("src/" + facade + ".java", 1, 1));
    }

    private Path compile(Map<String, String> sources) {
        Path classes = directory.resolve("classes");
        List<Path> files = SourceFiles.write(directory.resolve("sources"), sources);
        SourceFiles.Compilation compilation = SourceFiles.compileStrict(files, classes);
        assertTrue(compilation.success(), () -> String.valueOf(compilation.messages()));
        return classes;
    }

    private static void publish(Path classes, List<GraphDescriptor> graphs) throws IOException {
        ProducerIndex.write(classes, new ExportIndex(GenerationFiles.SCHEMA_VERSION, ToolVersion.current(),
                "fx:things:main", new GenerationModel(graphs, List.of()), List.of()));
    }

    /** The request over {@code classes} and the classpath of this test, which holds {@code graphmaquina-core}. */
    private ExportRequest request(Path classes) {
        List<Path> classpath = new ArrayList<>();
        classpath.add(classes);
        classpath.addAll(SourceFiles.classpath());
        return new ExportRequest(classes, classpath, directory.resolve("dot"));
    }

    /** What the exporter of this test's own core renders for {@code companion}'s {@code structure()}. */
    private static String expected(Path classes, String companion) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
                DotExportTest.class.getClassLoader())) {
            GraphDefinition<?, ?, ?> definition =
                    (GraphDefinition<?, ?, ?>) loader.loadClass(companion).getMethod("structure").invoke(null);
            return GraphvizExporter.render(GraphView.of(definition));
        }
    }

    /** Writes a published index into a class output, as the completion of a compilation leaves it. */
    private static final class ProducerIndex {

        private ProducerIndex() {
        }

        static void write(Path classes, ExportIndex index) throws IOException {
            Path published = classes.resolve(GenerationFiles.PUBLISHED_INDEX);
            Files.createDirectories(published.getParent());
            ManifestCodec.writeExports(published, index);
        }
    }
}
