package io.github.graphmaquina.codegen.manifest;

import java.util.List;
import lombok.NonNull;

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
 * the compilation can tell which generation it was prepared for and, after an interrupted cycle, the next
 * preparation still knows the units that generation owned. Since that list mixes two roots, a preparation never
 * removes a source because a pending inventory lists it: it removes from {@code processorSourcesOutput} the sources
 * of the sealed inventory, all of them the processor's, but none that is, by its file key or else its real path, a
 * source the current generation writes, and the generator retires its own from {@code sourcesOutput}. Besides those
 * sources and the files the other lists name, the next preparation only removes the published and the pending export
 * index and, after an interrupted cycle, the sources of the types the processor inventory names, with the same
 * exception, and the class files it attributes, by reading them, to the types whose sources the inventories list and
 * the types the processor inventory names; any other file is never touched, whatever its name looks like.</p>
 */
public record OwnedOutputs(int schemaVersion, @NonNull List<String> sources,
        @NonNull List<String> binaries, @NonNull List<String> resources, @NonNull List<String> excludedBinaries) {

    public OwnedOutputs {
        sources = List.copyOf(sources);
        binaries = List.copyOf(binaries);
        resources = List.copyOf(resources);
        excludedBinaries = List.copyOf(excludedBinaries);
    }
}
