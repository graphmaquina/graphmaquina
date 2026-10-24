package io.github.graphmaquina.internal;

import io.github.graphmaquina.api.FlowEntry;
import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.FlowView;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;

/**
 * The flow a call hands out once it ended, in its outcome or in its failure: the root call's entity, the values
 * present then and the trail, frozen. It copies the values and the trail it is built from, in their order, and never
 * changes afterwards; the values themselves are the objects the call's steps and hooks put, never copies. It has no
 * owner thread and never goes stale, so any thread can read it for as long as it is kept. {@link #EMPTY}, which
 * {@link FlowView#empty()} returns, has neither an entity, nor a value, nor a trail. Two frozen flows are equal when
 * their entities are equal, by {@link Objects#equals}, their values are, by {@link Map#equals}, whatever their order,
 * and their trails are, by {@link List#equals}, entry by entry; the hash code follows all three. {@link #toString()}
 * names the keys as {@code name:Type} and never prints a value, the entity or the trail. Not part of the API.
 */
public final class FrozenFlow implements FlowView {

    /** The flow without an entity, without values and without a trail: {@link FlowView#empty()}. */
    public static final FrozenFlow EMPTY = new FrozenFlow(Optional.empty(), Map.of());

    private final Optional<Object> entity;
    private final Map<FlowKey<?>, Object> values;
    private final List<FlowEntry> trail;

    /**
     * A frozen copy of {@code values}, in their order, with the root call's {@code entity} and without a trail.
     *
     * @throws NullPointerException if {@code entity} or {@code values} is null, or {@code values} holds a null key or
     *     value
     * @throws ClassCastException if a value is not an instance of its key's type
     */
    public FrozenFlow(Optional<Object> entity, Map<FlowKey<?>, Object> values) {
        this(entity, values, List.of());
    }

    /**
     * A frozen copy of {@code values} and of {@code trail}, each in its order, with the root call's {@code entity}.
     *
     * @throws NullPointerException if {@code entity}, {@code values} or {@code trail} is null, or {@code values} holds
     *     a null key or value, or {@code trail} a null entry
     * @throws ClassCastException if a value is not an instance of its key's type
     */
    public FrozenFlow(@NonNull Optional<Object> entity, @NonNull Map<FlowKey<?>, Object> values,
            @NonNull List<FlowEntry> trail) {
        this.entity = entity;
        Map<FlowKey<?>, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<FlowKey<?>, Object> value : values.entrySet()) {
            FlowKey<?> key = Objects.requireNonNull(value.getKey(), "key");
            copy.put(key, key.type().cast(Objects.requireNonNull(value.getValue(), "value")));
        }
        this.values = Collections.unmodifiableMap(copy);
        for (FlowEntry entry : trail) {
            Objects.requireNonNull(entry, "entry");
        }
        this.trail = List.copyOf(trail);
    }

    @Override
    public <V> Optional<V> get(@NonNull FlowKey<V> key) {
        return Optional.ofNullable(values.get(key)).map(key.type()::cast);
    }

    @Override
    public Set<FlowKey<?>> keys() {
        return values.keySet();
    }

    @Override
    public Optional<Object> entity() {
        return entity;
    }

    @Override
    public <E> Optional<E> entity(@NonNull Class<E> type) {
        if (type.isPrimitive()) {
            throw new IllegalArgumentException("entity type must be a reference type; use its wrapper class");
        }
        return entity.map(type::cast);
    }

    @Override
    public List<FlowEntry> trail() {
        return trail;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FrozenFlow that && entity.equals(that.entity) && values.equals(that.values)
                && trail.equals(that.trail);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * entity.hashCode() + values.hashCode()) + trail.hashCode();
    }

    @Override
    public String toString() {
        return "FrozenFlow[keys=" + values.keySet() + "]";
    }
}
