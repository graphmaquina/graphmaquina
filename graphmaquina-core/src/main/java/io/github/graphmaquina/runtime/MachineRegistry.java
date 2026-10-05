package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionListener;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.error.UnknownFacadeException;
import io.github.graphmaquina.error.UnknownPeerMachineException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Resolves peers and cascades. Built from builders and passed to itself into every machine. Its maps are filled
 * once, when it is assembled for {@link Builder#build()} or for a standalone machine, and never touched again.
 *
 * <p>A cascade runs inside the root's transaction and under the root machine's deadline: a cascaded peer's
 * asynchronous waits are bounded by the asyncTimeout of the machine whose call started the root, not by its own.
 * When that root machine declares no asyncTimeout, those waits are unbounded, whatever the peer's own asyncTimeout;
 * only an edge that declares a timeout of its own still bounds its action's wait.
 * Nothing serializes two roots on one entity. The pre-check of phase 2 refuses a state that changed while a root
 * ran, but it catches only the interleavings that leave a different state behind, so concurrent calls on one entity
 * are the application's to serialize. {@link #close()} closes the default executors of the registry's machines. A
 * standalone machine runs in a registry of its own too, but that registry is internal and unreachable, so nothing can
 * close it, and the machine's default executor is never closed.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class MachineRegistry implements AutoCloseable {

    private final Map<MachineKey<?, ?, ?>, DefaultStateMachine<?, ?, ?>> machines = new LinkedHashMap<>();
    private final Map<MachineKey<?, ?, ?>, List<Watcher<?, ?, ?, ?, ?, ?>>> watchers = new LinkedHashMap<>();
    private final Map<Class<?>, MachineKey<?, ?, ?>> facades = new LinkedHashMap<>();
    private final int maxCascadeDepth;
    private final int maxWatchChain;
    private final Optional<TransitionListener> listener;

    public static Builder builder() {
        return new Builder();
    }

    /** A registry of one, assembled as a registry builder would assemble it, with the default limits and no listener. */
    static <E, S extends Enum<S>, T extends Enum<T>> StateMachine<E, S, T> standalone(StateMachines.Builder<E, S, T> builder) {
        return assemble(List.of(builder), 10, 10, Optional.empty()).machineFor(builder.key());
    }

    /**
     * The one way machines join a registry, standalone ones included. Every check runs before the first machine
     * builder is consumed, so a build that fails leaves every builder as it was, ready to be fixed and built again.
     */
    private static MachineRegistry assemble(List<? extends StateMachines.Builder<?, ?, ?>> builders, int maxCascadeDepth,
                                            int maxWatchChain, Optional<TransitionListener> listener) {
        Set<MachineKey<?, ?, ?>> keys = new HashSet<>();
        for (StateMachines.Builder<?, ?, ?> builder : builders) {
            keys.add(builder.key());
        }
        Set<Class<?>> facadeTypes = new HashSet<>();
        for (StateMachines.Builder<?, ?, ?> builder : builders) {
            builder.validate();
            checkWatches(builder.definition(), keys);
            // Defensive: as(...) is typed by (E, S) and add(...) already rejects a duplicate key,
            // so a typed caller cannot reach this collision.
            Optional<? extends Class<?>> facade = builder.facade();
            if (facade.isPresent() && !facadeTypes.add(facade.get())) {
                throw new IllegalStateException("the facade type " + facade.get().getSimpleName() + " is registered twice");
            }
        }
        MachineRegistry registry = new MachineRegistry(maxCascadeDepth, maxWatchChain, listener);
        for (StateMachines.Builder<?, ?, ?> builder : builders) {
            DefaultStateMachine<?, ?, ?> machine = builder.build(registry);
            registry.machines.put(machine.key(), machine);
            Optional<? extends Class<?>> facade = builder.facade();
            if (facade.isPresent()) {
                registry.facades.put(facade.get(), machine.key());
            }
        }
        registry.indexWatchers();
        return registry;
    }

    /** A watch must point to one of the machines being assembled; for a standalone machine, that is itself. */
    private static <E, S extends Enum<S>, T extends Enum<T>> void checkWatches(GraphDefinition<E, S, T> definition,
                                                                               Set<MachineKey<?, ?, ?>> keys) {
        for (S state : definition.key().states().getEnumConstants()) {
            Optional<WatchSpec<E, S, T, ?, ?, ?>> watch = definition.watch(state);
            if (watch.isPresent() && !keys.contains(watch.get().peer())) {
                throw new IllegalStateException("the watch of " + definition.key() + "." + state + " points to "
                        + watch.get().peer() + ", which is not in the registry");
            }
        }
    }

    /** Throws if not present: an Optional would end up in an orElse that swallows a wiring error. */
    public <E, S extends Enum<S>, T extends Enum<T>> StateMachine<E, S, T> of(MachineKey<E, S, T> key) {
        Objects.requireNonNull(key, "key");
        return machineFor(key);
    }

    public Set<MachineKey<?, ?, ?>> keys() {
        return Collections.unmodifiableSet(machines.keySet());
    }

    /**
     * Closes only the default executors of its machines. It blocks until the operations already
     * submitted through {@code async()} terminate.
     */
    @Override
    public void close() {
        for (DefaultStateMachine<?, ?, ?> machine : machines.values()) {
            machine.closeIfOwned();
        }
    }

    int maxCascadeDepth() { return maxCascadeDepth; }

    int maxWatchChain() { return maxWatchChain; }

    Optional<TransitionListener> listener() { return listener; }

    <E, S extends Enum<S>, T extends Enum<T>> DefaultStateMachine<E, S, T> machineFor(MachineKey<E, S, T> key) {
        DefaultStateMachine<?, ?, ?> machine = anyMachineFor(key);
        @SuppressWarnings("unchecked") // the map is filled in assemble() with each machine's typed key: the key fixes E, S and T
        DefaultStateMachine<E, S, T> typed = (DefaultStateMachine<E, S, T>) machine;
        return typed;
    }

    DefaultStateMachine<?, ?, ?> anyMachineFor(MachineKey<?, ?, ?> key) {
        DefaultStateMachine<?, ?, ?> machine = machines.get(key);
        if (machine == null) {
            throw new UnknownPeerMachineException(key, machines.keySet());
        }
        return machine;
    }

    /** Resolves a facade type to its key. The generics of the facade fix P and Q, so the cast is sound. */
    <P, Q extends Enum<Q>, U extends Enum<U>> MachineKey<P, Q, U> keyFor(Class<? extends Machine<P, Q, U>> facade) {
        MachineKey<?, ?, ?> key = facades.get(facade);
        if (key == null) {
            throw new UnknownFacadeException(facade, facades.keySet());
        }
        @SuppressWarnings("unchecked") // put in assemble() from a Builder whose as(...) takes Class<? extends Machine<P, Q, U>>
        MachineKey<P, Q, U> typed = (MachineKey<P, Q, U>) key;
        return typed;
    }

    /** Indexes every watch by the peer it watches. assemble checked beforehand that every peer is in the registry. */
    void indexWatchers() {
        for (DefaultStateMachine<?, ?, ?> machine : machines.values()) {
            indexWatchersOf(machine);
        }
    }

    private <E, S extends Enum<S>, T extends Enum<T>> void indexWatchersOf(DefaultStateMachine<E, S, T> machine) {
        S[] states = machine.key().states().getEnumConstants();
        for (S state : states) {
            Optional<WatchSpec<E, S, T, ?, ?, ?>> watch = machine.definition().watch(state);
            if (watch.isEmpty()) {
                continue;
            }
            MachineKey<?, ?, ?> peer = watch.get().peer();
            watchers.computeIfAbsent(peer, (MachineKey<?, ?, ?> key) -> new ArrayList<>()).add(Watcher.of(machine, watch.get()));
        }
    }

    /**
     * After the root commit: for each moved entity, every watcher of its machine resolves the owner;
     * an owner is re-evaluated only once even if several of its peers moved. A registry that indexed no
     * watcher answers before reading which entities moved, which is what most transactions pay. An owner lookup
     * that throws an Exception is an aborted reaction, a checked one wrapped in UndeclaredThrowableException.
     */
    List<Reaction> dispatchWatches(CommitSummary summary, int chain) {
        if (watchers.isEmpty()) {
            return List.of();
        }
        Map<EntityRef, Watcher<?, ?, ?, ?, ?, ?>> targets = new LinkedHashMap<>();
        List<Reaction> reactions = new ArrayList<>();
        for (EntityRef moved : summary.movedEntities()) {
            for (Watcher<?, ?, ?, ?, ?, ?> watcher : watchers.getOrDefault(moved.key(), List.of())) {
                try {
                    Optional<EntityRef> owner = watcher.ownerToReevaluate(moved.instance());
                    if (owner.isPresent()) {
                        targets.putIfAbsent(owner.get(), watcher);
                    }
                } catch (RuntimeException e) {
                    reactions.add(new Reaction.Aborted(moved.key(), moved.instance(), e));
                } catch (Exception e) {
                    reactions.add(new Reaction.Aborted(moved.key(), moved.instance(), new UndeclaredThrowableException(e)));
                }
            }
        }
        for (Map.Entry<EntityRef, Watcher<?, ?, ?, ?, ?, ?>> entry : targets.entrySet()) {
            reactions.add(entry.getValue().react(entry.getKey().instance(), chain + 1));
        }
        return List.copyOf(reactions);
    }

    /**
     * Single-use and not thread-safe. Accepts builders, not machines. Once build() succeeds, every method throws
     * IllegalStateException instead of accepting configuration nothing would read. A build() that fails consumes
     * neither this builder nor the machine builders it holds: fix what it reports and build again.
     */
    public static final class Builder {

        private final List<StateMachines.Builder<?, ?, ?>> builders = new ArrayList<>();
        private int maxCascadeDepth = 10;
        private int maxWatchChain = 10;
        private Optional<TransitionListener> listener = Optional.empty();
        private boolean used;

        private Builder() { }

        public Builder add(StateMachines.Builder<?, ?, ?> builder) {
            requireUnused();
            Objects.requireNonNull(builder, "builder");
            for (StateMachines.Builder<?, ?, ?> existing : builders) {
                if (existing.key().equals(builder.key())) {
                    throw new IllegalStateException("the machine " + builder.key() + " is already in the registry");
                }
                if (existing.key().entity().equals(builder.key().entity())
                        && existing.key().states().equals(builder.key().states())) {
                    throw new IllegalStateException("the machine " + builder.key() + " writes the same state as "
                            + existing.key() + " with a different transition catalogue: "
                            + existing.key().transitions().getSimpleName() + " and "
                            + builder.key().transitions().getSimpleName());
                }
            }
            builders.add(builder);
            return this;
        }

        public Builder maxCascadeDepth(int depth) {
            requireUnused();
            if (depth < 1) {
                throw new IllegalArgumentException("maxCascadeDepth must be greater than or equal to 1");
            }
            this.maxCascadeDepth = depth;
            return this;
        }

        public Builder maxWatchChain(int chain) {
            requireUnused();
            if (chain < 1) {
                throw new IllegalArgumentException("maxWatchChain must be greater than or equal to 1");
            }
            this.maxWatchChain = chain;
            return this;
        }

        /** One listener per registry, notified of every outcome. */
        public Builder listener(TransitionListener listener) {
            requireUnused();
            Objects.requireNonNull(listener, "listener");
            if (this.listener.isPresent()) {
                throw new IllegalStateException("this MachineRegistry.Builder already has a listener");
            }
            this.listener = Optional.of(listener);
            return this;
        }

        /**
         * Checks every machine builder, its identity, accessors, asyncTimeout, watches and facade, before it consumes
         * any of them; only a build that succeeds marks this builder and its machine builders used.
         */
        public MachineRegistry build() {
            requireUnused();
            for (StateMachines.Builder<?, ?, ?> builder : builders) {
                if (!builder.hasIdentity()) {
                    throw new IllegalStateException("the machine " + builder.key() + " must declare identity(...) within a registry");
                }
            }
            MachineRegistry registry = assemble(builders, maxCascadeDepth, maxWatchChain, listener);
            used = true;
            return registry;
        }

        private void requireUnused() {
            if (used) {
                throw new IllegalStateException("this MachineRegistry.Builder was already used");
            }
        }
    }
}
