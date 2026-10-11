package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.FlowContext;
import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.error.StaleContextException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A handle on the flow of a call, handed to an action, checked by its owner before every operation. A post-commit
 * hook gets a {@link Hook} instead, from {@link #hook}.
 * <ul>
 *   <li>{@code guard} is the check of the context that made the handle: every operation, a read as well as a write,
 *       runs it first, as {@link DefaultPeerView} does, so a handle used off its thread throws that context's
 *       WrongThreadException and one used after the transaction its StaleContextException.</li>
 *   <li>A handle does not create the call's flow, and neither does a read: before the call's first write,
 *       {@link #get} answers empty and {@link #keys} an empty set, and the entity needs no flow. The first write, a
 *       put or a remove, creates the flow with the root call's entity.</li>
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

    private final UnitOfWork uow;
    private final Object rootEntity;
    private final Runnable guard;
    private final CallLog log;
    private final int depth;

    private FlowHandle(UnitOfWork uow, Object rootEntity, Runnable guard, CallLog log, int depth) {
        this.uow = uow;
        this.rootEntity = rootEntity;
        this.guard = guard;
        this.log = log;
        this.depth = depth;
    }

    /**
     * A writable handle on the flow of the call whose unit of work is {@code uow} and whose root entity is
     * {@code rootEntity}, checked by {@code guard}, whose writes the log prints at {@code depth}.
     */
    static FlowHandle of(UnitOfWork uow, Object rootEntity, Runnable guard, CallLog log, int depth) {
        return new FlowHandle(uow, rootEntity, guard, log, depth);
    }

    /** The reads of this handle alone, checked by the same guard. */
    FlowView readOnly() {
        return new ReadOnly(this);
    }

    /**
     * A handle for the hook named {@code hook}, which runs for the committed hop {@code hop}, on the flow of the call
     * whose unit of work is {@code uow} and whose root entity is {@code rootEntity}; owned by the current thread.
     */
    static Hook hook(UnitOfWork uow, Object rootEntity, CallLog log, String hook, EntityRef hop) {
        return new Hook(uow, rootEntity, log, hook, hop);
    }

    /** The value of {@code key} in the call's flow; empty when no write created the flow. */
    private static <V> Optional<V> read(UnitOfWork uow, FlowKey<V> key) {
        Optional<CallFlow> flow = uow.flow();
        return flow.isPresent() ? flow.get().get(key) : Optional.empty();
    }

    /** The keys of the call's flow; an empty set when no write created the flow. */
    private static Set<FlowKey<?>> keysOf(UnitOfWork uow) {
        Optional<CallFlow> flow = uow.flow();
        return flow.isPresent() ? flow.get().keys() : Set.of();
    }

    @Override
    public <V> Optional<V> get(FlowKey<V> key) {
        guard.run();
        Objects.requireNonNull(key, "key");
        return read(uow, key);
    }

    @Override
    public Set<FlowKey<?>> keys() {
        guard.run();
        return keysOf(uow);
    }

    @Override
    public Optional<Object> entity() {
        guard.run();
        return Optional.of(rootEntity);
    }

    @Override
    public <E> Optional<E> entity(Class<E> type) {
        guard.run();
        Objects.requireNonNull(type, "type");
        if (type.isPrimitive()) {
            throw new IllegalArgumentException("entity type must be a reference type; use its wrapper class");
        }
        return Optional.of(type.cast(rootEntity));
    }

    @Override
    public <V> void put(FlowKey<V> key, V value) {
        guard.run();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        V checked = key.type().cast(value);
        uow.flow(rootEntity).put(key, checked, uow.savepoint());
        if (log.trace()) {
            log.trace(depth, "flow-put " + key + (key.visible() ? "=" + CallLog.show(checked) : ""));
        }
    }

    @Override
    public void remove(FlowKey<?> key) {
        guard.run();
        Objects.requireNonNull(key, "key");
        uow.flow(rootEntity).remove(key, uow.savepoint());
        if (log.trace()) {
            log.trace(depth, "flow-remove " + key);
        }
    }

    /**
     * The handle a post-commit hook gets, through its {@code CommitContext}. It runs after the commit, so it has no
     * context to check with:
     * <ul>
     *   <li>Every operation checks the handle's own lifetime, then its owner thread, the thread that made it, which
     *       runs the hook. {@link #invalidate()} ends the lifetime once the hook returned or threw: from then on every
     *       operation throws StaleContextException with the machine key and the entity of the hop.</li>
     *   <li>It does not create the call's flow, and neither does a read: before the call's first write, a read
     *       answers empty, and the entity needs no flow. Its first write that passes its checks creates the flow, if
     *       no step of the call wrote. A hook that never writes costs this object alone.</li>
     *   <li>A write applies at once, so the later hooks of the call read it. It keeps the size of the committed log,
     *       and no rollback follows the commit, so nothing undoes it.</li>
     *   <li>A call that logs at TRACE logs each write with the hook's name and without a depth, the value only for a
     *       visible key and through {@link CallLog#show}.</li>
     * </ul>
     * Equal only to itself.
     */
    static final class Hook implements FlowContext {

        private final UnitOfWork uow;
        private final Object rootEntity;
        private final CallLog log;
        private final String hook;
        private final EntityRef hop;
        private final Thread owner = Thread.currentThread();
        private boolean stale;

        private Hook(UnitOfWork uow, Object rootEntity, CallLog log, String hook, EntityRef hop) {
            this.uow = uow;
            this.rootEntity = rootEntity;
            this.log = log;
            this.hook = hook;
            this.hop = hop;
        }

        /** Ends the lifetime of this handle: its hook returned or threw. */
        void invalidate() {
            stale = true;
        }

        /** Only from the thread running the hook, and only while it runs. */
        private void check() {
            if (stale) {
                throw new StaleContextException(hop.key(), hop.instance());
            }
            if (Thread.currentThread() != owner) {
                throw new WrongThreadException("the flow of the hook " + hook
                        + " can only be used from the thread running it: " + owner.getName());
            }
        }

        @Override
        public <V> Optional<V> get(FlowKey<V> key) {
            check();
            Objects.requireNonNull(key, "key");
            return read(uow, key);
        }

        @Override
        public Set<FlowKey<?>> keys() {
            check();
            return keysOf(uow);
        }

        @Override
        public Optional<Object> entity() {
            check();
            return Optional.of(rootEntity);
        }

        @Override
        public <E> Optional<E> entity(Class<E> type) {
            check();
            Objects.requireNonNull(type, "type");
            if (type.isPrimitive()) {
                throw new IllegalArgumentException("entity type must be a reference type; use its wrapper class");
            }
            return Optional.of(type.cast(rootEntity));
        }

        @Override
        public <V> void put(FlowKey<V> key, V value) {
            check();
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            V checked = key.type().cast(value);
            uow.flow(rootEntity).put(key, checked, uow.savepoint());
            if (log.trace()) {
                log.trace("hook-flow-put " + hook + " " + key + (key.visible() ? "=" + CallLog.show(checked) : ""));
            }
        }

        @Override
        public void remove(FlowKey<?> key) {
            check();
            Objects.requireNonNull(key, "key");
            uow.flow(rootEntity).remove(key, uow.savepoint());
            if (log.trace()) {
                log.trace("hook-flow-remove " + hook + " " + key);
            }
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
