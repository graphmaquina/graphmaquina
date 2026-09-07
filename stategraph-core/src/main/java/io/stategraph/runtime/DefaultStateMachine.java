package io.stategraph.runtime;

import io.stategraph.api.AsyncStateMachine;
import io.stategraph.api.GraphDefinition;
import io.stategraph.api.MachineKey;
import io.stategraph.api.Outcome;
import io.stategraph.api.Reaction;
import io.stategraph.api.StateMachine;
import io.stategraph.api.TransitionRecord;
import io.stategraph.error.UninitializedStateException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Runtime {@link StateMachine}: resolves cells, runs a root transaction and commits in two phases. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
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

    @Override
    public MachineKey<E, S> key() { return definition.key(); }

    @Override
    public Set<S> allowedFrom(S state) {
        Objects.requireNonNull(state, "state");
        return definition.allowedFrom(state);
    }

    @Override
    public Outcome<E, S> transitionTo(E entity, S target) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(target, "target");
        return runRoot(entity, (Transaction tx) -> tx.transition(this, entity, target, 0), 0);
    }

    @Override
    public Outcome<E, S> reevaluate(E entity) {
        Objects.requireNonNull(entity, "entity");
        throw new UnsupportedOperationException("reevaluate: implemented in task 10");
    }

    @Override
    public AsyncStateMachine<E, S> async() {
        throw new UnsupportedOperationException("async(): implemented in task 11");
    }

    /**
     * Root transaction: reentrancy marker, new UoW, step, phase 2, context invalidation.
     * Watch reactions are delivered outside the marker (task 10).
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
        List<Reaction> reactions = List.of(); // task 10: registry.dispatchWatches(uow, chain) if step is Moved or FailedTo
        return switch (step) {
            case StepResult.Moved<S> moved -> new Outcome.Applied<>(entity, moved.from(), moved.to(), records, reactions);
            case StepResult.Ignored<S> ignored -> new Outcome.Ignored<>(entity, ignored.from(), ignored.to());
            case StepResult.Rejected<S> rejected ->
                    new Outcome.Rejected<>(entity, rejected.from(), rejected.to(), rejected.reason(), rejected.guard());
            case StepResult.FailedTo<S> failed -> new Outcome.Failed<>(entity, failed.from(), failed.to(), failed.movedTo(),
                    failed.reason(), failed.cause(), records, reactions);
        };
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
