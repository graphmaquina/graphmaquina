package io.github.graphmaquina.api;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A typed key of the flow of a call: a name and the class of the values it holds. Two keys are equal when their names
 * are equal and their types are the same class; whether a key is visible is not part of that equality, so
 * {@code of("batch", String.class)} and {@code visible("batch", String.class)} read and write the same value. The key
 * passed to {@link FlowContext#put} decides how the runtime's TRACE line of that put prints it: {@code name:Type} for
 * a key made with {@link #of}, and {@code name:Type=value} for one made with {@link #visible}, the value printed by
 * its {@code toString()}. The line of a {@link FlowContext#remove} prints {@code name:Type} alone, whatever the key.
 * Keys are immutable and can be shared, typically as constants.
 *
 * @param <V> the type of the values the key holds
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class FlowKey<V> {

    private final String name;
    private final Class<V> type;

    /** Whether the runtime log prints the value of a put made with this key. */
    private final boolean visible;

    /**
     * A key whose writes the runtime log prints as {@code name:Type}, without the value.
     *
     * @throws NullPointerException if {@code name} or {@code type} is null
     * @throws IllegalArgumentException if {@code name} is blank, or {@code type} is a primitive class token; use its
     *     wrapper class instead
     */
    public static <V> FlowKey<V> of(String name, Class<V> type) {
        return create(name, type, false);
    }

    /**
     * A key whose puts the runtime log prints as {@code name:Type=value}, with the value's {@code toString()}; a
     * remove prints {@code name:Type} alone. Use it only for values you accept to see in your logs.
     *
     * @throws NullPointerException if {@code name} or {@code type} is null
     * @throws IllegalArgumentException if {@code name} is blank, or {@code type} is a primitive class token; use its
     *     wrapper class instead
     */
    public static <V> FlowKey<V> visible(String name, Class<V> type) {
        return create(name, type, true);
    }

    private static <V> FlowKey<V> create(@NonNull String name, @NonNull Class<V> type, boolean visible) {
        if (name.isBlank()) {
            throw new IllegalArgumentException("flow key name must not be blank");
        }
        if (type.isPrimitive()) {
            throw new IllegalArgumentException("flow key type must be a reference type; use its wrapper class");
        }
        return new FlowKey<>(name, type, visible);
    }

    /** Equal to another key with an equal name and the same type, whatever the visibility of either. */
    @Override
    public boolean equals(Object other) {
        return other instanceof FlowKey<?> that && name.equals(that.name) && type == that.type;
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + type.hashCode();
    }

    /** {@code name:SimpleType}, the simple name of the key's type. */
    @Override
    public String toString() {
        return name + ":" + type.getSimpleName();
    }
}
