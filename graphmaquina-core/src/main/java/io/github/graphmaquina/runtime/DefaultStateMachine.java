package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.AsyncStateMachine;
import io.github.graphmaquina.api.EdgeKind;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.HookRef;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionListener;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.api.WatchRule;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.error.AbortDetails;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.UninitializedStateException;
import io.github.graphmaquina.error.WatchChainExceededException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.Getter;

/** Runtime {@link StateMachine}: resolves cells, runs a root transaction and commits in two phases. */
final class DefaultStateMachine<E, S extends Enum<S>> implements StateMachine<E, S> {

    @Getter
    private final GraphDefinition<E, S> definition;
    private final Function<? super E, ? extends S> getter;
    private final BiConsumer<? super E, ? super S> setter;
    private final Optional<Function<? super E, ?>> identity;
    @Getter
    private final Optional<Duration> asyncTimeout;
    @Getter
    private final Executor executor;
    private final boolean ownsExecutor;
    private final MachineRegistry registry;
    private final DefaultAsyncStateMachine<E, S> async;

    DefaultStateMachine(GraphDefinition<E, S> definition, Function<? super E, ? extends S> getter,
                        BiConsumer<? super E, ? super S> setter, Optional<Function<? super E, ?>> identity,
                        Optional<Duration> asyncTimeout, Executor executor, boolean ownsExecutor, MachineRegistry registry) {
        this.definition = definition;
        this.getter = getter;
        this.setter = setter;
        this.identity = identity;
        this.asyncTimeout = asyncTimeout;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        this.registry = registry;
        this.async = new DefaultAsyncStateMachine<>(this);
    }

    @Override
    public MachineKey<E, S> key() { return definition.key(); }

    @Override
    public Set<S> allowedFrom(S state) {
        Objects.requireNonNull(state, "state");
        return definition.allowedFrom(state);
    }

    @Override
    public Outcome<E, S> transitionTo(E entity, S target) {
        return transitionTo(entity, target, null);
    }

