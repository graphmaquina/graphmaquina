package io.github.graphmaquina.codegen.manifest;

import java.util.List;
import lombok.NonNull;

/**
 * The combined manifest for one compilation: the local {@link ExportIndex} being produced, the indexes its
 * classpath publishes, which the annotation processor resolves the compilation against, where the annotation
 * processor's own inventory lives, and the source files this compilation owns (relative to their source root).
 */
public record CompileManifest(int schemaVersion, @NonNull String toolVersion,
        @NonNull ExportIndex local, @NonNull List<ExportIndex> dependencies,
        @NonNull String processorInventory, @NonNull List<String> ownedSources) {

    public CompileManifest {
        dependencies = List.copyOf(dependencies);
        ownedSources = List.copyOf(ownedSources);
    }
}
