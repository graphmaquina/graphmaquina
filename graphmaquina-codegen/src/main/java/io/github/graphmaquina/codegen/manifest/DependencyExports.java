package io.github.graphmaquina.codegen.manifest;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.codegen.CodegenException;
import io.github.graphmaquina.codegen.GenerationFiles;
import io.github.graphmaquina.codegen.ToolVersion;
import io.github.graphmaquina.codegen.model.GraphDescriptor;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ProviderNotFoundException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;

/**
 * Reads the published export index of every classpath entry that carries one.
 *
 * <p>A producer publishes {@code META-INF/graphmaquina/exports.json} beside its classes, in a jar or in a directory
 * of class files, and this class aggregates those indexes for one compilation. Only the published name is read:
 * the {@code pending-exports.json} a processor writes during its own compilation belongs to that run and is
 * promoted by its build, never consumed here. An entry that carries an index of another schema or of another tool
 * version is refused rather than interpreted with the wrong rules, and so is a machine that the indexes of two
 * different entries describe: they are two copies of one producer, and no rule here says which one is current.</p>
 */
public final class DependencyExports {

    private DependencyExports() {
    }

    /**
     * The indexes published on {@code classpath}, each with its entry made absolute and normalized, ordered by
     * {@code compilationId}. An entry that the classpath lists again, in any spelling of the same path, is read
     * once. An entry that does not exist, that is neither a directory nor a regular file, or that holds no
     * published index carries nothing and is skipped, and so is a regular file that does not open as an archive,
     * unless its name ends in {@code .jar} or {@code .zip}: such a file cannot be read.
     *
     * @throws CodegenException {@code SG602} if an entry carries an index this tool cannot read or that another
     *     tool version wrote, naming that entry, or if the indexes of two different entries describe one machine,
     *     naming both in classpath order
     * @throws IOException if an entry exists but cannot be read, naming that entry
     */
    public static List<PublishedIndex> read(@NonNull List<Path> classpath) throws IOException {
        String toolVersion = ToolVersion.current();
        List<PublishedIndex> indexes = new ArrayList<>();
        Set<Path> visited = new HashSet<>();
        Map<String, Path> describedBy = new HashMap<>();
        for (Path listed : classpath) {
            Path entry = listed.toAbsolutePath().normalize();
            if (!visited.add(entry)) {
                continue;
            }
            Optional<ExportIndex> index;
            try {
                index = read(entry);
            } catch (IOException failure) {
                throw new IOException("cannot read the classpath entry " + entry, failure);
            }
            if (index.isPresent()) {
                ExportIndex current = compatible(index.get(), entry, toolVersion);
                refuseSecondDescription(current, entry, describedBy);
                indexes.add(new PublishedIndex(entry, current));
            }
        }
        indexes.sort(Comparator.comparing((PublishedIndex published) -> published.index().compilationId()));
        return List.copyOf(indexes);
    }

    private static Optional<ExportIndex> read(Path entry) throws IOException {
        if (Files.isDirectory(entry)) {
            Path published = entry.resolve(GenerationFiles.PUBLISHED_INDEX);
            return Files.isRegularFile(published) ? Optional.of(readIndex(published, entry)) : Optional.empty();
        }
        if (!Files.isRegularFile(entry)) {
            return Optional.empty();
        }
        try (FileSystem archive = FileSystems.newFileSystem(entry)) {
            Path published = archive.getPath(GenerationFiles.PUBLISHED_INDEX);
            return Files.isRegularFile(published)
                ? Optional.of(readIndex(published, entry))
                : Optional.empty();
        } catch (ProviderNotFoundException notAnArchive) {
            return Optional.empty();
        }
    }

    /** Reads one index, reporting a codec failure against the classpath entry the caller knows. */
    private static ExportIndex readIndex(Path published, Path entry) throws IOException {
        try {
            return ManifestCodec.readExports(published);
        } catch (CodegenException failure) {
            throw new CodegenException(DiagnosticCode.SG602.name(),
                "the StateGraph export index published by " + entry + " cannot be read: " + failure.getMessage());
        }
    }

    private static ExportIndex compatible(ExportIndex index, Path entry, String toolVersion) {
        if (!index.toolVersion().equals(toolVersion)) {
            throw new CodegenException(DiagnosticCode.SG602.name(), "the StateGraph export index published by " + entry
                + " was written by tool version " + index.toolVersion() + "; this tool is " + toolVersion);
        }
        return index;
    }

    /** Records {@code entry} as the describer of each machine of {@code index}, refusing one that another entry has. */
    private static void refuseSecondDescription(ExportIndex index, Path entry, Map<String, Path> describedBy) {
        for (GraphDescriptor graph : index.model().graphs()) {
            Path first = describedBy.computeIfAbsent(graph.facade(), (String facade) -> entry);
            if (!first.equals(entry)) {
                throw new CodegenException(DiagnosticCode.SG602.name(), "the machine " + graph.facade()
                    + " is described by the export indexes of " + first + " and " + entry
                    + ": keep one artifact of its producer on the classpath");
            }
        }
    }
}
