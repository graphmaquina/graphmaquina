package io.github.graphmaquina.codegen.manifest;

import java.nio.file.Path;
import lombok.NonNull;

/**
 * An export index together with the classpath entry it was read from, so that a message about the index can name
 * the jar or directory a user can act on. {@code entry} is that classpath entry as it arrived, made absolute and
 * normalized.
 */
public record PublishedIndex(@NonNull Path entry, @NonNull ExportIndex index) {
}
