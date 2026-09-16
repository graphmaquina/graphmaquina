package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.AsyncStateMachine;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.HookRef;
import io.github.graphmaquina.api.IgnoreReason;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionListener;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.api.WatchRule;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.error.AbortDetails;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.UninitializedStateException;
import io.github.graphmaquina.error.WatchChainExceededException;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
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

/** Runtime {@link StateMachine}: resolves edges by identity, runs a root transaction and commits in two phases. */
final class DefaultStateMachine<E, S extends Enum<S>, T extends Enum<T>> implements StateMachine<E, S, T> {

    @Getter
    private final GraphDefinition<E, S, T> definition;
    private final Function<? super E, ? extends S> getter;
    private final BiConsumer<? super E, ? super S> setter;
    private final Optional<Function<? super E, ?>> identity;
    @Getter
    private final Optional<Duration> asyncTimeout;
    @Getter
    private final Executor executor;
    private final boolean ownsExecutor;
    private final MachineRegistry registry;
    private final DefaultAsyncStateMachine<E, S, T> async;

    DefaultStateMachine(GraphDefinition<E, S, T> definition, Function<? super E, ? extends S> getter,
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
    public MachineKey<E, S, T> key() { return definition.key(); }

    @Override
    public Set<T> transitionsFrom(S state) {
        Objects.requireNonNull(state, "state");
        EnumSet<T> declared = EnumSet.noneOf(key().transitions());
        for (TransitionSpec<E, S, T> spec : definition.outgoingFrom(state)) {
            declared.add(spec.transition());
        }
        return Collections.unmodifiableSet(declared);
    }

    @Override
    public Outcome<E, S, T> execute(E entity, T transition) {
        return execute(entity, transition, null);
    }

    @Override
    public Outcome<E, S, T> execute(E entity, T transition, Object input) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(transition, "transition");
        return runRoot(entity, (Transaction tx) -> tx.transition(this, entity, transition, 0, input), 0);
    }

    @Override
    public Outcome<E, S, T> reevaluate(E entity) {
        Objects.requireNonNull(entity, "entity");
        return reevaluate(entity, 0);
    }

    /** chain counts reaction -> transition -> reaction. Exceeding maxWatchChain aborts before evaluating anything. */
    Outcome<E, S, T> reevaluate(E entity, int chain) {
        Reentrancy.check(key(), entity);
        S state = readState(entity);
        if (chain > registry.maxWatchChain()) {
            AbortDetails details = new AbortDetails(key(), entity, state, Optional.empty(), Optional.empty(),
                    Optional.empty(), List.of(), List.of());
            throw new WatchChainExceededException(details, registry.maxWatchChain());
        }
        Optional<WatchSpec<E, S, T, ?, ?, ?>> watch = definition.watch(state);
        if (watch.isEmpty()) {
            return notify(new Outcome.Ignored<>(entity, state, IgnoreReason.NO_WATCH));
        }
        Optional<T> selected = firstMatch(watch.get(), entity);
        if (selected.isEmpty()) {
            return notify(new Outcome.Ignored<>(entity, state, IgnoreReason.NO_RULE_MATCHED));
        }
        T resolved = selected.orElseThrow();
        return runRoot(entity, (Transaction tx) -> tx.transition(this, entity, resolved, 0), chain);
    }

    /** Rules in order; the first one wins. The view is fresh: outside any transaction it reads the getters. */
    private <P, Q extends Enum<Q>, U extends Enum<U>> Optional<T> firstMatch(WatchSpec<E, S, T, P, Q, U> watch, E entity) {
        Collection<? extends P> peers = watch.peersOf().apply(entity);
        PeerView<P, Q, U> view = new DefaultPeerView<>(() -> { },
                new UnitOfWork(registry.maxCascadeDepth(), Long.MAX_VALUE), registry.machineFor(watch.peer()));
        for (WatchRule<E, S, T, P, Q, U> rule : watch.rules()) {
            if (rule.condition().condition().test(entity, peers, view)) {
                return Optional.of(rule.transition());
            }
        }
        return Optional.empty();
    }

    @Override
    public AsyncStateMachine<E, S, T> async() {
        return async;
    }

    @Override
    public <R> CompletableFuture<R> supplyAsync(Supplier<R> call) {
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
    Outcome<E, S, T> runRoot(E entity, Function<Transaction, StepResult<S, T>> body, int chain) {
        Reentrancy.enter(key(), entity);
        UnitOfWork uow = new UnitOfWork(registry.maxCascadeDepth(), deadlineNanos());
        Transaction tx = new Transaction(registry, uow);
        StepResult<S, T> step;
        List<TransitionRecord<?, ?, ?>> records;
        List<LogEntry> committed;
        try {
            step = body.apply(tx);
            boolean commits = step instanceof StepResult.Applied<S, T> || step instanceof StepResult.Failed<S, T>;
            if (commits) {
                Commit.apply(registry, uow);
                records = uow.records();
                committed = uow.entries();
            } else {
                records = List.of();
                committed = List.of();
            }
        } finally {
            tx.invalidate();
            Reentrancy.exit();
        }
        runHooks(committed);
        List<Reaction> reactions = committed.isEmpty() ? List.of() : registry.dispatchWatches(uow, chain);
        return notify(switch (step) {
            case StepResult.Applied<S, T> applied ->
                    new Outcome.Applied<>(entity, applied.from(), applied.transition(), applied.to(), records, reactions);
            case StepResult.Ignored<S, T> ignored -> new Outcome.Ignored<>(entity, ignored.from(), ignored.reason());
            case StepResult.Rejected<S, T> rejected -> new Outcome.Rejected<>(entity, rejected.from(),
                    rejected.transition(), rejected.reason(), rejected.kind(), rejected.guard());
            case StepResult.Failed<S, T> failed -> new Outcome.Failed<>(entity, failed.from(), failed.transition(),
                    failed.to(), failed.failureTransition(), failed.movedTo(), failed.reason(), failed.cause(),
                    records, reactions);
        });
    }

    /** The registry listener sees every outcome of every call. */
    private Outcome<E, S, T> notify(Outcome<E, S, T> outcome) {
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

    /** Every committed hop with a hook, in log order, on the calling thread, using the edge that was selected. */
    private void runHooks(List<LogEntry> committed) {
        for (LogEntry entry : committed) {
            runHook(registry.anyMachineFor(entry.ref().key()), entry);
        }
    }

    private <P, Q extends Enum<Q>, U extends Enum<U>> void runHook(DefaultStateMachine<P, Q, U> owner, LogEntry entry) {
        TransitionSpec<P, Q, U> spec = Typed.spec(owner.key(), entry);
        if (spec.afterCommit().isEmpty()) {
            return;
        }
        HookRef<P, Q, U> hook = spec.afterCommit().orElseThrow();
        P target = owner.key().entity().cast(entry.ref().instance());
        CommitContext<Q, U> context = new CommitContext<>(spec.from(), spec.transition(), spec.to(), spec.kind(),
                owner.definition().terminals().contains(spec.to()));
        try {
            hook.run(target, context);
        } catch (RuntimeException e) {
            throw new AfterCommitException(owner.key(), target, spec.from(), spec.to(), hook.name(), e);
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
