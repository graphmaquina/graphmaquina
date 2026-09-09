package io.github.graphmaquina.codegen.model;

import io.github.graphmaquina.annotation.internal.SymbolKind;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One generated symbol type: the host and member it names, every original member it could resolve to and,
 * when the member is a property, the resolved accessor.
 *
 * <p>{@code kinds} is normalized to declaration order of {@link SymbolKind}, so a descriptor iterates its
 * roles the same way in every run; a hash set of enums does not, because its iteration order is salted per
 * JVM.</p>
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
        EnumSet<SymbolKind> ordered = EnumSet.noneOf(SymbolKind.class);
        ordered.addAll(kinds);
        kinds = Collections.unmodifiableSet(ordered);
        candidates = List.copyOf(candidates);
    }
}
