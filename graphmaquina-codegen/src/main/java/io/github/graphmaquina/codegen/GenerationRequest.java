package io.github.graphmaquina.codegen;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One invocation of the generator: the sources it reads, the classpath it resolves headers against, where it
 * writes generated sources and its manifests, the identity of the project and source set it runs for, and the
 * release and the encoding of the compilation that follows it.
 *
 * <p>{@code compilationId} identifies a project and source set, for example {@code io.github.graphmaquina:example:main};
 * it never carries paths or timestamps. Every path here is an execution path and may be absolute; the paths
 * published in an {@link io.github.graphmaquina.codegen.manifest.ExportIndex} are relative instead. The directories in
 * {@code sources} must not overlap {@code sourcesOutput}, {@code processorSourcesOutput}, {@code classesOutput}
 * or {@code workDirectory}; they are compared as absolute, normalized paths, a relative one resolved against the
 * working directory as every other use of it is, so spelling one side relatively cannot hide an overlap.</p>
 *
 * <p>{@code release} and {@code encoding} are the ones javac compiles the sources with. The generator parses the
 * original sources at that release and resolves the JDK types they name against it, reads them in that encoding,
 * and writes what it generates in it too. A request that names neither, as every request did before the two
 * existed, is read at release {@value #DEFAULT_RELEASE} in UTF-8. No request carries {@code --enable-preview}: the
 * generator parses the original sources without it, whatever javac is given. An original source using a preview
 * feature that javac's parser checks, such as string templates on JDK 21, is refused with {@code SG601}; one using
 * a preview feature javac checks only after parsing, such as primitive patterns at release 25, is read like any
 * other source.</p>
 *
 * @param projectDirectory the directory the compilation belongs to, which diagnostics are relative to
 * @param sources the original sources: directories walked for their Java files, or files read as they are given
 * @param classpath the compile classpath headers and published indexes are resolved against
 * @param sourcesOutput where the generated sources are written
 * @param processorSourcesOutput where the annotation processor writes its own sources
 * @param classesOutput where javac writes the classes of the compilation
 * @param workDirectory where the manifests and the inventories of the compilation live
 * @param compilationId the identity of the project and source set
 * @param release the Java release javac compiles the sources at
 * @param encoding the charset javac reads the sources in
 */
public record GenerationRequest(
        Path projectDirectory,
        List<Path> sources,
        List<Path> classpath,
        Path sourcesOutput,
        Path processorSourcesOutput,
        Path classesOutput,
        Path workDirectory,
        String compilationId,
        int release,
        Charset encoding) {

    /** The release of a request that names none: Java 21, the lowest release StateGraph runs on. */
    public static final int DEFAULT_RELEASE = 21;

    /** The encoding of a request that names none. */
    public static final Charset DEFAULT_ENCODING = StandardCharsets.UTF_8;

    public GenerationRequest {
        Objects.requireNonNull(projectDirectory, "projectDirectory");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(classpath, "classpath");
        Objects.requireNonNull(sourcesOutput, "sourcesOutput");
        Objects.requireNonNull(processorSourcesOutput, "processorSourcesOutput");
        Objects.requireNonNull(classesOutput, "classesOutput");
        Objects.requireNonNull(workDirectory, "workDirectory");
        Objects.requireNonNull(compilationId, "compilationId");
        Objects.requireNonNull(encoding, "encoding");
        if (compilationId.isBlank()) {
            throw new IllegalArgumentException("compilationId must not be blank");
        }
        sources = List.copyOf(sources);
        classpath = List.copyOf(classpath);
        requireNoOverlap(sources, sourcesOutput, processorSourcesOutput, classesOutput, workDirectory);
    }

    /** A request that names neither a release nor an encoding: it is read at release 21 in UTF-8. */
    public GenerationRequest(Path projectDirectory, List<Path> sources, List<Path> classpath, Path sourcesOutput,
            Path processorSourcesOutput, Path classesOutput, Path workDirectory, String compilationId) {
        this(projectDirectory, sources, classpath, sourcesOutput, processorSourcesOutput, classesOutput,
            workDirectory, compilationId, DEFAULT_RELEASE, DEFAULT_ENCODING);
    }

    private static void requireNoOverlap(List<Path> sources, Path... outputs) {
        for (Path source : sources) {
            Path normalizedSource = source.toAbsolutePath().normalize();
            for (Path output : outputs) {
                Path normalizedOutput = output.toAbsolutePath().normalize();
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
