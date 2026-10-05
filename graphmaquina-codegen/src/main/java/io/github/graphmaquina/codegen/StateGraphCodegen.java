package io.github.graphmaquina.codegen;

import io.github.graphmaquina.codegen.emit.ApiEmitter;
import io.github.graphmaquina.codegen.manifest.CompileManifest;
import io.github.graphmaquina.codegen.manifest.DependencyExports;
import io.github.graphmaquina.codegen.manifest.ExportIndex;
import io.github.graphmaquina.codegen.manifest.ManifestAssembler;
import io.github.graphmaquina.codegen.model.GeneratedSource;
import io.github.graphmaquina.codegen.model.GenerationModel;
import io.github.graphmaquina.codegen.output.GenerationOutput;
import io.github.graphmaquina.codegen.source.Bootstrap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The entry point of the generator: reads one request's original sources, emits the typed graph API they declare
 * and publishes it together with the manifest of the compilation that follows.
 *
 * <p>Nothing here compiles or loads the application. The sources are only parsed, the vocabulary is emitted from
 * what their declarations say, and javac verifies the result afterwards, when the annotation processor runs.</p>
 */
public final class StateGraphCodegen {

    private StateGraphCodegen() {
    }

    /**
     * Generates the typed graph API of {@code request} and returns the sources it wrote and its manifest.
     *
     * <p>The dependencies of the manifest are the indexes the classpath publishes, except the one in this
     * compilation's own class output: a caller may put that output on the classpath as its previous run left it,
     * and its index describes this compilation, never a dependency of it.</p>
     *
     * @throws CodegenException if the request's sources, its classpath indexes or its outputs are not usable
     * @throws IOException if the sources or the manifests cannot be read or written
     */
    public static GenerationResult generate(GenerationRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        GenerationModel model = Bootstrap.inspect(request);
        List<GeneratedSource> sources = ApiEmitter.emit(model, request.compilationId());
        List<ExportIndex> dependencies = DependencyExports.read(dependencyClasspath(request));
        CompileManifest manifest = ManifestAssembler.assemble(request, model, sources, dependencies);
        return GenerationOutput.publish(request, sources, manifest);
    }

    /** The classpath of {@code request} without its own class output, compared as absolute, normalized paths. */
    private static List<Path> dependencyClasspath(GenerationRequest request) {
        Path classes = request.classesOutput().toAbsolutePath().normalize();
        List<Path> entries = new ArrayList<>();
        for (Path entry : request.classpath()) {
            if (!entry.toAbsolutePath().normalize().equals(classes)) {
                entries.add(entry);
            }
        }
        return entries;
    }
}
