package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.error.StaleContextException;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Live {@link GuardContext} handed to a guard: entity and edge, read-only, with peers and a read-only view of the
 * call's flow, but no cascading.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class DefaultGuardContext<E, S extends Enum<S>, T extends Enum<T>>
        implements GuardContext<E, S, T>, ContextHandle {

    /**
     * The transaction of the call while this context is live; once it is invalidated, its machine's released one,
     * which keeps nothing of the call reachable. Not final, so {@code @NonNull} puts it in the constructor.
     */
    @NonNull
    private Transaction tx;
    private final DefaultStateMachine<E, S, T> machine;
    @Getter
    private final E entity;
    private final TransitionSpec<E, S, T> spec;
    private final int depth;
    private final Optional<Object> operationInput;
    private final Thread owner = Thread.currentThread();
    private boolean stale;

    @Override public S from() { return spec.from(); }

    @Override public T transition() { return spec.transition(); }

    @Override public S to() { return spec.to(); }

    @Override
    public Optional<?> input() {
        return operationInput;
    }

    @Override
    public <P, Q extends Enum<Q>, U extends Enum<U>> PeerView<P, Q, U> peer(@NonNull MachineKey<P, Q, U> key) {
        check();
        return Cascades.peer(tx, this, this::checkedUnit, key);
    }

    /**
     * Checks this context and then the poison before it resolves the facade; an unknown facade in a read never
     * poisons.
     */
    @Override
    public <P, Q extends Enum<Q>, U extends Enum<U>, M extends Machine<P, Q, U>> PeerView<P, Q, U> peer(
            @NonNull Class<M> peer) {
        check();
        return Cascades.peer(tx, this, this::checkedUnit, Cascades.peerKeyFor(tx, this, peer));
    }

    /**
     * Checks this context, then hands out a read-only view of the call's flow that runs the same check first. A guard
     * never creates the flow: it cannot write.
     */
    @Override
    public FlowView flow() {
        check();
        return tx.flow(this::checkedUnit, depth).readOnly();
    }

    /** Only from the thread running test(), and only while the transaction is alive. */
    void check() {
        if (stale) {
            throw new StaleContextException(machine.key(), entity);
        }
        if (Thread.currentThread() != owner) {
            throw new WrongThreadException("the context of " + machine.key()
                    + " can only be used from the thread running test(): " + owner.getName());
        }
    }

    /** The call's unit of work, once this context is checked: what its peer views and its flow read and write. */
    UnitOfWork checkedUnit() {
        check();
        return tx.uow();
    }

    @Override public MachineKey<?, ?, ?> machineKey() { return machine.key(); }

    @Override public Object entityInstance() { return entity; }

    @Override public Enum<?> fromState() { return spec.from(); }

    @Override public Enum<?> requestedTransition() { return spec.transition(); }

    @Override public Enum<?> toState() { return spec.to(); }

    @Override public int contextDepth() { return depth; }

    /** Ends the lifetime of this context, which lets go of the call's transaction and so of its unit of work. */
    @Override
    public void invalidate() {
        stale = true;
        tx = machine.releasedTransaction();
    }
}
