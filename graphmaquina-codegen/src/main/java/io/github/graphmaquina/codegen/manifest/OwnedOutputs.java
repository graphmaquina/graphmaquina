package io.github.graphmaquina.codegen.manifest;

import java.util.List;
import java.util.Objects;

/**
 * The inventory of outputs one generation/compilation cycle owns: the sources the annotation processor
 * generated, the class files of the types the cycle generated, the processor's and the generator's alike except
 * the carrier, the export index it published, and the carrier's class files, which completing the compilation
 * deletes so that no jar carries them.
 *
 * <p>Every entry is a path relative to the output root it belongs to, written with forward slashes and sorted,
 * so an inventory identifies files without ever naming a directory of the machine that produced it:
 * {@code sources} are relative to {@code processorSourcesOutput}, and {@code binaries}, {@code resources} and
 * {@code excludedBinaries} to {@code classesOutput}. The pending inventory written before javac runs also lists,
 * among its {@code sources}, the sources the generator wrote, relative to {@code sourcesOutput}, so that completing
 * the compilation can tell which generation it was prepared for. Besides the files an inventory lists, the next
 * preparation only removes the published and the pending export index and, after an interrupted cycle, the class
 * files it attributes, by reading them, to the types the inventory names; any other file is never touched,
 * whatever its name looks like.</p>
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
