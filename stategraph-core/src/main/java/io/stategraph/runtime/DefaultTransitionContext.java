package io.stategraph.runtime;

import io.stategraph.api.CascadePolicy;
import io.stategraph.api.MachineKey;
import io.stategraph.api.Outcome;
import io.stategraph.api.PeerView;
import io.stategraph.api.TransitionContext;
import io.stategraph.error.StaleContextException;
import java.util.Collection;
import java.util.List;
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
    private final Thread owner = Thread.currentThread();
    private boolean stale;

    @Override
    public <P, Q extends Enum<Q>> PeerView<P, Q> peer(@NonNull MachineKey<P, Q> key) {
        check();
        return tx.peer(this, key);
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

    /** Only from the thread running process(), and only while the transaction is alive. */
    private void check() {
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
