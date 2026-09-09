package io.stategraph.codegen.manifest;

import java.util.List;
import java.util.Objects;

/**
 * The inventory of outputs one generation/compilation cycle owns, relative to their respective output roots:
 * generated sources, the class files and resources produced from them, and the carrier binaries a packager
 * must exclude.
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
