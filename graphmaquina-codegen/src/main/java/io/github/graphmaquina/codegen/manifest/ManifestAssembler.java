package io.github.graphmaquina.codegen.manifest;

import io.github.graphmaquina.codegen.GenerationRequest;
import io.github.graphmaquina.codegen.ToolVersion;
import io.github.graphmaquina.codegen.emit.ApiEmitter;
import io.github.graphmaquina.codegen.model.GeneratedSource;
import io.github.graphmaquina.codegen.model.GenerationModel;
import java.util.List;
import java.util.Objects;

/**
 * Assembles the manifest of one compilation: the index this run produces, the indexes its classpath publishes,
 * where the annotation processor writes its own inventory and which source files this generation owns.
 *
 * <p>The local index is what this compilation will publish once javac has verified it, so it carries the model
 * and the canonical names of every generated annotation. The paths of the manifest are the ones the build needs
 * to execute: {@code processorInventory} is the absolute path of the file the processor writes, while
 * {@code ownedSources} are relative to the generated source root, since only that root's contents are owned.</p>
 */
public final class ManifestAssembler {

    private static final int SCHEMA_VERSION = 1;
    private static final String PROCESSOR_INVENTORY = "processor-inventory.json";

    private ManifestAssembler() {
    }

    /** The manifest of {@code request}, describing {@code model}, {@code sources} and {@code dependencies}. */
    public static CompileManifest assemble(GenerationRequest request, GenerationModel model,
            List<GeneratedSource> sources, List<ExportIndex> dependencies) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(dependencies, "dependencies");
        String toolVersion = ToolVersion.current();
        ExportIndex local = new ExportIndex(SCHEMA_VERSION, toolVersion, request.compilationId(), model,
            ApiEmitter.annotationTypes(model));
        String processorInventory =
            request.workDirectory().resolve(PROCESSOR_INVENTORY).toAbsolutePath().toString();
        return new CompileManifest(SCHEMA_VERSION, toolVersion, local, dependencies, processorInventory,
            ownedSources(sources));
    }

    /** The generated files, relative to the generated source root and always written with forward slashes. */
    private static List<String> ownedSources(List<GeneratedSource> sources) {
        return sources.stream()
                .map(source -> source.qualifiedName().replace('.', '/') + ".java")
                .sorted()
                .toList();
    }
}
