package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.error.StaleContextException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Live {@link TransitionContext} handed to an action: entity, edge, depth, peers and cascading. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class DefaultTransitionContext<E, S extends Enum<S>, T extends Enum<T>>
        implements TransitionContext<E, S, T>, ContextHandle {

    private final Transaction tx;
    private final DefaultStateMachine<E, S, T> machine;
    private final E entity;
    private final TransitionSpec<E, S, T> spec;
    @Getter
    private final int depth;
    private final Optional<Object> operationInput;
    private final Thread owner = Thread.currentThread();
    private boolean stale;

    @Override public E entity() { return entity; }

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
        return tx.peer(this, this::check, key);
    }

    @Override
    public <P, Q extends Enum<Q>, U extends Enum<U>> Outcome<P, Q, U> cascade(@NonNull MachineKey<P, Q, U> key,
                                                                              @NonNull P peer, @NonNull U transition) {
        check();
        return tx.cascade(this, key, peer, transition);
    }

    @Override
    public <P, Q extends Enum<Q>, U extends Enum<U>> List<Outcome<P, Q, U>> cascadeAll(
            @NonNull MachineKey<P, Q, U> key, @NonNull Collection<? extends P> peers, @NonNull U transition,
            @NonNull CascadePolicy policy) {
        check();
        return tx.cascadeAll(this, key, peers, transition, policy);
    }

    /**
     * Checks this context and then the poison before it resolves the facade, as the cascades by facade do. A read
     * stages nothing: an unknown facade throws here without poisoning the transaction.
     */
    @Override
    public <P, Q extends Enum<Q>, U extends Enum<U>, M extends Machine<P, Q, U>> PeerView<P, Q, U> peer(@NonNull Class<M> peer) {
        check();
        return tx.peer(this, this::check, tx.peerKeyFor(this, peer));
    }

    /** An unknown facade poisons the transaction, as an unknown key does once the cascade looks its machine up. */
    @Override
    public <P, Q extends Enum<Q>, U extends Enum<U>, M extends Machine<P, Q, U>> Outcome<P, Q, U> cascade(
            @NonNull Class<M> peer, @NonNull P entity, @NonNull U transition) {
        check();
        return tx.cascade(this, tx.cascadeKeyFor(this, peer), entity, transition);
    }

    /**
     * An unknown facade poisons the transaction, as an unknown key does: either is refused whatever valid elements
     * the collection holds, and an empty collection too. A null element is refused first, without poisoning, as it
     * is for a key.
     */
    @Override
    public <P, Q extends Enum<Q>, U extends Enum<U>, M extends Machine<P, Q, U>> List<Outcome<P, Q, U>> cascadeAll(
            @NonNull Class<M> peer, @NonNull Collection<? extends P> peers, @NonNull U transition,
            @NonNull CascadePolicy policy) {
        check();
        return tx.cascadeAll(this, peer, peers, transition, policy);
    }

    /** Only from the thread running process(), and only while the transaction is alive. */
    void check() {
        if (stale) {
            throw new StaleContextException(machine.key(), entity);
        }
        if (Thread.currentThread() != owner) {
            throw new WrongThreadException("the context of " + machine.key()
                    + " can only be used from the thread running process(): " + owner.getName());
        }
    }

    @Override public MachineKey<?, ?, ?> machineKey() { return machine.key(); }

    @Override public Object entityInstance() { return entity; }

    @Override public Enum<?> fromState() { return spec.from(); }

    @Override public Enum<?> requestedTransition() { return spec.transition(); }

    @Override public Enum<?> toState() { return spec.to(); }

    @Override public int contextDepth() { return depth; }

    @Override public void invalidate() { stale = true; }
}
