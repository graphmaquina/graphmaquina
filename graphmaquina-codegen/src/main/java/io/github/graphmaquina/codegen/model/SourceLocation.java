package io.github.graphmaquina.codegen.model;

import java.util.Objects;

/**
 * A one-based origin in an original source file, used to anchor diagnostics on generated metadata. The generator
 * writes {@code path} relative to the project directory, or absolute for a file on another root, and always with
 * forward slashes, so an index that publishes it reads the same whichever platform built it.
 */
public record SourceLocation(String path, long line, long column) {

    public SourceLocation {
        Objects.requireNonNull(path, "path");
    }
}
