package io.stategraph.codegen;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One invocation of the generator: the sources it reads, the classpath it resolves headers against, where it
 * writes generated sources and its manifests, and the identity of the project and source set it runs for.
 *
 * <p>{@code compilationId} identifies a project and source set, for example {@code io.stategraph:example:main};
 * it never carries paths or timestamps. Every path here is an execution path and may be absolute; the paths
 * published in an {@link io.stategraph.codegen.manifest.ExportIndex} are relative instead. The directories in
 * {@code sources} must not overlap {@code sourcesOutput}, {@code processorSourcesOutput}, {@code classesOutput}
 * or {@code workDirectory}.
 */
public record GenerationRequest(
        Path projectDirectory,
        List<Path> sources,
        List<Path> classpath,
        Path sourcesOutput,
        Path processorSourcesOutput,
        Path classesOutput,
        Path workDirectory,
        String compilationId) {

    public GenerationRequest {
        Objects.requireNonNull(projectDirectory, "projectDirectory");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(classpath, "classpath");
        Objects.requireNonNull(sourcesOutput, "sourcesOutput");
        Objects.requireNonNull(processorSourcesOutput, "processorSourcesOutput");
        Objects.requireNonNull(classesOutput, "classesOutput");
        Objects.requireNonNull(workDirectory, "workDirectory");
        Objects.requireNonNull(compilationId, "compilationId");
        if (compilationId.isBlank()) {
            throw new IllegalArgumentException("compilationId must not be blank");
        }
        sources = List.copyOf(sources);
        classpath = List.copyOf(classpath);
        requireNoOverlap(sources, sourcesOutput, processorSourcesOutput, classesOutput, workDirectory);
    }

    private static void requireNoOverlap(List<Path> sources, Path... outputs) {
        for (Path source : sources) {
            Path normalizedSource = source.normalize();
            for (Path output : outputs) {
                Path normalizedOutput = output.normalize();
                if (normalizedSource.equals(normalizedOutput)
                        || normalizedSource.startsWith(normalizedOutput)
                        || normalizedOutput.startsWith(normalizedSource)) {
                    throw new IllegalArgumentException(
                        "source directory " + source + " overlaps output directory " + output);
                }
            }
        }
    }
}
