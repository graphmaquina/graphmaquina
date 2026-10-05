package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionRef;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.GuardRef;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.error.AbortDetails;
import io.github.graphmaquina.error.CascadeCycleException;
import io.github.graphmaquina.error.CascadeDepthExceededException;
import io.github.graphmaquina.error.CascadeException;
import io.github.graphmaquina.error.PoisonedUnitOfWorkException;
import io.github.graphmaquina.error.StateMachineException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UnhandledFailureException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/** Engine of a root transaction. All the state of the transition lives here and in the UnitOfWork. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class Transaction {

    private final MachineRegistry registry;
    private final UnitOfWork uow;
    private final List<ContextHandle> contexts = new ArrayList<>();

    UnitOfWork uow() { return uow; }

    <P, Q extends Enum<Q>, U extends Enum<U>> MachineKey<P, Q, U> keyFor(Class<? extends Machine<P, Q, U>> facade) {
        return registry.keyFor(facade);
    }

    void invalidate() {
        for (ContextHandle context : contexts) {
            context.invalidate();
        }
    }

    /** For reevaluate, which has no per-call input to carry; a cascade passes {@code null} to the full overload. */
    <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> transition(DefaultStateMachine<E, S, T> machine, E entity,
                                                                          T id, int depth) {
        return transition(machine, entity, id, depth, null);
    }

    /** One step: resolve (current state, identifier) exactly once, guards, annotate, execute, failure route. */
    <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> transition(DefaultStateMachine<E, S, T> machine, E entity,
                                                                          T id, int depth, Object input) {
        machine.requireTransition(id);
        EntityRef ref = uow.register(machine.refOf(entity));
        S from = currentState(machine, ref, entity);
        Optional<TransitionSpec<E, S, T>> selected = machine.definition().findTransition(from, id);
        if (selected.isEmpty()) {
            return rejected(machine, ref, from, id, Optional.empty(), depth,
                    machine.definition().policy(from).reasonFor(id), RejectionKind.NO_TRANSITION, Optional.empty());
        }
        TransitionSpec<E, S, T> transition = selected.orElseThrow();
        uow.push(ref, transition);
        try {
            for (GuardRef<E, S, T> guard : transition.guards()) {
                DefaultGuardContext<E, S, T> guardContext =
                        new DefaultGuardContext<>(this, machine, entity, transition, depth, input);
                contexts.add(guardContext);
                GuardResult verdict = evaluateGuard(machine, ref, transition, depth, guard, guardContext);
                if (verdict instanceof GuardResult.Reject reject) {
                    return rejected(machine, ref, from, id, Optional.of(transition.to()), depth, reject.reason(),
                            RejectionKind.GUARD, Optional.of(guard.name()));
                }
            }
            int savepoint = uow.savepoint();
            uow.append(new LogEntry(ref, transition, depth));
            if (transition.action().isEmpty()) {
                return new StepResult.Applied<>(from, id, transition.to());
            }
            return runAction(machine, ref, entity, transition, depth, savepoint, input);
        } finally {
            uow.pop();
        }
    }

    private <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> runAction(
            DefaultStateMachine<E, S, T> machine, EntityRef ref, E entity, TransitionSpec<E, S, T> transition, int depth,
            int savepoint, Object input) {
        ActionRef<E, S, T> action = transition.action().orElseThrow();
        int journalIndex = uow.appendJournal(new ActionRecord(machine.key(), entity, transition.from(),
                transition.transition(), transition.to(), action.name(), depth, ActionStatus.RUNNING));
        DefaultTransitionContext<E, S, T> ctx = new DefaultTransitionContext<>(this, machine, entity, transition, depth, input);
        contexts.add(ctx);
        ActionOutcome outcome;
        try {
            outcome = executeAction(machine, ref, transition, action, ctx);
        } catch (ActionFailure failure) {
            uow.journalStatus(journalIndex, ActionStatus.THREW);
            Throwable cause = failure.getCause();
            if (matchesFailsOn(transition, cause)) {
                // A poisoned unit of work never commits, and the failure route commits: the poison is thrown before
                // any recovery action can run, as it is when the action returns.
                if (uow.isPoisoned()) {
                    throw uow.poison().orElseThrow();
                }
                return failureRoute(machine, ref, entity, transition, depth, savepoint, describe(cause),
                        Optional.of(cause), input);
            }
            throw abort(machine, ref, transition.from(), Optional.of(transition.transition()), Optional.of(transition.to()),
                    Optional.of(action.name()), depth, "the action " + action.name() + " threw " + cause, Optional.of(cause));
        } catch (StateMachineException e) {
            uow.journalStatus(journalIndex, ActionStatus.THREW);
            throw e;
        }
        if (uow.isPoisoned()) {
            uow.journalStatus(journalIndex, ActionStatus.THREW);
            throw uow.poison().orElseThrow();
        }
        if (outcome instanceof ActionOutcome.Failed failed) {
            uow.journalStatus(journalIndex, ActionStatus.FAILED);
            return failureRoute(machine, ref, entity, transition, depth, savepoint, failed.reason(), Optional.empty(), input);
        }
        uow.journalStatus(journalIndex, ActionStatus.OK);
        return new StepResult.Applied<>(transition.from(), transition.transition(), transition.to());
    }

    /**
     * Sync actions run inline; async ones return a stage that is awaited under the edge's deadline. A
     * StateMachineException the action throws, or that completes its stage, is rethrown as is. Any other Exception,
     * thrown or completing the stage, checked ones included, which Kotlin or {@code @SneakyThrows} code can throw
     * without declaring them, becomes an ActionFailure that failsOn classifies. An Error is never routed: thrown as
     * itself, it propagates; wrapped in CompletionException or ExecutionException, it is unwrapped and aborts.
     */
    private <E, S extends Enum<S>, T extends Enum<T>> ActionOutcome executeAction(
            DefaultStateMachine<E, S, T> machine, EntityRef ref, TransitionSpec<E, S, T> transition,
            ActionRef<E, S, T> action, DefaultTransitionContext<E, S, T> ctx) {
        ActionOutcome outcome = switch (action) {
            case ActionRef.Sync<E, S, T> sync -> {
                try {
                    yield sync.action().process(ctx);
                } catch (StateMachineException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ActionFailure(e);
                }
            }
            case ActionRef.Async<E, S, T> async -> {
                long edgeBudget = edgeBudgetNanos(transition);
                CompletionStage<ActionOutcome> stage;
                try {
                    stage = async.action().process(ctx);
                } catch (StateMachineException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ActionFailure(e);
                }
                if (stage == null) {
                    throw abort(machine, ref, ctx.from(), Optional.of(ctx.transition()), Optional.of(ctx.to()),
                            Optional.of(action.name()), ctx.depth(),
                            "the action " + action.name() + " returned a null stage", Optional.empty());
                }
                yield await(machine, ref, ctx.from(), Optional.of(ctx.transition()), Optional.of(ctx.to()),
                        Optional.of(action.name()), ctx.depth(), "the action " + action.name(), stage,
                        edgeDeadline(edgeBudget));
            }
        };
        if (outcome == null) {
            throw abort(machine, ref, ctx.from(), Optional.of(ctx.transition()), Optional.of(ctx.to()),
                    Optional.of(action.name()), ctx.depth(), "the action " + action.name() + " returned null",
                    Optional.empty());
        }
        return outcome;
    }

    /**
     * Sync guards run inline; async ones return a stage that is awaited under the transaction's deadline. A
     * StateMachineException the guard throws, or that completes its stage, is rethrown as is. Any other exceptional
     * completion of the stage, an Error included, and any other Exception the guard throws, checked ones included,
     * are technical aborts; an Error thrown as itself propagates.
     */
    private <E, S extends Enum<S>, T extends Enum<T>> GuardResult evaluateGuard(
            DefaultStateMachine<E, S, T> machine, EntityRef ref, TransitionSpec<E, S, T> transition, int depth,
            GuardRef<E, S, T> guard, DefaultGuardContext<E, S, T> ctx) {
        Optional<Enum<?>> id = Optional.of(transition.transition());
        Optional<Enum<?>> to = Optional.of(transition.to());
        GuardResult verdict = switch (guard) {
            case GuardRef.Sync<E, S, T> sync -> {
                try {
                    yield sync.guard().test(ctx);
                } catch (StateMachineException e) {
                    throw e;
                } catch (Exception e) {
                    Throwable cause = Unwrap.unwrap(e);
                    throw abort(machine, ref, transition.from(), id, to, Optional.empty(), depth,
                            "the guard " + guard.name() + " threw " + cause, Optional.of(cause));
                }
            }
            case GuardRef.Async<E, S, T> async -> {
                CompletionStage<GuardResult> stage;
                try {
                    stage = async.guard().test(ctx);
                } catch (StateMachineException e) {
                    throw e;
                } catch (Exception e) {
                    Throwable cause = Unwrap.unwrap(e);
                    throw abort(machine, ref, transition.from(), id, to, Optional.empty(), depth,
                            "the guard " + guard.name() + " threw " + cause, Optional.of(cause));
                }
                if (stage == null) {
                    throw abort(machine, ref, transition.from(), id, to, Optional.empty(), depth,
                            "the guard " + guard.name() + " returned a null stage", Optional.empty());
                }
                try {
                    yield await(machine, ref, transition.from(), id, to, Optional.empty(), depth,
                            "the guard " + guard.name(), stage, uow.deadlineNanos());
                } catch (ActionFailure failure) {
                    throw abort(machine, ref, transition.from(), id, to, Optional.empty(), depth,
                            "the guard " + guard.name() + " failed: " + failure.getCause(), Optional.of(failure.getCause()));
                }
            }
        };
        if (verdict == null) {
            throw abort(machine, ref, transition.from(), id, to, Optional.empty(), depth,
                    "the guard " + guard.name() + " returned null", Optional.empty());
        }
        return verdict;
    }

    /** Waits under the deadline. Timeout and interruption are aborts; the user's stage is left running. */
    private <R> R await(DefaultStateMachine<?, ?, ?> machine, EntityRef ref, Enum<?> from, Optional<Enum<?>> id,
                        Optional<Enum<?>> to, Optional<String> action, int depth, String what, CompletionStage<R> stage,
                        long deadlineNanos) {
        try {
            return Await.await(stage, deadlineNanos);
        } catch (Await.Timeout timeout) {
            throw abort(machine, ref, from, id, to, action, depth, "waiting for " + what + " timed out",
                    Optional.of(timeout.getCause()));
        } catch (Await.Interrupted interrupted) {
            throw abort(machine, ref, from, id, to, action, depth, "interrupted while waiting for " + what,
                    Optional.of(interrupted.getCause()));
        }
    }

    /**
     * The edge's own budget in nanoseconds, Long.MAX_VALUE when it sets no limit. It is converted before the action
     * runs, although its clock only starts once the action returns its stage; the conversion saturates and never throws.
     */
    private static long edgeBudgetNanos(TransitionSpec<?, ?, ?> transition) {
        return transition.timeout().isEmpty() ? Long.MAX_VALUE : Await.budgetNanos(transition.timeout().get());
    }

    /**
     * The edge's timeout can only shorten the transaction's deadline, never extend it: the wait ends at the earlier
     * of the two. The edge's clock starts now, after the action returned its stage. The budgets left are compared
     * rather than the deadlines, because a deadline may have wrapped past Long.MAX_VALUE, and Long.MAX_VALUE on
     * either side means that side sets no limit.
     */
    private long edgeDeadline(long edgeBudget) {
        long base = uow.deadlineNanos();
        if (edgeBudget == Long.MAX_VALUE) {
            return base;
        }
        long now = System.nanoTime();
        if (base != Long.MAX_VALUE && base - now <= edgeBudget) {
            return base;
        }
        return now + edgeBudget;
    }

    /**
     * Roll back to the savepoint, then resolve the recovery edge from the original origin by its identifier and
     * run it without guards and without a failure route of its own.
     */
    private <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> failureRoute(
            DefaultStateMachine<E, S, T> machine, EntityRef ref, E entity, TransitionSpec<E, S, T> failing, int depth,
            int savepoint, String reason, Optional<Throwable> cause, Object input) {
        uow.rollbackTo(savepoint);
        S from = failing.from();
        Optional<String> actionName = failing.action().map(ActionRef::name);
        if (failing.onFailure().isEmpty()) {
            AbortDetails details = details(machine, ref, from, Optional.of(failing.transition()),
                    Optional.of(failing.to()), actionName);
            UnhandledFailureException unhandled = cause.isPresent()
                    ? new UnhandledFailureException(details, reason, cause.get())
                    : new UnhandledFailureException(details, reason);
            throw poisonIfNested(depth, unhandled);
        }
        T recoveryId = failing.onFailure().orElseThrow();
        TransitionSpec<E, S, T> route = machine.definition().findTransition(from, recoveryId).orElseThrow();
        // The failed action has ended. Keep one frame for the entity, now identifying the recovery edge.
        uow.pop();
        uow.push(ref, route);
        uow.append(new LogEntry(ref, route, depth));
        if (route.action().isPresent()) {
            ActionRef<E, S, T> action = route.action().orElseThrow();
            int journalIndex = uow.appendJournal(new ActionRecord(machine.key(), entity, from, recoveryId, route.to(),
                    action.name(), depth, ActionStatus.RUNNING));
            DefaultTransitionContext<E, S, T> ctx = new DefaultTransitionContext<>(this, machine, entity, route, depth, input);
            contexts.add(ctx);
            ActionOutcome outcome;
            try {
                outcome = executeAction(machine, ref, route, action, ctx);
            } catch (ActionFailure failure) {
                uow.journalStatus(journalIndex, ActionStatus.THREW);
                throw abort(machine, ref, from, Optional.of(recoveryId), Optional.of(route.to()),
                        Optional.of(action.name()), depth,
                        "the failure route action " + action.name() + " threw after the original failure (" + reason + ")",
                        Optional.of(failure.getCause()));
            } catch (StateMachineException e) {
                uow.journalStatus(journalIndex, ActionStatus.THREW);
                throw e;
            }
            if (uow.isPoisoned()) {
                uow.journalStatus(journalIndex, ActionStatus.THREW);
                throw uow.poison().orElseThrow();
            }
            if (outcome instanceof ActionOutcome.Failed nested) {
                uow.journalStatus(journalIndex, ActionStatus.FAILED);
                throw abort(machine, ref, from, Optional.of(recoveryId), Optional.of(route.to()),
                        Optional.of(action.name()), depth,
                        "the failure route action " + action.name() + " returned failed (" + nested.reason()
                                + ") after the original failure (" + reason + ")", cause);
            }
            uow.journalStatus(journalIndex, ActionStatus.OK);
        }
        return new StepResult.Failed<>(from, failing.transition(), failing.to(), recoveryId, route.to(), reason, cause);
    }

    private <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> rejected(
            DefaultStateMachine<E, S, T> machine, EntityRef ref, S from, T id, Optional<S> to, int depth, String reason,
            RejectionKind kind, Optional<String> guard) {
        if (depth == 0) {
            return new StepResult.Rejected<>(from, id, reason, kind, guard);
        }
        String who = guard.map((String name) -> "guard " + name).orElse("no such transition");
        throw abort(machine, ref, from, Optional.of(id), to.map((S state) -> (Enum<?>) state), Optional.empty(), depth,
                "rejected inside a cascade by " + who + ": " + reason, Optional.empty());
    }

    private <E, S extends Enum<S>, T extends Enum<T>> S currentState(DefaultStateMachine<E, S, T> machine, EntityRef ref,
                                                                      E entity) {
        Optional<Enum<?>> pending = uow.pendingState(ref);
        if (pending.isPresent()) {
            return machine.key().states().cast(pending.get());
        }
        return machine.readState(entity);
    }

    private static <E, S extends Enum<S>, T extends Enum<T>> boolean matchesFailsOn(TransitionSpec<E, S, T> transition,
                                                                                     Throwable cause) {
        if (cause instanceof Error || cause instanceof CancellationException) {
            return false;
        }
        for (Class<? extends Throwable> type : transition.failsOn()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The reason a failsOn match reports: the exception's simple name and its message. An anonymous class has an empty
     * simple name, so its binary name stands in: a reason is never blank.
     */
    private static String describe(Throwable cause) {
        String message = cause.getMessage();
        String type = cause.getClass().getSimpleName();
        return (type.isEmpty() ? cause.getClass().getName() : type) + (message == null ? "" : ": " + message);
    }

    private TransitionAbortedException abort(DefaultStateMachine<?, ?, ?> machine, EntityRef ref, Enum<?> from,
                                             Optional<Enum<?>> id, Optional<Enum<?>> to, Optional<String> action, int depth,
                                             String message, Optional<Throwable> cause) {
        AbortDetails details = details(machine, ref, from, id, to, action);
        TransitionAbortedException error = cause.isPresent()
                ? new TransitionAbortedException(details, message, cause.get())
                : new TransitionAbortedException(details, message);
        return poisonIfNested(depth, error);
    }

    /** At depth greater than zero, the failure poisons the UoW: the parent cannot go on as if nothing happened. */
    private <X extends StateMachineException> X poisonIfNested(int depth, X error) {
        if (depth > 0) {
            uow.poison(error);
        }
        return error;
    }

    private AbortDetails details(DefaultStateMachine<?, ?, ?> machine, EntityRef ref, Enum<?> from, Optional<Enum<?>> id,
                                 Optional<Enum<?>> to, Optional<String> action) {
        uow.markRunningAsThrew();
        return new AbortDetails(machine.key(), ref.instance(), from, id, to, action, uow.path(), uow.journal());
    }

    private AbortDetails details(ContextHandle caller) {
        uow.markRunningAsThrew();
        return new AbortDetails(caller.machineKey(), caller.entityInstance(), caller.fromState(),
                Optional.of(caller.requestedTransition()), Optional.of(caller.toState()), Optional.empty(),
                uow.path(), uow.journal());
    }

    // Peers and cascading.

    /**
     * {@code guard} is the caller context's own check: the view stays as alive as the context that made it. A read
     * stages nothing, so it never poisons: neither does an unknown key here, nor an unknown facade in keyFor.
     */
    <P, Q extends Enum<Q>, U extends Enum<U>> PeerView<P, Q, U> peer(ContextHandle caller, Runnable guard,
                                                                     MachineKey<P, Q, U> key) {
        checkPoison(caller);
        return new DefaultPeerView<>(guard, uow, registry.machineFor(key));
    }

    /**
     * Resolves the facade a cascade names inside the poisoning scope: an unknown facade poisons, as an unknown key
     * does once the cascade looks its machine up.
     */
    <P, Q extends Enum<Q>, U extends Enum<U>> MachineKey<P, Q, U> cascadeKeyFor(ContextHandle caller,
                                                                              Class<? extends Machine<P, Q, U>> facade) {
        checkPoison(caller);
        try {
            return keyFor(facade);
        } catch (StateMachineException e) {
            uow.poison(e);
            throw e;
        }
    }

    /**
     * Cascade to a single peer. An identifier from another catalogue is refused before anything starts, as the context
     * refuses a null argument before calling this: such a refusal stages nothing and poisons nothing. Once the cascade
     * starts, any failure poisons the UnitOfWork: the parent cannot carry on as if nothing happened. The failure itself
     * reaches the parent unchanged, an Error included.
     */
    <P, Q extends Enum<Q>, U extends Enum<U>> Outcome<P, Q, U> cascade(ContextHandle caller, MachineKey<P, Q, U> key,
                                                                       P peer, U id) {
        checkPoison(caller);
        requireTransition(key, id);
        try {
            return cascadeOne(caller, key, peer, id);
        } catch (Throwable error) {
            poison(caller, key, error);
            throw error;
        }
    }

    /** Refuses an identifier from another machine's catalogue, which only a caller that bypasses generics can pass. */
    private static void requireTransition(MachineKey<?, ?, ?> key, Enum<?> id) {
        if (!key.transitions().isInstance(id)) {
            throw new IllegalArgumentException("transition must belong to " + key.transitions().getName() + ": " + id);
        }
    }

    /**
     * An engine failure is the poison itself. Anything else, an Error or an exception from a callback the engine does
     * not wrap such as an identity function, poisons through an abort that names the cascade and carries it as cause.
     */
    private void poison(ContextHandle caller, MachineKey<?, ?, ?> key, Throwable error) {
        if (error instanceof StateMachineException engine) {
            uow.poison(engine);
        } else {
            uow.poison(new TransitionAbortedException(details(caller), "the cascade to " + key + " failed: " + error, error));
        }
    }

    /**
     * Order of checks: identity, re-entry, depth, and only then the step. There is no convergent no-op: a request
     * that resolves no edge is rejected, and a request on an entity already in flight is a cycle.
     */
    private <P, Q extends Enum<Q>, U extends Enum<U>> Outcome<P, Q, U> cascadeOne(ContextHandle caller,
                                                                                  MachineKey<P, Q, U> key, P peer, U id) {
        DefaultStateMachine<P, Q, U> machine = registry.machineFor(key);
        machine.requireTransition(id);
        EntityRef ref = uow.register(machine.refOf(peer));
        int depth = caller.contextDepth() + 1;
        Optional<InFlight> flight = uow.inFlight(ref);
        if (flight.isPresent()) {
            throw cascadeCycle(caller, flight.orElseThrow(), id);
        }
        if (depth > uow.maxDepth()) {
            throw new CascadeDepthExceededException(details(caller), uow.maxDepth());
        }
        return outcomeOf(peer, transition(machine, peer, id, depth, null));
    }

    /** The cascade path plus the entity that was re-entered, with the identifier in flight and the one requested. */
    private CascadeCycleException cascadeCycle(ContextHandle caller, InFlight flight, Enum<?> requested) {
        List<String> route = new ArrayList<>(uow.path());
        route.add(flight.ref().toString());
        return new CascadeCycleException(details(caller), route, flight.transition(), requested);
    }

    /** Adapts a nested step to an outcome: records and reactions belong to the root, so they stay empty here. */
    private <P, Q extends Enum<Q>, U extends Enum<U>> Outcome<P, Q, U> outcomeOf(P peer, StepResult<Q, U> step) {
        return switch (step) {
            case StepResult.Applied<Q, U> applied ->
                    new Outcome.Applied<>(peer, applied.from(), applied.transition(), applied.to(), List.of(), List.of());
            // Unreachable: a nested step executes an edge and never reevaluates. Kept only for switch exhaustiveness.
            case StepResult.Ignored<Q, U> ignored -> new Outcome.Ignored<>(peer, ignored.from(), ignored.reason());
            // Unreachable: at depth > 0, rejected(...) always aborts. Kept only for switch exhaustiveness.
            case StepResult.Rejected<Q, U> rejected -> new Outcome.Rejected<>(peer, rejected.from(), rejected.transition(),
                    rejected.reason(), rejected.kind(), rejected.guard());
            case StepResult.Failed<Q, U> failed -> new Outcome.Failed<>(peer, failed.from(), failed.transition(),
                    failed.to(), failed.failureTransition(), failed.movedTo(), failed.reason(), failed.cause(),
                    List.of(), List.of());
        };
    }

    /**
     * FAIL_FAST aborts at the first failure. COLLECT tries every element under its own savepoint, rolls back
     * the failed one, and at the end poisons with the aggregate. It is the only place that clears the poison.
     * COLLECT gathers transition aborts only: any other failure poisons and propagates at once. The arguments are
     * refused before any element runs, as cascade refuses its own: an identifier from another catalogue or a null
     * element stages nothing and poisons nothing.
     */
    <P, Q extends Enum<Q>, U extends Enum<U>> List<Outcome<P, Q, U>> cascadeAll(ContextHandle caller,
                                                                                MachineKey<P, Q, U> key,
                                                                                Collection<? extends P> peers, U id,
                                                                                CascadePolicy policy) {
        checkPoison(caller);
        requireTransition(key, id);
        for (P peer : peers) {
            Objects.requireNonNull(peer, "peers contains a null element");
        }
        List<Outcome<P, Q, U>> outcomes = new ArrayList<>();
        List<TransitionAbortedException> errors = new ArrayList<>();
        for (P peer : peers) {
            if (policy == CascadePolicy.FAIL_FAST) {
                outcomes.add(cascade(caller, key, peer, id));
                continue;
            }
            int savepoint = uow.savepoint();
            try {
                outcomes.add(cascadeOne(caller, key, peer, id));
            } catch (TransitionAbortedException e) {
                uow.rollbackTo(savepoint);
                uow.clearPoison();
                errors.add(e);
            } catch (Throwable error) {
                poison(caller, key, error);
                throw error;
            }
        }
        if (!errors.isEmpty()) {
            CascadeException aggregate = new CascadeException(details(caller), errors);
            uow.poison(aggregate);
            throw aggregate;
        }
        return List.copyOf(outcomes);
    }

    private void checkPoison(ContextHandle caller) {
        if (uow.isPoisoned()) {
            throw new PoisonedUnitOfWorkException(details(caller), uow.poison().orElseThrow());
        }
    }
}
