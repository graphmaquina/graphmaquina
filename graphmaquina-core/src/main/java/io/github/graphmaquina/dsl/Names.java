package io.github.graphmaquina.dsl;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import java.util.Optional;

/** Resolves the name used in diagnostics and rendering for an action or guard instance. */
final class Names {

    private Names() { }

    /** Simple class name, except for lambdas, method references and anonymous classes. */
    static Optional<String> defaultName(Object instance) {
        Class<?> type = instance.getClass();
        if (type.isSynthetic() || type.isHidden() || type.isAnonymousClass() || type.getSimpleName().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(type.getSimpleName());
    }

    /** Resolves the name, or records SG106/SG107 and returns empty. */
    static Optional<String> resolve(Optional<String> explicit, Object instance, String what, String where,
                                    int fromOrdinal, int toOrdinal, Problems problems) {
        if (explicit.isPresent()) {
            if (explicit.get().isBlank()) {
                problems.add(DiagnosticCode.SG107, fromOrdinal, toOrdinal, what + " of " + where + " has a blank name");
                return Optional.empty();
            }
            return explicit;
        }
        Optional<String> derived = defaultName(instance);
        if (derived.isEmpty()) {
            problems.add(DiagnosticCode.SG106, fromOrdinal, toOrdinal,
                    what + " of " + where + " is a lambda or anonymous class and has no name: use the named overload");
        }
        return derived;
    }
}
