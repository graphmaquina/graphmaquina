package io.github.graphmaquina.codegen.manifest;

import io.github.graphmaquina.codegen.model.GenerationModel;
import java.util.List;
import lombok.NonNull;

/**
 * One producer's publishable graph model: the generated model itself plus the exact generated annotation type
 * names a dependent must recognize. The only paths inside {@link #model()} are the origins of its declarations,
 * relative to the producer's project directory unless a source lies on another root, such as another drive, whose
 * path stays absolute; apart from those, this index carries no execution paths, so it can be published alongside
 * the producer's compiled classes and read back by a dependent.
 */
public record ExportIndex(int schemaVersion, @NonNull String toolVersion, @NonNull String compilationId,
        @NonNull GenerationModel model, @NonNull List<String> annotationTypes) {

    public ExportIndex {
        annotationTypes = List.copyOf(annotationTypes);
    }
}
