package io.github.graphmaquina.codegen.manifest;

import io.github.graphmaquina.codegen.model.GenerationModel;
import java.util.List;
import java.util.Objects;

/**
 * One producer's publishable graph model: the generated model itself plus the exact generated annotation type
 * names a dependent must recognize. Paths inside {@link #model()} are relative; this index carries no execution
 * paths, so it can be published alongside the producer's compiled classes and read back by a dependent.
 */
public record ExportIndex(int schemaVersion, String toolVersion, String compilationId,
        GenerationModel model, List<String> annotationTypes) {

    public ExportIndex {
        Objects.requireNonNull(toolVersion, "toolVersion");
        Objects.requireNonNull(compilationId, "compilationId");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(annotationTypes, "annotationTypes");
        annotationTypes = List.copyOf(annotationTypes);
    }
}
