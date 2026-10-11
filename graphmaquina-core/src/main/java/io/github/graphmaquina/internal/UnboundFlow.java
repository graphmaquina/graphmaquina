package io.github.graphmaquina.internal;

import io.github.graphmaquina.api.FlowContext;
import io.github.graphmaquina.api.FlowKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A flow that belongs to no call: the one the five-argument constructor of
 * {@link io.github.graphmaquina.api.CommitContext} gives, so that a hook can be called by hand, in a unit test, and
 * what it wrote read back. It starts empty and has no entity: {@link #entity()} and {@link #entity(Class)} are empty.
 * It has no owner thread, never goes stale and is not synchronized. It refuses what the flow of a call refuses, and
 * {@link #keys()} keeps the same order. Two unbound flows are equal when they hold equal values under equal keys,
 * whatever their order, so contexts built by hand compare by their components; like a collection's, its hash code
 * follows its values. Not part of the API.
 */
public final class UnboundFlow implements FlowContext {

    private final Map<FlowKey<?>, Object> values = new LinkedHashMap<>();

    /** An empty flow, without an entity. */
    public UnboundFlow() { }

    @Override
    public <V> Optional<V> get(FlowKey<V> key) {
        Objects.requireNonNull(key, "key");
        return values.containsKey(key) ? Optional.of(key.type().cast(values.get(key))) : Optional.empty();
    }

    @Override
    public Set<FlowKey<?>> keys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values.keySet()));
    }

    @Override
    public Optional<Object> entity() {
        return Optional.empty();
    }

    @Override
    public <E> Optional<E> entity(Class<E> type) {
        Objects.requireNonNull(type, "type");
        if (type.isPrimitive()) {
            throw new IllegalArgumentException("entity type must be a reference type; use its wrapper class");
        }
        return Optional.empty();
    }

    /** Removes the key first, so a key put again moves to the end and the key that put the value is the one kept. */
    @Override
    public <V> void put(FlowKey<V> key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        V checked = key.type().cast(value);
        values.remove(key);
        values.put(key, checked);
    }

    @Override
    public void remove(FlowKey<?> key) {
        Objects.requireNonNull(key, "key");
        values.remove(key);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof UnboundFlow that && values.equals(that.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }
}
