package io.github.graphmaquina.codegen.model;

import io.github.graphmaquina.annotation.internal.SymbolKind;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;

/**
 * One generated symbol type: the host and member it names and, for a method family, every original member it
 * could resolve to or, for a property, the declaration the property was read from, with no candidate.
 *
 * <p>{@code kinds} is normalized to declaration order of {@link SymbolKind}, so a descriptor iterates its
 * roles the same way in every run; a hash set of enums does not, because its iteration order is salted per
 * JVM.</p>
 */
public record SymbolDescriptor(@NonNull String qualifiedName, @NonNull String host, @NonNull String member,
        @NonNull Set<SymbolKind> kinds, @NonNull List<MemberDescriptor> candidates,
        @NonNull Optional<PropertyDescriptor> property, @NonNull SourceLocation origin) {

    public SymbolDescriptor {
        EnumSet<SymbolKind> ordered = EnumSet.noneOf(SymbolKind.class);
        ordered.addAll(kinds);
        kinds = Collections.unmodifiableSet(ordered);
        candidates = List.copyOf(candidates);
    }
}
