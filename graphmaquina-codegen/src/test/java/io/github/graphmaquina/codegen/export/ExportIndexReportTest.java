package io.github.graphmaquina.codegen.export;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.annotation.internal.SymbolKind;
import io.github.graphmaquina.codegen.ToolVersion;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import io.github.graphmaquina.codegen.model.GenerationModel;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import io.github.graphmaquina.codegen.model.SourceLocation;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import io.github.graphmaquina.codegen.source.ProducerJars;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The report of the export indexes a classpath carries: for each entry that publishes one, in classpath order, its
 * location, the tool version that wrote it, its machines and its symbols, then each reason {@code SG602} would refuse
 * it in a compilation: another tool version, an index that cannot be read, or a machine an earlier entry describes.
 */
class ExportIndexReportTest {

    private static final String PUBLISHED = "META-INF/graphmaquina/exports.json";

    @TempDir
    Path directory;

    private static ExportIndex index(String toolVersion, String facade, List<String> symbols) {
        String name = facade.substring(facade.lastIndexOf('.') + 1);
        GraphDescriptor graph = new GraphDescriptor(facade, "fx", "fx.Document", "fx." + name + "Status",
                List.of("DRAFT"), "fx." + name + "Event", List.of("PUBLISH"), List.of(),
                new SourceLocation("src/fx/" + name + ".java", 1, 1));
        List<SymbolDescriptor> described = symbols.stream().map((String symbol) -> new SymbolDescriptor(symbol,
                "fx." + name + "Guards", "ready", Set.of(SymbolKind.GUARD), List.of(), Optional.empty(),
                new SourceLocation("src/fx/" + name + "Guards.java", 1, 1))).toList();
        return new ExportIndex(2, toolVersion, "io.github.graphmaquina:" + name.toLowerCase() + ":main",
                new GenerationModel(List.of(graph), described), List.of(facade + "Graph.Transition"));
    }

    @Test
    void reportsEachIndexWithItsToolVersionMachinesAndSymbols() throws IOException {
        Path docs = ProducerJars.archived(directory.resolve("docs.jar"), PUBLISHED,
                index(ToolVersion.current(), "fx.Docs", List.of("fx.DocsGuardsRefs.Ready")));
        Path orders = ProducerJars.published(directory.resolve("orders"), "exports.json",
                index(ToolVersion.current(), "fx.Orders", List.of()));

        List<String> report = ExportIndexReport.report(List.of(docs, directory.resolve("absent"), orders));

        assertEquals(List.of(
                "graphmaquina export index: " + docs.toAbsolutePath().normalize(),
                "  tool version: " + ToolVersion.current(),
                "  machines: fx.Docs",
                "  symbols: fx.DocsGuardsRefs.Ready",
                "graphmaquina export index: " + orders.toAbsolutePath().normalize(),
                "  tool version: " + ToolVersion.current(),
                "  machines: fx.Orders",
                "  symbols: none"), report);
    }

    @Test
    void marksAnIndexOfAnotherToolVersionAsRefused() throws IOException {
        Path docs = ProducerJars.archived(directory.resolve("docs.jar"), PUBLISHED,
                index("0.0.1", "fx.Docs", List.of()));

        List<String> report = ExportIndexReport.report(List.of(docs));

        assertEquals("  refused by " + DiagnosticCode.SG602.name() + ": written by tool version 0.0.1; this tool is "
                + ToolVersion.current(), report.get(report.size() - 1));
    }

    @Test
    void marksAMachineThatAnEarlierEntryDescribesAsRefused() throws IOException {
        Path first = ProducerJars.archived(directory.resolve("a/docs.jar"), PUBLISHED,
                index(ToolVersion.current(), "fx.Docs", List.of()));
        Path second = ProducerJars.archived(directory.resolve("b/docs.jar"), PUBLISHED,
                index(ToolVersion.current(), "fx.Docs", List.of()));

        List<String> report = ExportIndexReport.report(List.of(first, second));

        assertEquals("  refused by " + DiagnosticCode.SG602.name() + ": the machine fx.Docs is also described by the"
                + " export index of " + first.toAbsolutePath().normalize(), report.get(report.size() - 1));
    }

    @Test
    void marksAnIndexThatCannotBeReadAsRefused() throws IOException {
        Path broken = directory.resolve("broken");
        Files.createDirectories(broken.resolve("META-INF/graphmaquina"));
        Files.writeString(broken.resolve(PUBLISHED), "{ not json", StandardCharsets.UTF_8);

        List<String> report = ExportIndexReport.report(List.of(broken));

        assertEquals("graphmaquina export index: " + broken.toAbsolutePath().normalize(), report.get(0));
        assertEquals(true, report.get(1).startsWith("  refused by " + DiagnosticCode.SG602.name()
                + ": the StateGraph export index published by "), report.get(1));
    }

    @Test
    void reportsNothingForAClasspathWithoutIndexes() throws IOException {
        Path empty = Files.createDirectories(directory.resolve("classes"));

        assertEquals(List.of("graphmaquina: no classpath entry carries a StateGraph export index"),
                ExportIndexReport.report(List.of(empty, directory.resolve("absent"))));
    }
}
