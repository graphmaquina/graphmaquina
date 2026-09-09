package io.stategraph.codegen.manifest;

import java.util.List;
import java.util.Objects;

/**
 * The combined manifest for one compilation: the local {@link ExportIndex} being produced, the dependency
 * indexes it was resolved against, where the annotation processor's own inventory lives, and the source files
 * this compilation owns (relative to their source root).
 */
public record CompileManifest(int schemaVersion, String toolVersion,
        ExportIndex local, List<ExportIndex> dependencies,
        String processorInventory, List<String> ownedSources) {

    public CompileManifest {
        Objects.requireNonNull(toolVersion, "toolVersion");
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(processorInventory, "processorInventory");
        Objects.requireNonNull(ownedSources, "ownedSources");
        dependencies = List.copyOf(dependencies);
        ownedSources = List.copyOf(ownedSources);
    }
}
