package io.github.graphmaquina.codegen.model;

import lombok.NonNull;

/**
 * A one-based origin in an original source file, used to anchor diagnostics on generated metadata. The generator
 * writes {@code path} relative to the project directory, or absolute for a file on another root, and always with
 * forward slashes, so an index that publishes it reads the same whichever platform built it.
 */
public record SourceLocation(@NonNull String path, long line, long column) {

    public SourceLocation {
    }
}
