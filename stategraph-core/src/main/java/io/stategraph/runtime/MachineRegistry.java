package io.stategraph.runtime;

import io.stategraph.api.MachineKey;
import io.stategraph.api.Reaction;
import io.stategraph.api.StateMachine;
import io.stategraph.api.TransitionListener;
import io.stategraph.api.WatchSpec;
import io.stategraph.error.UnknownPeerMachineException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Resolves peers and cascades. Built from builders and passed to itself into every machine.
 * The map is filled once in build() and never touched again.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class MachineRegistry implements AutoCloseable {

    private final Map<MachineKey<?, ?>, DefaultStateMachine<?, ?>> machines = new LinkedHashMap<>();
    private final Map<MachineKey<?, ?>, List<Watcher<?, ?, ?, ?>>> watchers = new LinkedHashMap<>();
    private final int maxCascadeDepth;
    private final int maxWatchChain;
    private final Optional<TransitionListener> listener;

    public static Builder builder() {
        return new Builder();
    }

    static <E, S extends Enum<S>> StateMachine<E, S> standalone(StateMachines.Builder<E, S> builder) {
        MachineRegistry registry = new MachineRegistry(10, 10, Optional.empty());
        DefaultStateMachine<E, S> machine = builder.build(registry);
        registry.machines.put(machine.key(), machine);
        return machine;
    }

    /** Throws if not present: an Optional would end up in an orElse that swallows a wiring error. */
    public <E, S extends Enum<S>> StateMachine<E, S> of(MachineKey<E, S> key) {
        Objects.requireNonNull(key, "key");
        return machineFor(key);
    }

    public Set<MachineKey<?, ?>> keys() {
        return Collections.unmodifiableSet(machines.keySet());
    }

    /**
     * Closes only the default executors of its machines. It blocks until the operations already
     * submitted through {@code async()} terminate.
     */
    @Override
    public void close() {
        for (DefaultStateMachine<?, ?> machine : machines.values()) {
            machine.closeIfOwned();
        }
    }

    int maxCascadeDepth() { return maxCascadeDepth; }

    int maxWatchChain() { return maxWatchChain; }

    Optional<TransitionListener> listener() { return listener; }

    <E, S extends Enum<S>> DefaultStateMachine<E, S> machineFor(MachineKey<E, S> key) {
        DefaultStateMachine<?, ?> machine = anyMachineFor(key);
        @SuppressWarnings("unchecked") // the map is filled in build() with each machine's typed key: the key fixes E and S
        DefaultStateMachine<E, S> typed = (DefaultStateMachine<E, S>) machine;
        return typed;
    }

    DefaultStateMachine<?, ?> anyMachineFor(MachineKey<?, ?> key) {
        DefaultStateMachine<?, ?> machine = machines.get(key);
        if (machine == null) {
            throw new UnknownPeerMachineException(key, machines.keySet());
        }
        return machine;
    }

    void indexWatchers() {
        for (DefaultStateMachine<?, ?> machine : machines.values()) {
            indexWatchersOf(machine);
        }
    }

    private <E, S extends Enum<S>> void indexWatchersOf(DefaultStateMachine<E, S> machine) {
        S[] states = machine.key().states().getEnumConstants();
        for (S state : states) {
            Optional<WatchSpec<E, S, ?, ?>> watch = machine.definition().watch(state);
            if (watch.isEmpty()) {
                continue;
            }
            MachineKey<?, ?> peer = watch.get().peer();
            if (!machines.containsKey(peer)) {
                throw new IllegalStateException("the watch of " + machine.key() + "." + state + " points to " + peer
                        + ", which is not in the registry");
            }
            watchers.computeIfAbsent(peer, (MachineKey<?, ?> key) -> new ArrayList<>()).add(Watcher.of(machine, watch.get()));
        }
    }

    /**
     * After the root commit: for each moved entity, every watcher of its machine resolves the owner;
     * an owner is re-evaluated only once even if several of its peers moved.
     */
    List<Reaction> dispatchWatches(UnitOfWork uow, int chain) {
        Map<EntityRef, Watcher<?, ?, ?, ?>> targets = new LinkedHashMap<>();
        List<Reaction> reactions = new ArrayList<>();
        for (EntityRef moved : uow.movedEntities()) {
            for (Watcher<?, ?, ?, ?> watcher : watchers.getOrDefault(moved.key(), List.of())) {
                try {
                    Optional<EntityRef> owner = watcher.ownerToReevaluate(moved.instance());
                    if (owner.isPresent()) {
                        targets.putIfAbsent(owner.get(), watcher);
                    }
                } catch (RuntimeException e) {
                    reactions.add(new Reaction.Aborted(moved.key(), moved.instance(), e));
                }
            }
        }
        for (Map.Entry<EntityRef, Watcher<?, ?, ?, ?>> entry : targets.entrySet()) {
            reactions.add(entry.getValue().react(entry.getKey().instance(), chain + 1));
        }
        return List.copyOf(reactions);
    }

    /** Single-use and not thread-safe. Accepts builders, not machines. */
    public static final class Builder {

        private final List<StateMachines.Builder<?, ?>> builders = new ArrayList<>();
        private int maxCascadeDepth = 10;
        private int maxWatchChain = 10;
        private Optional<TransitionListener> listener = Optional.empty();
        private boolean used;

        private Builder() { }

        public Builder add(StateMachines.Builder<?, ?> builder) {
            Objects.requireNonNull(builder, "builder");
            for (StateMachines.Builder<?, ?> existing : builders) {
                if (existing.key().equals(builder.key())) {
                    throw new IllegalStateException("the machine " + builder.key() + " is already in the registry");
                }
            }
            builders.add(builder);
            return this;
        }

        public Builder maxCascadeDepth(int depth) {
            if (depth < 1) {
                throw new IllegalArgumentException("maxCascadeDepth must be greater than or equal to 1");
            }
            this.maxCascadeDepth = depth;
            return this;
        }

        public Builder maxWatchChain(int chain) {
            if (chain < 1) {
                throw new IllegalArgumentException("maxWatchChain must be greater than or equal to 1");
            }
            this.maxWatchChain = chain;
            return this;
        }

        /** One listener per registry, notified of every outcome. */
        public Builder listener(TransitionListener listener) {
            Objects.requireNonNull(listener, "listener");
            if (this.listener.isPresent()) {
                throw new IllegalStateException("this MachineRegistry.Builder already has a listener");
            }
            this.listener = Optional.of(listener);
            return this;
        }

        public MachineRegistry build() {
            if (used) {
                throw new IllegalStateException("this MachineRegistry.Builder was already used");
            }
            used = true;
            MachineRegistry registry = new MachineRegistry(maxCascadeDepth, maxWatchChain, listener);
            for (StateMachines.Builder<?, ?> builder : builders) {
                if (!builder.hasIdentity()) {
                    throw new IllegalStateException("the machine " + builder.key() + " must declare identity(...) within a registry");
                }
                DefaultStateMachine<?, ?> machine = builder.build(registry);
                registry.machines.put(machine.key(), machine);
            }
            registry.indexWatchers();
            return registry;
        }
    }
}
