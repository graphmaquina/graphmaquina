package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.CallOrigin;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CallRequest;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.IgnoreReason;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.OutcomeStatus;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.SubmachineSpec;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.UnknownFacadeException;
import io.github.graphmaquina.error.UnknownPeerMachineException;
import io.github.graphmaquina.internal.CallFailureFlows;
import io.github.graphmaquina.internal.FrozenFlow;
import io.github.graphmaquina.internal.Interrupts;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Resolves peers and cascades, and hands each machine the sub-machine bindings in which it is the child. Built from
 * builders and passed to itself into every machine. Its maps are filled once, when it is assembled for
 * {@link Builder#build()} or for a standalone machine, and never touched again. Assembling refuses a binding whose
 * child machine is not in the registry, or was built from another {@code GraphDefinition} instance than the one the
 * binding holds, and a machine that declares no asyncTimeout although an edge that its calls can run implicitly is
 * asynchronous, as below.
 *
 * <p>A cascade runs inside the root's transaction and under the root machine's deadline: a cascaded peer's
 * asynchronous waits are bounded by the asyncTimeout of the machine whose call started the root, not by its own.
 * When that root machine declares no asyncTimeout, those waits are unbounded, whatever the peer's own asyncTimeout;
 * only an edge that declares a timeout of its own still bounds its action's wait. The exit of a sub-machine runs under
 * that deadline too, never under its parent's asyncTimeout, and so does every other edge the engine runs implicitly.
 * A machine must therefore declare asyncTimeout when its own graph has an asynchronous edge, or when an edge that the
 * engine can run implicitly from one of its calls is asynchronous: its action or one of its guards is. The implicit
 * edges of a call start with the exit edges of every binding in which the machine is the child, and the exits
 * selected by terminal initial descendants of bindings that its own edges enter. Then, for each implicit edge:
 * <ul>
 *   <li>its failure routes, its onFailure edge and the edge of each recovery rule, from the same origin;</li>
 *   <li>when it leaves its machine in a terminal state and that machine is the child of a binding, the exit edge that
 *       the binding maps that state to, up the chain of parents;</li>
 *   <li>when it leaves its machine in a state that holds a sub-machine in the machine's own graph, every exit edge of
 *       that binding, since the state the child stands in after the entering action is not known before the call,
 *       and the exits selected by its terminal initial descendants.</li>
 * </ul>
 * A cascade that an action requests is explicit, so neither it nor what its steps run asks anything of the kind.
 * The root machine decides the trail of its call the same way: the hops and starts of every peer and every exit of
 * the call are in the trail when the root machine was built with {@code recordTrail}, whatever the peers' builders
 * declare, and in none otherwise.
 * Nothing serializes two roots on one entity. The pre-check of phase 2 refuses a state that changed while a root
 * ran, but it catches only the interleavings that leave a different state behind, so concurrent calls on one entity
 * are the application's to serialize. Its observers see every call of its machines, as
 * {@link io.github.graphmaquina.api.MachineObserver} describes; a standalone machine's registry has none.
 * {@link #close()} closes the default executors of the registry's machines. A standalone machine runs in a registry
 * of its own too, but that registry is internal and unreachable, so nothing can close it, and the machine's default
 * executor is never closed.
 *
 * <p>A cascade, the exit of a sub-machine and a watch reaction each run one level deeper on the stack of the calling
 * thread, so that stack bounds maxCascadeDepth and maxWatchChain too. A call that outgrows it meets a
 * StackOverflowError, which reaches the actions on its way up, as {@link io.github.graphmaquina.api.TransitionContext}
 * says, and otherwise ends the call; in a watch chain, the root and the reactions before the one that overflowed have
 * committed already. Neither the registry nor the thread is left unusable by it: later calls run as before.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class MachineRegistry implements AutoCloseable {

    /**
     * How many machines {@link #anyMachineFor} scans for the very key instance asked for before it hashes the key: a
     * registry that holds more hashes it at once. A call asks with the machines' own key instances for its root, the
     * entities it logged and its sub-machine bindings, and a few comparisons cost less than one hash; another instance
     * of a registered key, which an application may pass, and a key of no machine resolve through the map.
     */
    private static final int REFERENCE_SCAN_LIMIT = 8;

    private final Map<MachineKey<?, ?, ?>, DefaultStateMachine<?, ?, ?>> machines = new LinkedHashMap<>();
    /** The machines in the order they joined, for the scan of {@link #REFERENCE_SCAN_LIMIT}. */
    private final List<DefaultStateMachine<?, ?, ?>> assembled = new ArrayList<>();
    private final Map<MachineKey<?, ?, ?>, List<Watcher<?, ?, ?, ?, ?, ?>>> watchers = new LinkedHashMap<>();
    private final Map<Class<?>, MachineKey<?, ?, ?>> facades = new LinkedHashMap<>();
    @Getter(AccessLevel.PACKAGE)
    private final int maxCascadeDepth;
    @Getter(AccessLevel.PACKAGE)
    private final int maxWatchChain;
    @Getter(AccessLevel.PACKAGE)
    private final Observers observers;

    public static Builder builder() {
        return new Builder();
    }

    /** A registry of one, assembled as a registry builder would, with the default limits and no observer. */
    static <E, S extends Enum<S>, T extends Enum<T>> StateMachine<E, S, T> standalone(
            StateMachines.Builder<E, S, T> builder) {
        return assemble(List.of(builder), 10, 10, Observers.NONE).machineFor(builder.key());
    }

    /**
     * The one way machines join a registry, standalone ones included. Every check runs before the first machine
     * builder is consumed, so a build that fails leaves every builder as it was, ready to be fixed and built again.
     * Each machine receives the bindings in which it is the child, in the order of the builders and then of the
     * parent's states.
     */
    private static MachineRegistry assemble(List<? extends StateMachines.Builder<?, ?, ?>> builders,
                                            int maxCascadeDepth, int maxWatchChain, Observers observers) {
        Map<MachineKey<?, ?, ?>, StateMachines.Builder<?, ?, ?>> byKey = new HashMap<>();
        for (StateMachines.Builder<?, ?, ?> builder : builders) {
            byKey.put(builder.key(), builder);
        }
        Map<MachineKey<?, ?, ?>, List<SubmachineBinding<?, ?, ?, ?, ?, ?>>> bindings = new HashMap<>();
        Map<MachineKey<?, ?, ?>, List<ImplicitAsyncEdges.BoundExits<?, ?, ?>>> exitsByChild = new HashMap<>();
        Set<Class<?>> facadeTypes = new HashSet<>();
        for (StateMachines.Builder<?, ?, ?> builder : builders) {
            builder.validate();
            checkWatches(builder.definition(), byKey.keySet());
            indexBindings(builder.definition(), byKey, bindings, exitsByChild);
            // Defensive: as(...) is typed by (E, S) and add(...) already rejects a duplicate key, so a typed caller
            // cannot reach this collision.
            Optional<? extends Class<?>> facade = builder.facade();
            if (facade.isPresent() && !facadeTypes.add(facade.get())) {
                throw new IllegalStateException(
                        "the facade type " + facade.get().getSimpleName() + " is registered twice");
            }
        }
        for (StateMachines.Builder<?, ?, ?> builder : builders) {
            if (builder.hasAsyncTimeout()) {
                continue;
            }
            Optional<String> edge = ImplicitAsyncEdges.firstAsyncImplicitEdge(builder.definition(), exitsByChild);
            if (edge.isPresent()) {
                throw new IllegalStateException("the machine " + builder.key() + " can run the asynchronous edge "
                        + edge.get() + " implicitly and does not declare asyncTimeout(...)");
            }
        }
        initializeRuntime();
        MachineRegistry registry = new MachineRegistry(maxCascadeDepth, maxWatchChain, observers);
        for (StateMachines.Builder<?, ?, ?> builder : builders) {
            registry.register(builder, bindings.getOrDefault(builder.key(), List.of()));
        }
        registry.indexWatchers();
        return registry;
    }

    /**
     * Builds the machine of {@code builder} into this registry, with a copy of {@code bindingsAsChild}, the bindings
     * in which it is the child, and registers it under its key, in the order it joined, and under its facade, if any.
     */
    private void register(StateMachines.Builder<?, ?, ?> builder,
                          List<SubmachineBinding<?, ?, ?, ?, ?, ?>> bindingsAsChild) {
        DefaultStateMachine<?, ?, ?> machine = builder.build(this, List.copyOf(bindingsAsChild));
        machines.put(machine.key(), machine);
        assembled.add(machine);
        Optional<? extends Class<?>> facade = builder.facade();
        if (facade.isPresent()) {
            facades.put(facade.get(), machine.key());
        }
    }

    /**
     * Every class that has a static initializer in the packages a call runs through: api, error, internal and runtime.
     * A static initializer that does not complete, because a StackOverflowError interrupts it at the bottom of a deep
     * call for one, leaves its class unusable for as long as the class is loaded, and every subclass with it (JLS
     * 12.4.2): each later use throws NoClassDefFoundError, as every later failure of every call would, had it been
     * CallFailedException's. A class without one cannot be left so once its superclass is initialized. A test over the
     * compiled classes keeps this list complete, and keeps those packages free of switches on another class's enum,
     * whose synthetic holder class has a static initializer that no source can name.
     */
    static List<Class<?>> staticallyInitialized() {
        return List.of(ActionStatus.class, CallOrigin.class, CallPhase.class, CallRequest.class,
                CascadePolicy.class, IgnoreReason.class, OutcomeCode.class, OutcomeStatus.class, RejectionKind.class,
                StatesWritten.class, TransitionKind.class, CallFailedException.class, CallFailureFlows.class,
                FrozenFlow.class, Await.class, CallLog.class, CommitSummary.class, FlowHandle.Hook.class,
                Observers.class, Reentrancy.class, Submachines.Hold.class, Submit.class, UnitOfWork.class,
                Watcher.NoOwner.class);
    }

    /**
     * Initialises each class of {@link #staticallyInitialized()} now, on the stack that assembles the registry, so
     * that none of their static initializers runs for the first time inside a call, where a call that overflows its
     * stack would leave the class unusable. A class already initialized costs a check.
     */
    private static void initializeRuntime() {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        for (Class<?> type : staticallyInitialized()) {
            try {
                lookup.ensureInitialized(type);
            } catch (IllegalAccessException unreachable) {
                // Each class is in this module and accessible from this package.
                throw new IllegalStateException(unreachable);
            }
        }
    }

    /** A watch must point to one of the machines being assembled; for a standalone machine, that is itself. */
    private static <E, S extends Enum<S>, T extends Enum<T>> void checkWatches(GraphDefinition<E, S, T> definition,
                                                                               Set<MachineKey<?, ?, ?>> keys) {
        for (S state : definition.key().states().getEnumConstants()) {
            Optional<WatchSpec<E, S, T, ?, ?, ?>> watch = definition.watch(state);
            if (watch.isPresent() && !keys.contains(watch.get().peer())) {
                throw new IllegalStateException("the watch of " + definition.key() + "." + state.name() + " points to "
                        + watch.get().peer() + ", which is not in the registry");
            }
        }
    }

    /**
     * Adds a binding for each sub-machine of {@code definition}, in state order, under the key of its child. The child
     * must be in the registry and built from the very definition the binding holds. Each binding's exits, with the
     * graph that resolves them, also go in {@code exitsByChild}, in the same order and under the same key.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> void indexBindings(
            GraphDefinition<E, S, T> definition, Map<MachineKey<?, ?, ?>, StateMachines.Builder<?, ?, ?>> builders,
            Map<MachineKey<?, ?, ?>, List<SubmachineBinding<?, ?, ?, ?, ?, ?>>> bindings,
            Map<MachineKey<?, ?, ?>, List<ImplicitAsyncEdges.BoundExits<?, ?, ?>>> exitsByChild) {
        for (S state : definition.key().states().getEnumConstants()) {
            Optional<SubmachineSpec<E, S, T, ?, ?, ?>> submachine = definition.submachine(state);
            if (submachine.isEmpty()) {
                continue;
            }
            SubmachineSpec<E, S, T, ?, ?, ?> spec = submachine.get();
            Optional<StateMachines.Builder<?, ?, ?>> child = Optional.ofNullable(builders.get(spec.childKey()));
            if (child.isEmpty()) {
                throw new IllegalStateException("the sub-machine of " + definition.key() + "." + state.name() + " is "
                        + spec.childKey() + ", which is not in the registry");
            }
            if (child.get().definition() != spec.child()) {
                throw new IllegalStateException("the sub-machine of " + definition.key() + "." + state.name()
                        + " was built from another definition of " + spec.childKey() + " than the registered machine");
            }
            bindings.computeIfAbsent(spec.childKey(), (MachineKey<?, ?, ?> key) -> new ArrayList<>())
                    .add(new SubmachineBinding<>(definition.key(), spec));
            exitsByChild.computeIfAbsent(spec.childKey(), (MachineKey<?, ?, ?> key) -> new ArrayList<>())
                    .add(new ImplicitAsyncEdges.BoundExits<>(definition, spec));
        }
    }

    /** Throws if not present: an Optional would end up in an orElse that swallows a wiring error. */
    public <E, S extends Enum<S>, T extends Enum<T>> StateMachine<E, S, T> of(@NonNull MachineKey<E, S, T> key) {
        return machineFor(key);
    }

    public Set<MachineKey<?, ?, ?>> keys() {
        return Collections.unmodifiableSet(machines.keySet());
    }

    /**
     * Closes only the default executors of its machines, blocking until the operations they already accepted end.
     * An executor the application supplied is neither closed nor awaited. An interrupt of the calling thread, before
     * or during the wait, reaches every default executor not yet closed, not only the one awaited: each interrupts the
     * threads of its operations, so a call waiting for a stage aborts as interrupted, and this method still waits for
     * them to end, then returns with the thread interrupted.
     *
     * @throws IllegalStateException before it closes anything, on a thread that runs an operation of one of those
     *         default executors, an asynchronous call or a {@code supplyAsync} supplier, which closing that executor
     *         would wait for forever, whatever calls it there: an action, a hook or a watch reaction of the call, a
     *         call a hook starts, or a dependent that completing the operation's future runs on that thread. The
     *         registry stays open.
     */
    @Override
    public void close() {
        for (DefaultStateMachine<?, ?, ?> machine : machines.values()) {
            machine.refuseCloseFromOwnTask();
        }
        for (DefaultStateMachine<?, ?, ?> machine : machines.values()) {
            machine.closeIfOwned();
        }
    }

    <E, S extends Enum<S>, T extends Enum<T>> DefaultStateMachine<E, S, T> machineFor(MachineKey<E, S, T> key) {
        DefaultStateMachine<?, ?, ?> machine = anyMachineFor(key);
        // the map is filled in assemble() with each machine's typed key: the key fixes E, S and T
        @SuppressWarnings("unchecked")
        DefaultStateMachine<E, S, T> typed = (DefaultStateMachine<E, S, T>) machine;
        return typed;
    }

    DefaultStateMachine<?, ?, ?> anyMachineFor(MachineKey<?, ?, ?> key) {
        if (assembled.size() <= REFERENCE_SCAN_LIMIT) {
            for (int index = 0; index < assembled.size(); index++) {
                DefaultStateMachine<?, ?, ?> registered = assembled.get(index);
                if (registered.key() == key) {
                    return registered;
                }
            }
        }
        DefaultStateMachine<?, ?, ?> machine = machines.get(key);
        if (machine == null) {
            throw new UnknownPeerMachineException(key, machines.keySet());
        }
        return machine;
    }

    /** Resolves a facade type to its key. The generics of the facade fix P, Q and U, so the cast is sound. */
    <P, Q extends Enum<Q>, U extends Enum<U>> MachineKey<P, Q, U> keyFor(Class<? extends Machine<P, Q, U>> facade) {
        MachineKey<?, ?, ?> key = facades.get(facade);
        if (key == null) {
            throw new UnknownFacadeException(facade, facades.keySet());
        }
        // put in assemble() from a Builder whose as(...) takes Class<? extends Machine<P, Q, U>>
        @SuppressWarnings("unchecked")
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
            watchers.computeIfAbsent(peer, (MachineKey<?, ?, ?> key) -> new ArrayList<>())
                    .add(Watcher.of(machine, watch.get()));
        }
    }

    /**
     * After the root commit: for each moved entity, every watcher of its machine resolves the owner; an owner is
     * re-evaluated only once even if several of its peers moved. A registry that indexed no watcher answers before
     * reading which entities moved, which is what most transactions pay. An owner lookup that throws a throwable that
     * is not an Error is an aborted reaction, a checked one wrapped in UndeclaredThrowableException, and an
     * InterruptedException interrupts the thread again. Each lookup runs with the reentrancy marker set, so one that
     * calls execute or reevaluate ends that way too. {@code log} is the log of the call whose commit is dispatched: at
     * TRACE it logs each lookup once, with what it found, the owner in the watched state once that owner is a target,
     * the state of an owner outside it, or no owner, or with what it threw, and when that call logs, each reaction's
     * log names its id as the trigger.
     */
    List<Reaction> dispatchWatches(CommitSummary summary, int chain, CallLog log) {
        if (watchers.isEmpty()) {
            return List.of();
        }
        Optional<Long> trigger = log.id() > 0 ? Optional.of(log.id()) : Optional.empty();
        Map<EntityRef, Watcher<?, ?, ?, ?, ?, ?>> targets = new LinkedHashMap<>();
        List<Reaction> reactions = new ArrayList<>();
        for (EntityRef moved : summary.movedEntities()) {
            for (Watcher<?, ?, ?, ?, ?, ?> watcher : watchers.getOrDefault(moved.key(), List.of())) {
                try {
                    Watcher.Lookup lookup = watcher.lookUpOwner(moved.instance());
                    if (lookup instanceof Watcher.Watched watched) {
                        targets.putIfAbsent(watched.owner(), watcher);
                    }
                    if (log.trace()) {
                        log.trace("watch watcher=" + watcher.machine().key() + " moved=" + CallLog.show(moved) + " "
                                + traced(lookup));
                    }
                } catch (RuntimeException e) {
                    traceLookupThrew(log, watcher, moved, e);
                    reactions.add(new Reaction.Aborted(moved.key(), moved.instance(), e));
                } catch (Error fatal) {
                    throw fatal;
                } catch (Throwable e) {
                    Interrupts.keep(e);
                    traceLookupThrew(log, watcher, moved, e);
                    reactions.add(new Reaction.Aborted(moved.key(), moved.instance(),
                            new UndeclaredThrowableException(e)));
                }
            }
        }
        for (Map.Entry<EntityRef, Watcher<?, ?, ?, ?, ?, ?>> entry : targets.entrySet()) {
            reactions.add(entry.getValue().react(entry.getKey().instance(), chain + 1, trigger));
        }
        return List.copyOf(reactions);
    }

    /**
     * How the TRACE line of an owner lookup names what it found: {@code owner=} and the owner in the watched state,
     * {@code owner-state=} and the state of an owner outside it, or {@code owner=none} when it found no owner.
     */
    private static String traced(Watcher.Lookup lookup) {
        if (lookup instanceof Watcher.Watched watched) {
            return "owner=" + CallLog.show(watched.owner());
        }
        if (lookup instanceof Watcher.Unwatched unwatched) {
            return "owner-state=" + unwatched.state().name();
        }
        return "owner=none";
    }

    /** Logs at TRACE that the lookup of {@code watcher} threw {@code error} for the moved entity {@code moved}. */
    private static void traceLookupThrew(CallLog log, Watcher<?, ?, ?, ?, ?, ?> watcher, EntityRef moved,
                                         Throwable error) {
        if (log.trace()) {
            log.trace("watch-lookup-threw watcher=" + watcher.machine().key() + " moved=" + CallLog.show(moved)
                    + " error=" + CallLog.show(error));
        }
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
        private final List<MachineObserver> observers = new ArrayList<>();
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

        /**
         * Bounds how deep a cascade may go, the root being at depth 0. The exit of a sub-machine counts at its own
         * depth: one level below the step that ended the child or, when the child stood in a terminal state while its
         * parent was still running the step that entered the bound state, one level below that step. The default is
         * 10, and a value below 1 is refused.
         */
        public Builder maxCascadeDepth(int depth) {
            requireUnused();
            if (depth < 1) {
                throw new IllegalArgumentException("maxCascadeDepth must be greater than or equal to 1");
            }
            this.maxCascadeDepth = depth;
            return this;
        }

        /**
         * Bounds how many watch reactions may follow one another; the default is 10, and a value below 1 is
         * refused. A call that an after-commit hook starts is a new root, which this bound never counts: the watch
         * reactions it triggers begin a chain of their own, so hooks that keep starting calls recur until the stack of
         * the thread overflows, as the class description says.
         */
        public Builder maxWatchChain(int chain) {
            requireUnused();
            if (chain < 1) {
                throw new IllegalArgumentException("maxWatchChain must be greater than or equal to 1");
            }
            this.maxWatchChain = chain;
            return this;
        }

        /**
         * Adds an observer of every call of the registry's machines. Observers add up and are notified in the order
         * they were added; see {@link MachineObserver} for what an observer is told and what it cannot do.
         */
        public Builder observer(MachineObserver observer) {
            requireUnused();
            Objects.requireNonNull(observer, "observer");
            observers.add(observer);
            return this;
        }

        /**
         * Checks every machine builder, its identity, accessors, asyncTimeout, watches, sub-machines and facade,
         * before it consumes any of them; only a build that succeeds marks this builder and its machine builders used.
         */
        public MachineRegistry build() {
            requireUnused();
            for (StateMachines.Builder<?, ?, ?> builder : builders) {
                if (!builder.hasIdentity()) {
                    throw new IllegalStateException(
                            "the machine " + builder.key() + " must declare identity(...) within a registry");
                }
            }
            Observers registered = observers.isEmpty() ? Observers.NONE : new Observers(observers);
            MachineRegistry registry = assemble(builders, maxCascadeDepth, maxWatchChain, registered);
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
