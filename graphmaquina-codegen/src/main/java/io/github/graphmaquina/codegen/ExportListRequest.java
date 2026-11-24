package io.github.graphmaquina.codegen;

import java.nio.file.Path;
import java.util.List;
import lombok.NonNull;

/**
 * One invocation of the generator's {@code list-exports} operation: the classpath whose export indexes it reports, as
 * {@link io.github.graphmaquina.codegen.export.ExportIndexReport} describes. Every path is an execution path and may be
 * absolute.
 *
 * @param classpath the compile classpath of a compilation, without its own class output
 */
public record ExportListRequest(@NonNull List<Path> classpath) {

    /** Copies the classpath, so a request never reflects a later change of the list it was built from. */
    public ExportListRequest {
        classpath = List.copyOf(classpath);
    }
}
