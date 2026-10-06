package io.github.graphmaquina.processor.gen;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * What one generated companion writes at the head of its type references.
 *
 * <ul>
 *   <li>{@code roots} maps the first segment of every qualified name the companion writes that starts with a
 *       package, by {@link Names} or by {@link TypeSource}, to the first such name;</li>
 *   <li>{@code typeRoots} does the same for the qualified names it writes of the types of the unnamed package,
 *       which start with the top-level type they are or are nested in and are that type's simple name for a
 *       top-level one;</li>
 *   <li>{@code simpleNames} maps every simple name {@link Names} writes for a type, as the name that resolves to
 *       it, to the type's qualified name;</li>
 *   <li>{@code expressions} maps each of those identifiers that the companion also writes at the head of a name an
 *       expression reads, the qualifier of a member it accesses or a constant an annotation value names, to the
 *       qualified name of the first type it so writes.</li>
 * </ul>
 *
 * <p>The processor checks them against the scope the companion is compiled in, which can hold types and fields
 * that no spelling of a name gets past: a type takes an identifier wherever it heads a name, a field only where
 * an expression reads that name.</p>
 */
public record Written(Map<String, String> roots, Map<String, String> typeRoots, Map<String, String> simpleNames,
                      Map<String, String> expressions) {

    public Written {
        roots = Collections.unmodifiableMap(new LinkedHashMap<>(roots));
        typeRoots = Collections.unmodifiableMap(new LinkedHashMap<>(typeRoots));
        simpleNames = Collections.unmodifiableMap(new LinkedHashMap<>(simpleNames));
        expressions = Collections.unmodifiableMap(new LinkedHashMap<>(expressions));
    }

    /** Everything this holds and everything {@code other} holds; where both hold a name, this keeps its own entry. */
    Written and(Written other) {
        return new Written(merged(roots, other.roots()), merged(typeRoots, other.typeRoots()),
                merged(simpleNames, other.simpleNames()), merged(expressions, other.expressions()));
    }

    /** Every identifier this holds: the first segments of both kinds of qualified names, and the simple names. */
    Set<String> identifiers() {
        Set<String> identifiers = new LinkedHashSet<>(roots.keySet());
        identifiers.addAll(typeRoots.keySet());
        identifiers.addAll(simpleNames.keySet());
        return identifiers;
    }

    private static Map<String, String> merged(Map<String, String> first, Map<String, String> second) {
        Map<String, String> merged = new LinkedHashMap<>(first);
        second.forEach(merged::putIfAbsent);
        return merged;
    }
}
