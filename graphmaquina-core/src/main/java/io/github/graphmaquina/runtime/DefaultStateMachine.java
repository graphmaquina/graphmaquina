package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.AsyncStateMachine;
import io.github.graphmaquina.api.CallOrigin;
import io.github.graphmaquina.api.CallRequest;
import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.error.UninitializedStateException;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;

/**
 * Runtime {@link StateMachine}: runs a root transaction per call, which resolves the edge from the entity's current
 * state and the identifier and commits in two phases; a reevaluation that selects no edge runs none, and neither does
 * an advance whose state has no automatic edge. An advance tries the automatic edges of the state as {@link Attempts}
 * says. An eligibility check runs the admission of that root step alone, as {@link Checks} says, and is no call.
 */
final class DefaultStateMachine<E, S extends Enum<S>, T extends Enum<T>> implements StateMachine<E, S, T> {

    @Getter
    private final GraphDefinition<E, S, T> definition;
    private final Function<? super E, ? extends S> getter;
    private final BiConsumer<? super E, ? super S> setter;
    private final Optional<Function<? super E, ?>> identity;
    @Getter
    private final Optional<Duration> asyncTimeout;
    private final Executor executor;
    private final boolean ownsExecutor;

    /** The registry this machine was assembled into, which every call of it runs in. */
    @Getter(AccessLevel.PACKAGE)
    private final MachineRegistry registry;
    private final DefaultAsyncStateMachine<E, S, T> async;
    /**
     * {@code Optional.of(id)} for each identifier of the catalogue, by ordinal: execute hands its root the identifier
     * it was asked for without allocating an Optional.
     */
    private final List<Optional<Enum<?>>> presentTransitions;
    /** See {@link #bindingsAsChild()}. */
    private final List<SubmachineBinding<?, ?, ?, ?, ?, ?>> bindingsAsChild;
    /** See {@link #bindsSubmachines()}. */
    private final boolean bindsSubmachines;
    /** See {@link #hasBindings()}. */
    private final boolean hasBindings;
    /** See {@link #trail()}. */
    private final Optional<InstantSource> trail;
    /** See {@link #releasedTransaction()}. */
    private final Transaction released;

    DefaultStateMachine(GraphDefinition<E, S, T> definition, Function<? super E, ? extends S> getter,
                        BiConsumer<? super E, ? super S> setter, Optional<Function<? super E, ?>> identity,
                        Optional<Duration> asyncTimeout, Executor executor, boolean ownsExecutor,
                        MachineRegistry registry, List<SubmachineBinding<?, ?, ?, ?, ?, ?>> bindingsAsChild,
                        Optional<InstantSource> trail) {
        this.definition = definition;
        this.getter = getter;
        this.setter = setter;
        this.identity = identity;
        this.asyncTimeout = asyncTimeout;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        this.registry = registry;
        this.async = new DefaultAsyncStateMachine<>(this);
        this.presentTransitions = present(definition.key().transitions());
        this.bindingsAsChild = bindingsAsChild;
        this.bindsSubmachines = bindsSubmachines(definition);
        this.hasBindings = bindsSubmachines || !bindingsAsChild.isEmpty();
        this.trail = trail;
        this.released = Transaction.released(registry, definition.key());
    }

