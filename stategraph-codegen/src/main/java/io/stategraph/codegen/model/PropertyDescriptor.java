package io.stategraph.codegen.model;

import java.util.Objects;

/** The resolved accessor for a {@code @GraphProperty} member: its logical name and the declaration it reads. */
public record PropertyDescriptor(String declaringType, String logicalName,
        String declarationName, boolean directGetter, SourceLocation origin) {

    public PropertyDescriptor {
        Objects.requireNonNull(declaringType, "declaringType");
        Objects.requireNonNull(logicalName, "logicalName");
        Objects.requireNonNull(declarationName, "declarationName");
        Objects.requireNonNull(origin, "origin");
    }
}
