package io.github.graphmaquina.codegen.model;

import lombok.NonNull;

/** One emitted compilation unit, ready to be written under its qualified name's package path. */
public record GeneratedSource(@NonNull String qualifiedName, @NonNull String content) {

    public GeneratedSource {
    }
}
