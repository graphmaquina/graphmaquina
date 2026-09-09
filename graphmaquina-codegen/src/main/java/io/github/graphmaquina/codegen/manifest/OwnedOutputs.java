package io.github.graphmaquina.codegen.manifest;

import java.util.List;
import java.util.Objects;

/**
 * The inventory of outputs one generation/compilation cycle owns: the sources the annotation processor
 * generated, the class files and resources produced from them, and the carrier binaries a packager must
 * exclude.
 *
 * <p>Every entry is a path relative to the output root it belongs to, written with forward slashes and sorted,
 * so an inventory identifies files without ever naming a directory of the machine that produced it:
 * {@code sources} are relative to {@code processorSourcesOutput}, and {@code binaries}, {@code resources} and
 * {@code excludedBinaries} to {@code classesOutput}. The inventory is the only authority on what the next
 * compilation may retire; a file it does not list is never touched, whatever its name looks like.</p>
 */
public record OwnedOutputs(int schemaVersion, List<String> sources,
        List<String> binaries, List<String> resources, List<String> excludedBinaries) {

    public OwnedOutputs {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(binaries, "binaries");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(excludedBinaries, "excludedBinaries");
        sources = List.copyOf(sources);
        binaries = List.copyOf(binaries);
        resources = List.copyOf(resources);
        excludedBinaries = List.copyOf(excludedBinaries);
    }
}
