package io.stategraph.codegen;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** The outcome of one {@link GenerationRequest}: the sources it wrote and where its combined manifest lives. */
public record GenerationResult(List<Path> generatedSources, Path annotationManifest) {

    public GenerationResult {
        Objects.requireNonNull(generatedSources, "generatedSources");
        Objects.requireNonNull(annotationManifest, "annotationManifest");
        generatedSources = List.copyOf(generatedSources);
    }
}
