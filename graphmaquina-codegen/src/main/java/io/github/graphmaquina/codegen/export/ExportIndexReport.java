package io.github.graphmaquina.codegen.export;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.ToolVersion;
import io.github.graphmaquina.codegen.manifest.DependencyExports;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import io.github.graphmaquina.codegen.model.SymbolDescriptor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;

/**
 * The report of the export indexes a classpath carries, which the {@code list-exports} operation prints: for each
 * entry that publishes {@code META-INF/graphmaquina/exports.json}, in classpath order and once per entry, its location,
 * the tool version that wrote it, its machines and its symbols, then one line for each reason {@code SG602} would
 * refuse it in a compilation of this tool: an index that cannot be read, another tool version, or a machine that an
 * earlier entry describes. Unlike a compilation, the report reads every entry and stops at none of them.
 */
public final class ExportIndexReport {

    private static final String HEADER = "graphmaquina export index: ";
    private static final String REFUSED = "  refused by " + DiagnosticCode.SG602.name() + ": ";
    private static final String NONE = "graphmaquina: no classpath entry carries a StateGraph export index";

    private ExportIndexReport() {
    }

    /**
     * The lines of the report for {@code classpath}: four for each entry that publishes an index, then its refusals;
     * an entry that the classpath lists again, in any spelling of the same path, is reported once, and one that
     * publishes nothing is left out, as a compilation skips it. A classpath without any index gives one line that
     * says so.
     *
     * @throws IOException if an entry exists but cannot be read, naming that entry
     */
    public static List<String> report(@NonNull List<Path> classpath) throws IOException {
        String toolVersion = ToolVersion.current();
        List<String> lines = new ArrayList<>();
        Set<Path> visited = new HashSet<>();
        Map<String, Path> describedBy = new HashMap<>();
        for (Path listed : classpath) {
            Path entry = listed.toAbsolutePath().normalize();
            if (!visited.add(entry)) {
                continue;
            }
            Optional<ExportIndex> index;
            try {
                index = DependencyExports.published(entry);
            } catch (CodegenException unreadable) {
                lines.add(HEADER + entry);
                lines.add(REFUSED + unreadable.getMessage());
                continue;
            }
            if (index.isPresent()) {
                describe(entry, index.get(), toolVersion, describedBy, lines);
            }
        }
        if (lines.isEmpty()) {
            lines.add(NONE);
        }
        return List.copyOf(lines);
    }

    /** Adds the lines of {@code index}, which {@code entry} publishes, and of its refusals to {@code lines}. */
    private static void describe(Path entry, ExportIndex index, String toolVersion, Map<String, Path> describedBy,
                                 List<String> lines) {
        List<String> machines = new ArrayList<>();
        for (GraphDescriptor graph : index.model().graphs()) {
            machines.add(graph.facade());
        }
        List<String> symbols = new ArrayList<>();
        for (SymbolDescriptor symbol : index.model().symbols()) {
            symbols.add(symbol.qualifiedName());
        }
        lines.add(HEADER + entry);
        lines.add("  tool version: " + index.toolVersion());
        lines.add("  machines: " + listed(machines));
        lines.add("  symbols: " + listed(symbols));
        if (!index.toolVersion().equals(toolVersion)) {
            lines.add(REFUSED + "written by tool version " + index.toolVersion() + "; this tool is " + toolVersion);
        }
        for (String machine : machines) {
            Path first = describedBy.computeIfAbsent(machine, (String facade) -> entry);
            if (!first.equals(entry)) {
                lines.add(REFUSED + "the machine " + machine + " is also described by the export index of " + first);
            }
        }
    }

    /** {@code names} joined by commas, or {@code none}. */
    private static String listed(List<String> names) {
        return names.isEmpty() ? "none" : String.join(", ", names);
    }
}
