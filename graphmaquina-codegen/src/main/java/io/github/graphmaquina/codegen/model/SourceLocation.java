package io.github.graphmaquina.codegen.model;

import java.util.Objects;

/** A one-based origin in an original source file, used to anchor diagnostics on generated metadata. */
public record SourceLocation(String path, long line, long column) {

    public SourceLocation {
        Objects.requireNonNull(path, "path");
    }
}
