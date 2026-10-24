package io.github.graphmaquina.codegen.model;

import io.github.graphmaquina.annotation.internal.SymbolKind;
import java.util.List;
import lombok.NonNull;

/** One candidate original member (method or field) a generated symbol may resolve to. */
public record MemberDescriptor(@NonNull String declaringType, @NonNull String name,
        @NonNull List<String> parameterTypes, @NonNull String returnType, @NonNull SymbolKind role,
        boolean staticMember, @NonNull SourceLocation origin) {

    public MemberDescriptor {
        parameterTypes = List.copyOf(parameterTypes);
    }
}
