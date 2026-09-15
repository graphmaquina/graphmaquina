package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.error.StaleContextException;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Live {@link GuardContext} handed to a guard: entity and edge, read-only, with peers but no cascading. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class DefaultGuardContext<E, S extends Enum<S>> implements GuardContext<E, S>, ContextHandle {

    private final Transaction tx;
    private final DefaultStateMachine<E, S> machine;
    @Getter
    private final E entity;
    @Getter
    private final S from;
    @Getter
    private final S to;
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
    public <P, Q extends Enum<Q>, M extends Machine<P, Q>> PeerView<P, Q> peer(@NonNull Class<M> peer) {
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

    @Override public MachineKey<?, ?> machineKey() { return machine.key(); }

    @Override public Object entityInstance() { return entity; }

    @Override public Enum<?> fromState() { return from; }

    @Override public Enum<?> toState() { return to; }

    @Override public int contextDepth() { return depth; }

    @Override public void invalidate() { stale = true; }
}
