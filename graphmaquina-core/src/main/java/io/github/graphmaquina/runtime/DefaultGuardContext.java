package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.error.StaleContextException;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Live {@link GuardContext} handed to a guard: entity and edge, read-only, with peers but no cascading. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class DefaultGuardContext<E, S extends Enum<S>, T extends Enum<T>> implements GuardContext<E, S, T>, ContextHandle {

    private final Transaction tx;
    private final DefaultStateMachine<E, S, T> machine;
    private final E entity;
    private final TransitionSpec<E, S, T> spec;
    private final int depth;
    private final Object operationInput;
    private final Thread owner = Thread.currentThread();
    private boolean stale;

    @Override public E entity() { return entity; }

    @Override public S from() { return spec.from(); }

    @Override public T transition() { return spec.transition(); }

    @Override public S to() { return spec.to(); }

    @Override
    public Optional<?> input() {
        return Optional.ofNullable(operationInput);
    }

    @Override
    public <P, Q extends Enum<Q>, U extends Enum<U>> PeerView<P, Q, U> peer(@NonNull MachineKey<P, Q, U> key) {
        check();
        return tx.peer(this, this::check, key);
    }

    @Override
    public <P, Q extends Enum<Q>, U extends Enum<U>, M extends Machine<P, Q, U>> PeerView<P, Q, U> peer(@NonNull Class<M> peer) {
        return peer(tx.keyFor(peer));
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

    @Override public MachineKey<?, ?, ?> machineKey() { return machine.key(); }

    @Override public Object entityInstance() { return entity; }

    @Override public Enum<?> fromState() { return spec.from(); }

    @Override public Enum<?> requestedTransition() { return spec.transition(); }

    @Override public Enum<?> toState() { return spec.to(); }

    @Override public int contextDepth() { return depth; }

    @Override public void invalidate() { stale = true; }
}
