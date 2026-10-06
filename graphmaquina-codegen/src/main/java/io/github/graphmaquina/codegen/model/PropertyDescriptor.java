package io.github.graphmaquina.codegen.model;

import java.util.Objects;

/**
 * The property a {@code @GraphProperty} member exports: its logical name and the marked declaration it was read
 * from, a field or a getter, with the type that declares it. No accessor is resolved here: the annotation processor
 * looks for the readers and writers later.
 */
public record PropertyDescriptor(String declaringType, String logicalName,
        String declarationName, boolean directGetter, SourceLocation origin) {

    public PropertyDescriptor {
        Objects.requireNonNull(declaringType, "declaringType");
        Objects.requireNonNull(logicalName, "logicalName");
        Objects.requireNonNull(declarationName, "declarationName");
        Objects.requireNonNull(origin, "origin");
    }
}
