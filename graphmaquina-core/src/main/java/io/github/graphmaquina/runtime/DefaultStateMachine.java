package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.AsyncStateMachine;
import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.HookRef;
import io.github.graphmaquina.api.IgnoreReason;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeStatus;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatesWritten;
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

/**
 * Runtime {@link StateMachine}: runs a root transaction per call, which resolves the edge from the entity's current
 * state and the identifier and commits in two phases; a reevaluation that selects no edge runs none.
 */
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
        requireTransition(transition);
        Reentrancy.check(key(), entity);
        Optional<Observed> observed = observe(entity, transition, false);
        return runRoot(entity, observed, (Transaction tx) -> tx.transition(this, entity, transition, 0, input), 0);
    }

    void requireTransition(T transition) {
        if (!key().transitions().isInstance(transition)) {
            throw new IllegalArgumentException("transition must belong to " + key().transitions().getName() + ": " + transition);
        }
    }

    @Override
    public Outcome<E, S, T> reevaluate(E entity) {
        Objects.requireNonNull(entity, "entity");
        return reevaluate(entity, 0);
    }

    /**
     * chain counts reaction -> transition -> reaction, so a chain above 0 is a watch reaction. Exceeding
     * maxWatchChain throws WatchChainExceededException after the reentrancy check and the state read, before any
     * watch function runs. The state read and the watch functions, peersOf and the conditions, run with the
     * reentrancy marker set, so none of them can start a transition on this thread: a nested root would begin a chain
     * of its own that maxWatchChain never counts. The marker is released before the selected edge runs as a root.
     * Observers see the whole of it as one call, which ends in RESOLVE if it throws before an edge runs.
     */
    Outcome<E, S, T> reevaluate(E entity, int chain) {
        Reentrancy.check(key(), entity);
        Optional<Observed> observed = observe(entity, null, chain > 0);
        S state;
        Optional<WatchSpec<E, S, T, ?, ?, ?>> watch;
        Optional<T> selected = Optional.empty();
        try {
            Reentrancy.enter(key(), entity);
            try {
                state = readState(entity);
                if (chain > registry.maxWatchChain()) {
                    AbortDetails details = new AbortDetails(key(), entity, state, Optional.empty(), Optional.empty(),
                            Optional.empty(), List.of(), List.of());
                    throw new WatchChainExceededException(details, registry.maxWatchChain());
                }
                watch = definition.watch(state);
                if (watch.isPresent()) {
                    selected = firstMatch(watch.get(), entity);
                }
            } finally {
                Reentrancy.exit();
            }
        } catch (Throwable thrown) {
            failed(observed, CallPhase.RESOLVE, StatesWritten.NONE, thrown);
            throw thrown;
        }
        if (watch.isEmpty()) {
            return ignored(observed, new Outcome.Ignored<>(entity, state, IgnoreReason.NO_WATCH));
        }
        if (selected.isEmpty()) {
            return ignored(observed, new Outcome.Ignored<>(entity, state, IgnoreReason.NO_RULE_MATCHED));
        }
        T resolved = selected.orElseThrow();
        return runRoot(entity, observed, (Transaction tx) -> tx.transition(this, entity, resolved, 0), chain);
    }

    /** A reevaluation that selected nothing: the listener is told inside the call, so an observer sees its failure. */
    private Outcome<E, S, T> ignored(Optional<Observed> observed, Outcome<E, S, T> outcome) {
        try {
            notify(outcome);
        } catch (Throwable thrown) {
            failed(observed, CallPhase.RESOLVE, StatesWritten.NONE, thrown);
            throw thrown;
        }
        return completed(observed, outcome);
    }

    /** Rules in order; the first one wins. The view is fresh: outside any transaction it reads the getters. */
    private <P, Q extends Enum<Q>, U extends Enum<U>> Optional<T> firstMatch(WatchSpec<E, S, T, P, Q, U> watch, E entity) {
        Collection<? extends P> peers = watch.peersOf().apply(entity);
        PeerView<P, Q, U> view = DefaultPeerView.committed(registry.machineFor(watch.peer()));
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
     * Root transaction: new UoW with its deadline, reentrancy marker, step, phase 2, context invalidation.
     * Everything is prepared before the marker is set, so that from {@code Reentrancy.enter} on, the only way out
     * runs through the {@code finally} that releases it, whatever the step throws.
     * Watch reactions are dispatched after the marker is released, so a reaction may start its own transaction; the
     * dispatch sets the marker again only while it runs an owner lookup, and a reevaluation while it runs its watch.
     */
    /**
     * Root transaction: new UoW with its deadline, reentrancy marker, step, phase 2, context invalidation, then the
     * hooks and the watch reactions of what it committed. Everything is prepared before the marker is set, so that
     * from {@code Reentrancy.enter} on, the only way out runs through the {@code finally} that releases it, whatever
     * the step throws. The transaction keeps the phase of the call, which the observers are told when it throws.
     * Watch reactions are dispatched after the marker is released, so a reaction may start its own transaction; the
     * dispatch sets the marker again only while it runs an owner lookup, and a reevaluation while it runs its watch.
     */
    Outcome<E, S, T> runRoot(E entity, Optional<Observed> observed, Function<Transaction, StepResult<S, T>> body,
                             int chain) {
        UnitOfWork uow = new UnitOfWork(registry.maxCascadeDepth(), deadlineNanos());
        Transaction tx = new Transaction(registry, uow);
        Outcome<E, S, T> outcome;
        try {
            StepResult<S, T> step;
            List<TransitionRecord<?, ?, ?>> records;
            List<LogEntry> committed;
            CommitSummary summary;
            Reentrancy.enter(key(), entity);
            try {
                step = body.apply(tx);
                boolean commits = step instanceof StepResult.Applied<S, T> || step instanceof StepResult.Failed<S, T>;
                if (commits) {
                    tx.enter(CallPhase.COMMIT);
                    summary = Commit.apply(registry, tx);
                    records = uow.records();
                    committed = uow.entries();
                } else {
                    summary = CommitSummary.EMPTY;
                    records = List.of();
                    committed = List.of();
                }
            } finally {
                tx.invalidate();
                Reentrancy.exit();
            }
            List<Reaction> reactions = List.of();
            if (!committed.isEmpty()) {
                tx.enter(CallPhase.HOOK);
                runHooks(committed);
                tx.enter(CallPhase.REACTIONS);
                reactions = registry.dispatchWatches(summary, chain);
            }
            outcome = notify(switch (step) {
                case StepResult.Applied<S, T> applied ->
                        new Outcome.Applied<>(entity, applied.from(), applied.transition(), applied.to(), records, reactions);
                case StepResult.Ignored<S, T> ignored -> new Outcome.Ignored<>(entity, ignored.from(), ignored.reason());
                case StepResult.Rejected<S, T> rejected -> new Outcome.Rejected<>(entity, rejected.from(),
                        rejected.transition(), rejected.reason(), rejected.kind(), rejected.guard());
                case StepResult.Failed<S, T> failed -> new Outcome.Failed<>(entity, failed.from(), failed.transition(),
                        failed.to(), failed.failureTransition(), failed.movedTo(), failed.reason(), failed.cause(),
                        records, reactions);
            });
        } catch (Throwable thrown) {
            failed(observed, tx.phase(), tx.statesWritten(), thrown);
            throw thrown;
        }
        return completed(observed, outcome);
    }

    /** Tells the registry's observers that a call starts; reads no clock and allocates nothing when it has none. */
    private Optional<Observed> observe(E entity, T requested, boolean reaction) {
        Observers observers = registry.observers();
        if (observers.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(observers.started(new MachineCall(key(), entity, Optional.ofNullable(requested), reaction)));
    }

    private static <O extends Outcome<?, ?, ?>> O completed(Optional<Observed> observed, O outcome) {
        if (observed.isPresent()) {
            observed.get().completed(outcome);
        }
        return outcome;
    }

    private static void failed(Optional<Observed> observed, CallPhase phase, StatesWritten written, Throwable thrown) {
        if (observed.isPresent()) {
            observed.get().failed(new CallFailure(phase, written, thrown));
        }
    }

    /**
     * The registry listener sees the outcome of every call that gets this far. A call that throws first notifies
     * nothing, such as one whose step aborts, whose commit fails, whose hook throws or whose reaction raises an Error.
     * Any Exception the listener throws, checked ones included, becomes an AfterCommitException that says whether the
     * call wrote states: only Applied and Failed outcomes did. An Error propagates unchanged.
     */
    private Outcome<E, S, T> notify(Outcome<E, S, T> outcome) {
        Optional<TransitionListener> listener = registry.listener();
        if (listener.isEmpty()) {
            return outcome;
        }
        try {
            listener.get().onOutcome(key(), outcome);
        } catch (Exception e) {
            boolean written = outcome.status() == OutcomeStatus.APPLIED || outcome.status() == OutcomeStatus.FAILED;
            throw new AfterCommitException(key(), outcome.entity(), outcome.from(), outcome.recordedState(), "listener",
                    written, e);
        }
        return outcome;
    }

    /**
     * Every committed hop with a hook, in log order, on the calling thread, using the edge that was selected. Any
     * Exception a hook throws, checked ones included, becomes an AfterCommitException; an Error propagates unchanged.
     */
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
        } catch (Exception e) {
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

    void closeIfOwned() {
        if (ownsExecutor && executor instanceof ExecutorService service) {
            service.close();
        }
    }
}
