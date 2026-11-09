package io.github.graphmaquina.codegen;

import java.nio.file.Path;
import java.util.List;
import lombok.NonNull;

/**
 * One invocation of the generator's {@code export-dot} operation: the class output of a compilation the StateGraph
 * build completed, which holds its published index, the runtime classpath its machines are loaded from, and the
 * directory their diagrams are written into. Every path is an execution path and may be absolute.
 *
 * @param classesOutput where javac wrote the classes of the compilation, its published index among them
 * @param classpath the runtime classpath of the compilation: its class output, {@code graphmaquina-core} and every
 *        class its machines load
 * @param outputDirectory the directory the diagrams are written into, which the export owns
 */
public record ExportRequest(@NonNull Path classesOutput, @NonNull List<Path> classpath,
        @NonNull Path outputDirectory) {

    /** Copies the classpath, so a request never reflects a later change of the list it was built from. */
    public ExportRequest {
        classpath = List.copyOf(classpath);
    }
}
