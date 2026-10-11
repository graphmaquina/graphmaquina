package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.FlowContext;
import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.FlowView;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A handle on the flow of a call, handed to an action, checked by its owner before every operation.
 * <ul>
 *   <li>{@code guard} is the check of the context that made the handle: every operation, a read as well as a write,
 *       runs it first, as {@link DefaultPeerView} does, so a handle used off its thread throws that context's
 *       WrongThreadException and one used after the transaction its StaleContextException.</li>
 *   <li>A write is staged at the size the log has when it is made, so {@link UnitOfWork#rollbackTo} drops it with
 *       the step that made it.</li>
 *   <li>A call that logs at TRACE logs each write at the depth of the context that made the handle, the value only
 *       for a visible key and through {@link CallLog#show}.</li>
 *   <li>{@link #readOnly()} is what a guard gets: a view that only reads, and is no {@link FlowContext}, so no cast
 *       reaches a write.</li>
 * </ul>
 * A handle is equal only to itself.
 */
final class FlowHandle implements FlowContext {

    private final CallFlow flow;
    private final UnitOfWork uow;
    private final Runnable guard;
    private final CallLog log;
    private final int depth;

    private FlowHandle(CallFlow flow, UnitOfWork uow, Runnable guard, CallLog log, int depth) {
        this.flow = flow;
        this.uow = uow;
        this.guard = guard;
        this.log = log;
        this.depth = depth;
    }

    /** A writable handle on {@code flow}, checked by {@code guard}, whose writes the log prints at {@code depth}. */
    static FlowHandle of(CallFlow flow, UnitOfWork uow, Runnable guard, CallLog log, int depth) {
        return new FlowHandle(flow, uow, guard, log, depth);
    }

    /** The reads of this handle alone, checked by the same guard. */
    FlowView readOnly() {
        return new ReadOnly(this);
    }

    @Override
    public <V> Optional<V> get(FlowKey<V> key) {
        guard.run();
        Objects.requireNonNull(key, "key");
        return flow.get(key);
    }

    @Override
    public Set<FlowKey<?>> keys() {
        guard.run();
        return flow.keys();
    }

    @Override
    public Optional<Object> entity() {
        guard.run();
        return Optional.of(flow.rootEntity());
    }

    @Override
    public <E> Optional<E> entity(Class<E> type) {
        guard.run();
        Objects.requireNonNull(type, "type");
        if (type.isPrimitive()) {
            throw new IllegalArgumentException("entity type must be a reference type; use its wrapper class");
        }
        return Optional.of(type.cast(flow.rootEntity()));
    }

    @Override
    public <V> void put(FlowKey<V> key, V value) {
        guard.run();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        V checked = key.type().cast(value);
        flow.put(key, checked, uow.savepoint());
        if (log.trace()) {
            log.trace(depth, "flow-put " + key + (key.visible() ? "=" + CallLog.show(checked) : ""));
        }
    }

    @Override
    public void remove(FlowKey<?> key) {
        guard.run();
        Objects.requireNonNull(key, "key");
        flow.remove(key, uow.savepoint());
        if (log.trace()) {
            log.trace(depth, "flow-remove " + key);
        }
    }

    /** The view a guard gets: the reads of a handle, delegated, and nothing else. Equal only to itself. */
    private static final class ReadOnly implements FlowView {

        private final FlowHandle handle;

        private ReadOnly(FlowHandle handle) {
            this.handle = handle;
        }

        @Override
        public <V> Optional<V> get(FlowKey<V> key) {
            return handle.get(key);
        }

        @Override
        public Set<FlowKey<?>> keys() {
            return handle.keys();
        }

        @Override
        public Optional<Object> entity() {
            return handle.entity();
        }

        @Override
        public <E> Optional<E> entity(Class<E> type) {
            return handle.entity(type);
        }
    }
}
