package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.TransitionContext;
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
final class DefaultTransitionContext<E, S extends Enum<S>> implements TransitionContext<E, S>, ContextHandle {

    private final Transaction tx;
    private final DefaultStateMachine<E, S> machine;
    @Getter
    private final E entity;
    @Getter
    private final S from;
    @Getter
    private final S to;
    @Getter
    private final int depth;
    private final Object operationInput;
    private final Thread owner = Thread.currentThread();
    private boolean stale;

    /** The value passed to this operation's call, if any; scoped to this invocation only. */
    @Override
    public Optional<?> input() {
        return Optional.ofNullable(operationInput);
    }

    @Override
    public <P, Q extends Enum<Q>> PeerView<P, Q> peer(@NonNull MachineKey<P, Q> key) {
        check();
        return tx.peer(this, this::check, key);
    }

    @Override
    public <P, Q extends Enum<Q>> Outcome<P, Q> cascade(@NonNull MachineKey<P, Q> key, @NonNull P peer, @NonNull Q target) {
        check();
        return tx.cascade(this, key, peer, target);
    }

    @Override
    public <P, Q extends Enum<Q>> List<Outcome<P, Q>> cascadeAll(@NonNull MachineKey<P, Q> key, @NonNull Collection<? extends P> peers,
                                                                 @NonNull Q target, @NonNull CascadePolicy policy) {
        check();
        return tx.cascadeAll(this, key, peers, target, policy);
    }

    @Override
    public <P, Q extends Enum<Q>, M extends Machine<P, Q>> PeerView<P, Q> peer(@NonNull Class<M> peer) {
        return peer(tx.keyFor(peer));
    }

    @Override
    public <P, Q extends Enum<Q>, M extends Machine<P, Q>> Outcome<P, Q> cascade(@NonNull Class<M> peer, @NonNull P entity, @NonNull Q target) {
        return cascade(tx.keyFor(peer), entity, target);
    }

    @Override
    public <P, Q extends Enum<Q>, M extends Machine<P, Q>> List<Outcome<P, Q>> cascadeAll(@NonNull Class<M> peer,
                                                                                         @NonNull Collection<? extends P> peers,
                                                                                         @NonNull Q target, @NonNull CascadePolicy policy) {
        return cascadeAll(tx.keyFor(peer), peers, target, policy);
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

    @Override public MachineKey<?, ?> machineKey() { return machine.key(); }

    @Override public Object entityInstance() { return entity; }

    @Override public Enum<?> fromState() { return from; }

    @Override public Enum<?> toState() { return to; }

    @Override public int contextDepth() { return depth; }

    @Override public void invalidate() { stale = true; }
}
