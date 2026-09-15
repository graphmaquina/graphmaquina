package io.github.graphmaquina.test.internal;

import java.util.Objects;

/** Reference-type token validation shared by recorded input readers and assertions. */
public final class InputTypes {
    private InputTypes() { }

    public static <I> Class<I> referenceType(Class<I> type) {
        Objects.requireNonNull(type, "type");
        if (type.isPrimitive()) {
            throw new IllegalArgumentException("input type must be a reference type; use its wrapper class");
        }
        return type;
    }
}
