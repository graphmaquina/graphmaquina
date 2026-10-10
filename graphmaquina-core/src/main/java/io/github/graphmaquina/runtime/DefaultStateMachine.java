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
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.SubmachineStart;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.api.WatchRule;
import io.github.graphmaquina.api.WatchSpec;
import io.github.graphmaquina.error.AbortDetails;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UninitializedStateException;
import io.github.graphmaquina.error.WatchChainExceededException;
import java.time.Duration;
import java.util.ArrayList;
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

    DefaultStateMachine(GraphDefinition<E, S, T> definition, Function<? super E, ? extends S> getter,
                        BiConsumer<? super E, ? super S> setter, Optional<Function<? super E, ?>> identity,
                        Optional<Duration> asyncTimeout, Executor executor, boolean ownsExecutor,
                        MachineRegistry registry, List<SubmachineBinding<?, ?, ?, ?, ?, ?>> bindingsAsChild) {
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
        Optional<Enum<?>> requested = presentTransitions.get(transition.ordinal());
        Optional<Object> operationInput = Optional.ofNullable(input);
        CallLog log = CallLog.open(Optional.empty());
        Optional<Observed> observed = observe(entity, requested, false, log);
        return runRoot(entity, requested, false, observed, log,
                (Transaction tx) -> tx.transition(this, entity, transition, 0, operationInput), 0);
    }

    void requireTransition(T transition) {
        if (!key().transitions().isInstance(transition)) {
            throw new IllegalArgumentException(
                    "transition must belong to " + key().transitions().getName() + ": " + transition);
        }
    }

    @Override
    public Outcome<E, S, T> reevaluate(E entity) {
        Objects.requireNonNull(entity, "entity");
        return reevaluate(entity, 0, Optional.empty());
    }

    /**
     * chain counts reaction -> transition -> reaction, so a chain above 0 is a watch reaction. Exceeding
     * maxWatchChain throws WatchChainExceededException after the reentrancy check and the state read, before any
     * watch function runs. The state read and the watch functions, peersOf and the conditions, run with the
     * reentrancy marker set, so none of them can start a transition on this thread: a nested root would begin a chain
     * of its own that maxWatchChain never counts. The marker is released before the selected edge runs as a root.
     * Observers see the whole of it as one call, which ends in RESOLVE if it throws before an edge runs, and so does
     * its log. The log's start line names {@code trigger}: the id of the call whose commit triggered this watch
     * reaction, when that call logged at DEBUG, and empty for a reevaluation the application made.
     */
    Outcome<E, S, T> reevaluate(E entity, int chain, Optional<Long> trigger) {
        Reentrancy.check(key(), entity);
        boolean reaction = chain > 0;
        CallLog log = CallLog.open(trigger);
        Optional<Observed> observed = observe(entity, Optional.empty(), reaction, log);
        S state;
        Optional<WatchSpec<E, S, T, ?, ?, ?>> watch;
        Optional<T> selected = Optional.empty();
        try {
            if (log.debug()) {
                log.start(key(), "reevaluate");
            }
            Reentrancy.enter(key(), entity);
            try {
                try {
                    state = readState(entity);
                } catch (Error fatal) {
                    throw fatal;
                } catch (Throwable e) {
                    throw unresolved(entity, observed, reaction, Optional.empty(), "reading the entity threw ", e);
                }
                if (chain > registry.maxWatchChain()) {
                    AbortDetails details = new AbortDetails(key(), entity, Optional.of(state), Optional.empty(),
                            Optional.empty(), Optional.empty(), List.of(), List.of());
                    throw new WatchChainExceededException(callOf(entity, observed, reaction), details,
                            registry.maxWatchChain());
                }
                watch = definition.watch(state);
                if (watch.isPresent()) {
                    try {
                        selected = firstMatch(watch.get(), entity);
                    } catch (Error fatal) {
                        throw fatal;
                    } catch (Throwable e) {
                        throw unresolved(entity, observed, reaction, Optional.of(state), "the watch threw ", e);
                    }
                }
            } finally {
                Reentrancy.exit();
            }
        } catch (Throwable thrown) {
            failed(observed, CallPhase.RESOLVE, StatesWritten.NONE, thrown);
            if (log.debug() && !(thrown instanceof Error)) {
                log.failed(new CallFailure(CallPhase.RESOLVE, StatesWritten.NONE, thrown));
            }
            throw thrown;
        }
        if (watch.isEmpty()) {
            return completed(observed, log, new Outcome.Ignored<>(entity, state, IgnoreReason.NO_WATCH));
        }
        if (selected.isEmpty()) {
            return completed(observed, log, new Outcome.Ignored<>(entity, state, IgnoreReason.NO_RULE_MATCHED));
        }
        T resolved = selected.orElseThrow();
        return runRoot(entity, Optional.empty(), reaction, observed, log,
                (Transaction tx) -> tx.transition(this, entity, resolved, 0), chain);
    }

    /**
     * A reevaluation that could not read its entity's state or evaluate its watch: an abort in RESOLVE that carries
     * the failure, unwrapped from CompletionException and ExecutionException.
     */
    private TransitionAbortedException unresolved(E entity, Optional<Observed> observed, boolean reaction,
                                                  Optional<Enum<?>> from, String what, Throwable error) {
        Throwable cause = Unwrap.unwrap(error);
        AbortDetails details = new AbortDetails(key(), entity, from, Optional.empty(), Optional.empty(),
                Optional.empty(), List.of(), List.of());
        return new TransitionAbortedException(callOf(entity, observed, reaction), CallPhase.RESOLVE, details,
                what + cause, cause);
    }

    /** Rules in order; the first one wins. The view is fresh: outside any transaction it reads the getters. */
    private <P, Q extends Enum<Q>, U extends Enum<U>> Optional<T> firstMatch(WatchSpec<E, S, T, P, Q, U> watch,
                                                                             E entity) {
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
     * Root transaction: new UoW with its deadline, reentrancy marker, step, phase 2, context invalidation, then the
     * hooks and the watch reactions of what it committed. Everything is prepared before the marker is set, so that
     * from {@code Reentrancy.enter} on, the only way out runs through the {@code finally} that releases it, whatever
     * the step throws. The transaction keeps the phase of the call, which the observers are told when it throws.
     * Watch reactions are dispatched after the marker is released, so a reaction may start its own transaction; the
     * dispatch sets the marker again only while it runs an owner lookup, and a reevaluation while it runs its watch.
     * {@code requested} is the identifier execute was asked for, and empty for a reevaluation.
     * The observers hear of the call before its log does: the start line follows onStarted, inside the try that tells
     * them of an Error, and the end or failed line follows their end event. An execute logs its start line here; a
     * reevaluation logged its own before it read the state. The committed hops and starts are logged once phase 2
     * wrote them, before the hooks, and an Error gets no failed line.
     */
    Outcome<E, S, T> runRoot(E entity, Optional<Enum<?>> requested, boolean reaction, Optional<Observed> observed,
                             CallLog log, Function<Transaction, StepResult<S, T>> body, int chain) {
        UnitOfWork uow = new UnitOfWork(registry.maxCascadeDepth(), deadlineNanos());
        Transaction tx = new Transaction(registry, uow, key(), entity, requested, reaction,
                observed.map(Observed::call), log);
        Outcome<E, S, T> outcome;
        try {
            if (log.debug() && requested.isPresent()) {
                log.start(key(), requested.get().name());
            }
            StepResult<S, T> step;
            List<TransitionRecord<?, ?, ?>> records;
            List<SubmachineStart<?, ?, ?>> starts;
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
                    starts = uow.starts();
                    committed = uow.entries();
                    if (log.debug()) {
                        log.committed(committed);
                    }
                } else {
                    summary = CommitSummary.EMPTY;
                    records = List.of();
                    starts = List.of();
                    committed = List.of();
                }
            } finally {
                tx.invalidate();
                Reentrancy.exit();
            }
            List<Reaction> reactions = List.of();
            if (!committed.isEmpty()) {
                tx.enter(CallPhase.HOOK);
                runHooks(tx, records, committed);
                tx.enter(CallPhase.REACTIONS);
                reactions = registry.dispatchWatches(summary, chain,
                        log.debug() ? Optional.of(log.id()) : Optional.empty());
            }
            outcome = switch (step) {
                case StepResult.Applied<S, T> applied ->
                        new Outcome.Applied<>(entity, applied.from(), applied.transition(), applied.to(),
                                records, reactions, starts);
                case StepResult.Ignored<S, T> ignored ->
                        new Outcome.Ignored<>(entity, ignored.from(), ignored.reason());
                case StepResult.Rejected<S, T> rejected ->
                        new Outcome.Rejected<>(entity, rejected.from(), rejected.transition(), rejected.reason(),
                                rejected.kind(), rejected.guard());
                case StepResult.Failed<S, T> failed ->
                        new Outcome.Failed<>(entity, failed.from(), failed.transition(), failed.to(),
                                failed.failureTransition(), failed.movedTo(), failed.reason(), failed.cause(),
                                records, reactions, failed.failureKind(), starts);
            };
        } catch (Throwable thrown) {
            if (thrown instanceof Error fatal) {
                throw failed(observed, tx, fatal);
            }
            CallFailedException failure = failed(observed, tx, tx.classify(thrown));
            if (log.debug()) {
                log.failed(tx.failureOf(failure));
            }
            throw failure;
        }
        return completed(observed, log, outcome);
    }

    /**
     * Tells the registry's observers that a call starts; reads no clock and allocates nothing when it has none. The
     * end events measure from the clock read of the call's log, when the call logs, and from a read of their own
     * otherwise, so a call reads the clock once at its start either way.
     */
    private Optional<Observed> observe(E entity, Optional<Enum<?>> requested, boolean reaction, CallLog log) {
        Observers observers = registry.observers();
        if (observers.isEmpty()) {
            return Optional.empty();
        }
        long startNanos = log == CallLog.OFF ? System.nanoTime() : log.startNanos();
        return Optional.of(observers.started(new MachineCall(key(), entity, requested, reaction), startNanos));
    }

    /** The call a reevaluation's own failure carries: the observed one, or a new one when nobody observes it. */
    private MachineCall callOf(E entity, Optional<Observed> observed, boolean reaction) {
        return observed.isPresent()
                ? observed.get().call()
                : new MachineCall(key(), entity, Optional.empty(), reaction);
    }

    /** Tells the observers, if any, that the call returned {@code outcome}, then logs its end line. */
    private static <O extends Outcome<?, ?, ?>> O completed(Optional<Observed> observed, CallLog log, O outcome) {
        if (observed.isPresent()) {
            observed.get().completed(outcome);
        }
        if (log.debug()) {
            log.end(outcome);
        }
        return outcome;
    }

    /** Tells the observers, if any, that the call ended in {@code thrown}, as its transaction classifies it. */
    private static <X extends Throwable> X failed(Optional<Observed> observed, Transaction tx, X thrown) {
        if (observed.isPresent()) {
            observed.get().failed(tx.failureOf(thrown));
        }
        return thrown;
    }

    private static void failed(Optional<Observed> observed, CallPhase phase, StatesWritten written, Throwable thrown) {
        if (observed.isPresent()) {
            observed.get().failed(new CallFailure(phase, written, thrown));
        }
    }

    /**
     * Every committed hop with a hook, in log order, on the calling thread, using the edge that was selected; a
     * sub-machine's start runs no hook. Any throwable a hook throws that is not an Error, checked ones included,
     * becomes an AfterCommitException of the call, naming the hop and carrying every hop and every start the call
     * committed; an Error propagates unchanged.
     */
    private void runHooks(Transaction tx, List<TransitionRecord<?, ?, ?>> records, List<LogEntry> committed) {
        for (int index = 0; index < committed.size(); index++) {
            LogEntry entry = committed.get(index);
            if (entry instanceof LogEntry.Hop hop) {
                runHook(tx, records, registry.anyMachineFor(hop.ref().key()), hop);
            }
        }
    }

    private <P, Q extends Enum<Q>, U extends Enum<U>> void runHook(
            Transaction tx, List<TransitionRecord<?, ?, ?>> records, DefaultStateMachine<P, Q, U> owner,
            LogEntry.Hop entry) {
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
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            throw new AfterCommitException(tx.call(), tx.uow().journal(), records, tx.uow().starts(),
                    Typed.record(owner.key(), entry), hook.name(), e);
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
