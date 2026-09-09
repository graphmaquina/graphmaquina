package io.stategraph.codegen.model;

import io.stategraph.annotation.internal.SymbolKind;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One generated symbol type: the host and member it names, every original member it could resolve to and,
 * when the member is a property, the resolved accessor.
 */
public record SymbolDescriptor(String qualifiedName, String host, String member,
        Set<SymbolKind> kinds, List<MemberDescriptor> candidates,
        Optional<PropertyDescriptor> property, SourceLocation origin) {

    public SymbolDescriptor {
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(member, "member");
        Objects.requireNonNull(kinds, "kinds");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(origin, "origin");
        kinds = Set.copyOf(kinds);
        candidates = List.copyOf(candidates);
    }
}
