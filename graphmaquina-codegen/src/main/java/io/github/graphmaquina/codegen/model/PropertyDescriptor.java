package io.github.graphmaquina.codegen.model;

import lombok.NonNull;

/**
 * The property a {@code @GraphProperty} member exports: its logical name and the marked declaration it was read
 * from, a field or a getter, with the type that declares it. No accessor is resolved here: the annotation processor
 * looks for the readers and writers later.
 */
public record PropertyDescriptor(@NonNull String declaringType, @NonNull String logicalName,
        @NonNull String declarationName, boolean directGetter, @NonNull SourceLocation origin) {

    public PropertyDescriptor {
    }
}
