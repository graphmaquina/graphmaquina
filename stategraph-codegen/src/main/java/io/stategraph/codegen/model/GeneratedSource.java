package io.stategraph.codegen.model;

import java.util.Objects;

/** One emitted compilation unit, ready to be written under its qualified name's package path. */
public record GeneratedSource(String qualifiedName, String content) {

    public GeneratedSource {
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        Objects.requireNonNull(content, "content");
    }
}
