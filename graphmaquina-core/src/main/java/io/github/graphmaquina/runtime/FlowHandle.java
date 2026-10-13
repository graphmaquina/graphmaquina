package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.FlowContext;
import io.github.graphmaquina.api.FlowEntry;
import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.error.StaleContextException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A handle on the flow of a call, handed to an action, checked by its owner before every operation. A post-commit
 * hook gets a {@link Hook} instead, from {@link #hook}.
 * <ul>
 *   <li>{@code unit} is the check of the context that made the handle, which answers the call's unit of work: every
 *       operation, a read as well as a write, runs it first, as {@link DefaultPeerView} does, so a handle used off its
 *       thread throws that context's WrongThreadException and one used after the transaction its
 *       StaleContextException. The handle reaches the unit of work only through it, so once that context is
 *       invalidated it holds nothing of the call but its root entity, its {@link CallLog}, and the entity and the
 *       input of that context's step, which the context keeps.</li>
 *   <li>A handle does not create the call's flow, and neither does a read: before the call's first write,
 *       {@link #get} answers empty and {@link #keys} an empty set, and the entity needs no flow. The first write, a
 *       put or a remove, creates the flow with the root call's entity, unless the call records its trail, whose flow
 *       exists from the start of the call.</li>
 *   <li>{@link #trail} builds the trail of the log so far with its marks, afresh at each read; it is empty when the
 *       call does not record its trail.</li>
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

    private final Supplier<UnitOfWork> unit;
    private final Object rootEntity;
    private final CallLog log;
    private final int depth;

    private FlowHandle(Supplier<UnitOfWork> unit, Object rootEntity, CallLog log, int depth) {
        this.unit = unit;
        this.rootEntity = rootEntity;
        this.log = log;
        this.depth = depth;
    }

    /**
     * A writable handle on the flow of the call whose unit of work {@code unit} answers once it checked the context
     * that made the handle, and whose root entity is {@code rootEntity}, whose writes the log prints at {@code depth}.
     */
    static FlowHandle of(Supplier<UnitOfWork> unit, Object rootEntity, CallLog log, int depth) {
        return new FlowHandle(unit, rootEntity, log, depth);
    }

    /** The reads of this handle alone, checked by the same context. */
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

    /** The value of {@code key} in the call's flow; empty when the call has no flow yet. */
    private static <V> Optional<V> read(UnitOfWork uow, FlowKey<V> key) {
        Optional<CallFlow> flow = uow.flow();
        return flow.isPresent() ? flow.get().get(key) : Optional.empty();
    }

    /** The keys of the call's flow; an empty set when the call has no flow yet. */
    private static Set<FlowKey<?>> keysOf(UnitOfWork uow) {
        Optional<CallFlow> flow = uow.flow();
        return flow.isPresent() ? flow.get().keys() : Set.of();
    }

    @Override
    public <V> Optional<V> get(FlowKey<V> key) {
        UnitOfWork uow = unit.get();
        Objects.requireNonNull(key, "key");
        return read(uow, key);
    }

    @Override
    public Set<FlowKey<?>> keys() {
        return keysOf(unit.get());
    }

    @Override
    public Optional<Object> entity() {
        unit.get();
        return Optional.of(rootEntity);
    }

    @Override
    public <E> Optional<E> entity(Class<E> type) {
        unit.get();
        Objects.requireNonNull(type, "type");
        if (type.isPrimitive()) {
            throw new IllegalArgumentException("entity type must be a reference type; use its wrapper class");
        }
        return Optional.of(type.cast(rootEntity));
    }

    @Override
    public List<FlowEntry> trail() {
        return unit.get().trail();
    }

    @Override
    public <V> void put(FlowKey<V> key, V value) {
        UnitOfWork uow = unit.get();
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
        UnitOfWork uow = unit.get();
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
     *       operation throws StaleContextException with the machine key and the entity of the hop, and the handle
     *       lets go of the call's unit of work, so a CommitContext kept after its hook keeps neither the call nor
     *       its flow reachable: the handle holds nothing of the call but its root entity, its {@link CallLog} and the
     *       {@link EntityRef} of the hop.</li>
     *   <li>It does not create the call's flow, and neither does a read: before the call's first write, a read
     *       answers empty, and the entity needs no flow. Its first write that passes its checks creates the flow, if
     *       no step of the call wrote and the call does not record its trail. A hook that never writes costs this
     *       object alone.</li>
     *   <li>Its trail is the call's whole trail: every hop and start the call committed, the call's log being
     *       final once it committed.</li>
     *   <li>A write applies at once, so the later hooks of the call read it. It keeps the size of the committed log,
     *       and no rollback follows the commit, so nothing undoes it.</li>
     *   <li>A call that logs at TRACE logs each write with the hook's name and without a depth, the value only for a
     *       visible key and through {@link CallLog#show}.</li>
     * </ul>
     * Equal only to itself.
     */
    static final class Hook implements FlowContext {

        /** What {@link #uow} holds once the hook returned or threw: the unit of work of no call, which nothing uses. */
        private static final UnitOfWork RELEASED = new UnitOfWork(0, 0L);

        /** The unit of work of the call while the hook runs; {@link #RELEASED} once the hook returned or threw. */
        private UnitOfWork uow;
        private final Object rootEntity;
        private final CallLog log;
        private final String hook;
        private final EntityRef hop;
        private final Thread owner = Thread.currentThread();

        private Hook(UnitOfWork uow, Object rootEntity, CallLog log, String hook, EntityRef hop) {
            this.uow = uow;
            this.rootEntity = rootEntity;
            this.log = log;
            this.hook = hook;
            this.hop = hop;
        }

        /** Ends the lifetime of this handle, which lets go of the call's unit of work: its hook returned or threw. */
        void invalidate() {
            uow = RELEASED;
        }

        /** Whether the lifetime of this handle ended, so that it no longer holds the call's unit of work. */
        boolean released() {
            return uow == RELEASED;
        }

        /** The call's unit of work, only from the thread running the hook, and only while it runs. */
        private UnitOfWork check() {
            if (released()) {
                throw new StaleContextException(hop.key(), hop.instance());
            }
            if (Thread.currentThread() != owner) {
                throw new WrongThreadException("the flow of the hook " + hook
                        + " can only be used from the thread running it: " + owner.getName());
            }
            return uow;
        }

        @Override
        public <V> Optional<V> get(FlowKey<V> key) {
            UnitOfWork unit = check();
            Objects.requireNonNull(key, "key");
            return read(unit, key);
        }

        @Override
        public Set<FlowKey<?>> keys() {
            return keysOf(check());
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
        public List<FlowEntry> trail() {
            return check().trail();
        }

        @Override
        public <V> void put(FlowKey<V> key, V value) {
            UnitOfWork unit = check();
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            V checked = key.type().cast(value);
            unit.flow(rootEntity).put(key, checked, unit.savepoint());
            if (log.trace()) {
                log.trace("hook-flow-put " + hook + " " + key + (key.visible() ? "=" + CallLog.show(checked) : ""));
            }
        }

        @Override
        public void remove(FlowKey<?> key) {
            UnitOfWork unit = check();
            Objects.requireNonNull(key, "key");
            unit.flow(rootEntity).remove(key, unit.savepoint());
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

        @Override
        public List<FlowEntry> trail() {
            return handle.trail();
        }
    }
}
