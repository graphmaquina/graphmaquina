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
     * @throws CodegenException if the request's sources, its classpath indexes or its outputs are not usable
     * @throws IOException if the sources or the manifests cannot be read or written
     */
    public static GenerationResult generate(GenerationRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        GenerationModel model = Bootstrap.inspect(request);
        List<GeneratedSource> sources = ApiEmitter.emit(model, request.compilationId());
        List<ExportIndex> dependencies = DependencyExports.read(request.classpath());
        CompileManifest manifest = ManifestAssembler.assemble(request, model, sources, dependencies);
        return GenerationOutput.publish(request, sources, manifest);
    }
}
