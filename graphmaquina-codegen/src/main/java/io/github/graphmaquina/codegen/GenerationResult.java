package io.github.graphmaquina.codegen;

import java.nio.file.Path;
import java.util.List;
import lombok.NonNull;

/** The outcome of one {@link GenerationRequest}: the sources it wrote and where its combined manifest lives. */
public record GenerationResult(@NonNull List<Path> generatedSources, @NonNull Path annotationManifest) {

    public GenerationResult {
        generatedSources = List.copyOf(generatedSources);
    }
}