    /** Whether some state of {@code definition} holds a sub-machine. */
    private static <E, S extends Enum<S>, T extends Enum<T>> boolean bindsSubmachines(
            GraphDefinition<E, S, T> definition) {
        for (S state : definition.key().states().getEnumConstants()) {
            if (definition.submachine(state).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The bindings in which this machine is the child, in the order the registry indexed them: the order of its
     * builders, then of each parent's states. Immutable, and empty for a machine that no definition binds.
     */
    List<SubmachineBinding<?, ?, ?, ?, ?, ?>> bindingsAsChild() { return bindingsAsChild; }

    /** Whether some state of this machine's own definition holds a sub-machine; computed once, at construction. */
    boolean bindsSubmachines() { return bindsSubmachines; }

    /** The clock of the trail of every root call this machine starts; empty when it records no trail. */
    Optional<InstantSource> trail() { return trail; }

    /**
     * The transaction of no call that a context of this machine holds once invalidated, in place of its call's, as
     * {@link Transaction#released} says; made once, at construction, so that invalidating a context allocates nothing.
     */
    Transaction releasedTransaction() { return released; }

    /**
     * Whether this machine takes part in a binding: its own definition binds a sub-machine, or some definition binds
     * it as one. Computed once, at construction.
     */
    boolean hasBindings() { return hasBindings; }

    /** {@code Optional.of(constant)} for each constant of {@code type}, in ordinal order. */
    private static List<Optional<Enum<?>>> present(Class<? extends Enum<?>> type) {
        List<Optional<Enum<?>>> present = new ArrayList<>();
        for (Enum<?> constant : type.getEnumConstants()) {
            present.add(Optional.of(constant));
        }
        return List.copyOf(present);
    }

    @Override
    public MachineKey<E, S, T> key() { return definition.key(); }

    @Override
    public Set<T> transitionsFrom(@NonNull S state) {
        EnumSet<T> declared = EnumSet.noneOf(key().transitions());
        for (TransitionSpec<E, S, T> spec : definition.outgoingFrom(state)) {
            declared.add(spec.transition());
        }
        return Collections.unmodifiableSet(declared);
    }

    @Override
    public Outcome<E, S, T> execute(@NonNull E entity, @NonNull T transition) {
        return executeRoot(entity, transition, Optional.empty());
    }

    @Override
    public Outcome<E, S, T> execute(@NonNull E entity, @NonNull T transition, @NonNull Object input) {
        return executeRoot(entity, transition, Optional.of(input));
    }

    /**
     * Runs the root call of {@code execute} over arguments already checked for null; {@code operationInput} is empty
     * for the overload that takes no input.
     */
    private Outcome<E, S, T> executeRoot(E entity, T transition, Optional<Object> operationInput) {
        Transaction.requireTransition(key(), transition);
        Reentrancy.check(key(), entity);
        Optional<Enum<?>> requested = presentTransitions.get(transition.ordinal());
        CallLog log = CallLog.open(Optional.empty());
        Optional<Observed> observed = RootCall.observe(this, entity, CallRequest.EXECUTE, requested,
                CallOrigin.APPLICATION, log);
        return RootCall.run(this, entity, CallRequest.EXECUTE, requested, CallOrigin.APPLICATION, observed, log,
                (Transaction tx) -> tx.transition(this, entity, transition, 0, operationInput), new ChainLevels(0, 0),
                Optional.empty()).outcome();
    }

    @Override
    public Eligibility<E, S, T> eligibility(@NonNull E entity, @NonNull T transition) {
        return Checks.check(this, entity, transition, Optional.empty());
    }

    @Override
    public Eligibility<E, S, T> eligibility(@NonNull E entity, @NonNull T transition, @NonNull Object input) {
        return Checks.check(this, entity, transition, Optional.of(input));
    }

    @Override
    public Outcome<E, S, T> reevaluate(@NonNull E entity) {
        return Reevaluation.reevaluate(this, entity, new ChainLevels(0, 0), Optional.empty());
    }

    @Override
    public Outcome<E, S, T> advance(@NonNull E entity) {
        return Attempts.attempt(this, entity, CallOrigin.APPLICATION, Optional.empty(), new ChainLevels(0, 1),
                Optional.empty()).outcome();
    }

    @Override
    public AsyncStateMachine<E, S, T> async() {
        return async;
    }

    @Override
    public <R> CompletableFuture<R> supplyAsync(@NonNull Supplier<R> call) {
        return submit(call);
    }

    /** Submits {@code call} to this machine's executor through {@link Submit}, which marks a default one's thread. */
    <R> CompletableFuture<R> submit(Supplier<R> call) {
        return Submit.supply(executor, ownsExecutor, call);
    }

    EntityRef refOf(E entity) {
        if (identity.isPresent()) {
            Object id = identity.get().apply(entity);
            if (id == null) {
                throw new NullPointerException("identity of " + key() + " returned null");
            }
            return new EntityRef(key(), id, entity);
        }
        return new EntityRef(key(), new IdentityKey(entity), entity);
    }

    S readState(E entity) {
        S state = getter.apply(entity);
        if (state == null) {
            throw new UninitializedStateException(key(), entity);
        }
        return state;
    }

    /** For phase 2: reads without requiring non-null. */
    Optional<Enum<?>> peekState(Object instance) {
        Enum<?> state = getter.apply(key().entity().cast(instance));
        return Optional.ofNullable(state);
    }

    void writeState(Object instance, Enum<?> state) {
        setter.accept(key().entity().cast(instance), key().states().cast(state));
    }

    /**
     * The root's deadline, or Long.MAX_VALUE for no limit: no asyncTimeout, or one of
     * {@code Duration.ofNanos(Long.MAX_VALUE)} or more. It never throws. A sum that wraps past Long.MAX_VALUE stays
     * exact for Await, which only subtracts from it.
     */
    long deadlineNanos() {
        if (asyncTimeout.isEmpty()) {
            return Long.MAX_VALUE;
        }
        long budget = Await.budgetNanos(asyncTimeout.get());
        return budget == Long.MAX_VALUE ? Long.MAX_VALUE : System.nanoTime() + budget;
    }

    /**
     * Refuses to close this machine's default executor from a task of its own, which ExecutorService.close() would
     * wait for forever: the task that is calling. Checked for every machine before the registry closes any.
     */
    void refuseCloseFromOwnTask() {
        if (ownsExecutor && Submit.runsTaskOf(executor)) {
            throw new IllegalStateException("close() was called from a task of the default executor of " + key()
                    + ", which it would wait for forever: close the registry from another thread");
        }
    }

    void closeIfOwned() {
        if (ownsExecutor && executor instanceof ExecutorService service) {
            service.close();
        }
    }
}