    @Override
    public Outcome<E, S> transitionTo(E entity, S target, Object input) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(target, "target");
        return runRoot(entity, (Transaction tx) -> tx.transition(this, entity, target, 0, input), 0);
    }

    @Override
    public Outcome<E, S> reevaluate(E entity) {
        Objects.requireNonNull(entity, "entity");
        return reevaluate(entity, 0);
    }

    /** chain counts reaction -> transition -> reaction. Exceeding maxWatchChain aborts before evaluating anything. */
    Outcome<E, S> reevaluate(E entity, int chain) {
        Reentrancy.check(key(), entity);
        S state = readState(entity);
        if (chain > registry.maxWatchChain()) {
            AbortDetails details = new AbortDetails(key(), entity, state, state, Optional.empty(), List.of(), List.of());
            throw new WatchChainExceededException(details, registry.maxWatchChain());
        }
        Optional<WatchSpec<E, S, ?, ?>> watch = definition.watch(state);
        if (watch.isEmpty()) {
            return notify(new Outcome.Ignored<>(entity, state, state));
        }
        Optional<S> target = firstMatch(watch.get(), entity);
        if (target.isEmpty()) {
            return notify(new Outcome.Ignored<>(entity, state, state));
        }
        S resolved = target.get();
        return runRoot(entity, (Transaction tx) -> tx.transition(this, entity, resolved, 0), chain);
    }

    /** Rules in order; the first one wins. The view is fresh: outside any transaction it reads the getters. */
    private <P, Q extends Enum<Q>> Optional<S> firstMatch(WatchSpec<E, S, P, Q> watch, E entity) {
        Collection<? extends P> peers = watch.peersOf().apply(entity);
        PeerView<P, Q> view = new DefaultPeerView<>(() -> { },
                new UnitOfWork(registry.maxCascadeDepth(), Long.MAX_VALUE), registry.machineFor(watch.peer()));
        for (WatchRule<E, S, P, Q> rule : watch.rules()) {
            if (rule.condition().condition().test(entity, peers, view)) {
                return Optional.of(rule.target());
            }
        }
        return Optional.empty();
    }

    @Override
    public AsyncStateMachine<E, S> async() {
        return async;
    }

    @Override
    public <T> CompletableFuture<T> supplyAsync(Supplier<T> call) {
        Objects.requireNonNull(call, "call");
        try {
            return CompletableFuture.supplyAsync(call, executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Root transaction: reentrancy marker, new UoW, step, phase 2, context invalidation.
     * Watch reactions are dispatched after the marker is released, so a reaction may start its own transaction.
     */
    Outcome<E, S> runRoot(E entity, Function<Transaction, StepResult<S>> body, int chain) {
        Reentrancy.enter(key(), entity);
        UnitOfWork uow = new UnitOfWork(registry.maxCascadeDepth(), deadlineNanos());
        Transaction tx = new Transaction(registry, uow);
        StepResult<S> step;
        List<TransitionRecord<?, ?>> records;
        try {
            step = body.apply(tx);
            records = switch (step) {
                case StepResult.Moved<S> moved -> Commit.apply(registry, uow);
                case StepResult.FailedTo<S> failed -> Commit.apply(registry, uow);
                case StepResult.Ignored<S> ignored -> List.of();
                case StepResult.Rejected<S> rejected -> List.of();
            };
        } finally {
            tx.invalidate();
            Reentrancy.exit();
        }
        runHooks(records);
        List<Reaction> reactions = switch (step) {
            case StepResult.Moved<S> moved -> registry.dispatchWatches(uow, chain);
            case StepResult.FailedTo<S> failed -> registry.dispatchWatches(uow, chain);
            case StepResult.Ignored<S> ignored -> List.of();
            case StepResult.Rejected<S> rejected -> List.of();
        };
        return notify(switch (step) {
            case StepResult.Moved<S> moved -> new Outcome.Applied<>(entity, moved.from(), moved.to(), records, reactions);
            case StepResult.Ignored<S> ignored -> new Outcome.Ignored<>(entity, ignored.from(), ignored.to());
            case StepResult.Rejected<S> rejected ->
                    new Outcome.Rejected<>(entity, rejected.from(), rejected.to(), rejected.reason(), rejected.guard());
            case StepResult.FailedTo<S> failed -> new Outcome.Failed<>(entity, failed.from(), failed.to(), failed.movedTo(),
                    failed.reason(), failed.cause(), records, reactions);
        });
    }

    /** Spec §6.1 step 7: the registry listener sees every outcome of every call. */
    private Outcome<E, S> notify(Outcome<E, S> outcome) {
        Optional<TransitionListener> listener = registry.listener();
        if (listener.isEmpty()) {
            return outcome;
        }
        try {
            listener.get().onOutcome(key(), outcome);
        } catch (RuntimeException e) {
            throw new AfterCommitException(key(), outcome.entity(), outcome.from(), outcome.current(), "listener", e);
        }
        return outcome;
    }

    /** Spec §6.1 step 4: every committed hop with a hook, in log order, on the calling thread. */
    private void runHooks(List<TransitionRecord<?, ?>> records) {
        for (TransitionRecord<?, ?> record : records) {
            if (record.convergent() || !(record.kind() == EdgeKind.ACTION || record.kind() == EdgeKind.MOVE)) {
                continue;
            }
            runHook(record);
        }
    }

    private <P, Q extends Enum<Q>> void runHook(TransitionRecord<P, Q> record) {
        DefaultStateMachine<P, Q> owner = registry.machineFor(record.key());
        Optional<HookRef<P>> hook = owner.definition().edge(record.from(), record.to()).afterCommit();
        if (hook.isEmpty()) {
            return;
        }
        try {
            hook.get().hook().accept(record.entity());
        } catch (RuntimeException e) {
            throw new AfterCommitException(record.key(), record.entity(), record.from(), record.to(), hook.get().name(), e);
        }
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

    long deadlineNanos() {
        if (asyncTimeout.isEmpty()) {
            return Long.MAX_VALUE;
        }
        return System.nanoTime() + asyncTimeout.get().toNanos();
    }

    void closeIfOwned() {
        if (ownsExecutor && executor instanceof ExecutorService service) {
            service.close();
        }
    }
}
