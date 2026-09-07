package io.stategraph.runtime;

import io.stategraph.api.GuardContext;
import io.stategraph.api.MachineKey;
import io.stategraph.api.PeerView;
import io.stategraph.error.StaleContextException;
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
    private final Thread owner = Thread.currentThread();
    private boolean stale;

    @Override
    public <P, Q extends Enum<Q>> PeerView<P, Q> peer(@NonNull MachineKey<P, Q> key) {
        if (stale) {
            throw new StaleContextException(machine.key(), entity);
        }
        if (Thread.currentThread() != owner) {
            throw new WrongThreadException("the context of " + machine.key()
                    + " can only be used from the thread running test(): " + owner.getName());
        }
        return tx.peer(this, key);
    }

    @Override public MachineKey<?, ?> machineKey() { return machine.key(); }

    @Override public Object entityInstance() { return entity; }

    @Override public Enum<?> fromState() { return from; }

    @Override public Enum<?> toState() { return to; }

    @Override public int contextDepth() { return depth; }

    @Override public void invalidate() { stale = true; }
}
