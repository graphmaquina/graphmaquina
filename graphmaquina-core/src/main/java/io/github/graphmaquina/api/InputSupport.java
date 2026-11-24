package io.github.graphmaquina.api;

import java.util.Optional;
import java.util.function.Supplier;
import lombok.NonNull;

/**
 * Cast-and-validate plumbing shared by {@link GuardContext#input(Class)} and
 * {@link TransitionContext#input(Class)}, so the two contexts enforce identical rules for a typed operation
 * input reader. Package-private: neither interface exposes this type.
 */
final class InputSupport {

    private InputSupport() {
    }

    /** Validates {@code type} before evaluating {@code input}, so a bad token fails even with no payload present. */
    static <I> Optional<I> cast(@NonNull Class<I> type, Supplier<Optional<?>> input) {
        if (type.isPrimitive()) {
            throw new IllegalArgumentException("input type must be a reference type; use its wrapper class");
        }
        return input.get().map(type::cast);
    }

    /**
     * Validates {@code type} as {@link #cast} does, then answers the input when it is an instance of {@code type},
     * the same reference, and empty otherwise.
     */
    static <I> Optional<I> castIfInstance(@NonNull Class<I> type, Supplier<Optional<?>> input) {
        if (type.isPrimitive()) {
            throw new IllegalArgumentException("input type must be a reference type; use its wrapper class");
        }
        return input.get().filter(type::isInstance).map(type::cast);
    }
}
