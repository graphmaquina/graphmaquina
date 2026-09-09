package io.github.graphmaquina.codegen.model;

import io.github.graphmaquina.annotation.internal.SymbolKind;
import java.util.List;
import java.util.Objects;

/** One candidate original member (method or field) a generated symbol may resolve to. */
public record MemberDescriptor(String declaringType, String name,
        List<String> parameterTypes, String returnType, SymbolKind role,
        boolean staticMember, SourceLocation origin) {

    public MemberDescriptor {
        Objects.requireNonNull(declaringType, "declaringType");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(parameterTypes, "parameterTypes");
        Objects.requireNonNull(returnType, "returnType");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(origin, "origin");
        parameterTypes = List.copyOf(parameterTypes);
    }
}
