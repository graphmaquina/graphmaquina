package io.github.graphmaquina.api;

import java.util.Objects;

/**
 * The key of a value that a guard keeps for its entity for the rest of the request with {@link GuardContext#memo}, one
 * value for each entity the request decides about. Two keys are the
 * same only when they are the same instance, so a key is declared once, as a constant, and shared by the guards that
 * read its value; two keys with one name are two keys. The name is for messages only.
 *
 * @param <V> the type of the value the key keeps
 */
public final class MemoKey<V> {

    private final String name;

    private MemoKey(String name) {
        this.name = name;
    }

    /**
     * A new key named {@code name}.
     *
     * @throws NullPointerException if {@code name} is null
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public static <V> MemoKey<V> named(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a memo key needs a name that is not blank");
        }
        return new MemoKey<>(name);
    }

    /** The name the key was created with. */
    public String name() {
        return name;
    }

    /** The name of the key. */
    @Override
    public String toString() {
        return name;
    }
}
