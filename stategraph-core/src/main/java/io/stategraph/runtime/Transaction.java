package io.stategraph.runtime;

import io.stategraph.api.ActionOutcome;
import io.stategraph.api.ActionRecord;
import io.stategraph.api.ActionRef;
import io.stategraph.api.ActionStatus;
import io.stategraph.api.CascadePolicy;
import io.stategraph.api.EdgeKind;
import io.stategraph.api.EdgeSpec;
import io.stategraph.api.GuardRef;
import io.stategraph.api.GuardResult;
import io.stategraph.api.MachineKey;
import io.stategraph.api.Outcome;
import io.stategraph.api.PeerView;
import io.stategraph.error.AbortDetails;
import io.stategraph.error.CascadeCycleException;
import io.stategraph.error.CascadeDepthExceededException;
import io.stategraph.error.CascadeException;
import io.stategraph.error.PoisonedUnitOfWorkException;
import io.stategraph.error.StateMachineException;
import io.stategraph.error.TransitionAbortedException;
import io.stategraph.error.UnhandledFailureException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/** Engine of a root transaction. All the state of the transition lives here and in the UnitOfWork. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class Transaction {

    private final MachineRegistry registry;
    private final UnitOfWork uow;
    private final List<ContextHandle> contexts = new ArrayList<>();

    UnitOfWork uow() { return uow; }

    void invalidate() {
        for (ContextHandle context : contexts) {
            context.invalidate();
        }
    }

    /** One step: resolve the cell, guards, annotate, execute, failure route. depth 0 is the root. */
    <E, S extends Enum<S>> StepResult<S> transition(DefaultStateMachine<E, S> machine, E entity, S target, int depth) {
        EntityRef ref = uow.register(machine.refOf(entity));
        S from = currentState(machine, ref, entity);
        EdgeSpec<E, S> edge = machine.definition().edge(from, target);
        if (edge.kind() == EdgeKind.FORBID) {
            return rejected(machine, ref, from, target, depth, edge.reason(), Optional.empty());
        }
        uow.push(ref, target);
        try {
            for (GuardRef<E, S> guard : edge.guards()) {
                DefaultGuardContext<E, S> guardContext = new DefaultGuardContext<>(this, machine, entity, from, target, depth);
                contexts.add(guardContext);
                GuardResult verdict = evaluateGuard(machine, ref, from, target, depth, guard, guardContext);
                if (verdict instanceof GuardResult.Reject reject) {
                    return rejected(machine, ref, from, target, depth, reject.reason(), Optional.of(guard.name()));
                }
            }
            if (edge.kind() == EdgeKind.STAY) {
                uow.append(new LogEntry(ref, from, target, EdgeKind.STAY, Optional.empty(), depth, false));
                return new StepResult.Ignored<>(from, target);
            }
            int savepoint = uow.savepoint();
            uow.append(new LogEntry(ref, from, target, edge.kind(), edge.action().map(ActionRef::name), depth, false));
            if (edge.kind() == EdgeKind.MOVE) {
                return new StepResult.Moved<>(from, target);
            }
            return runAction(machine, ref, entity, edge, from, target, depth, savepoint);
        } finally {
            uow.pop();
        }
    }

    private <E, S extends Enum<S>> StepResult<S> runAction(DefaultStateMachine<E, S> machine, EntityRef ref, E entity,
                                                            EdgeSpec<E, S> edge, S from, S target, int depth, int savepoint) {
        ActionRef<E, S> action = edge.action().orElseThrow();
        int journalIndex = uow.appendJournal(
                new ActionRecord(machine.key(), entity, from, target, action.name(), depth, ActionStatus.RUNNING));
        DefaultTransitionContext<E, S> ctx = new DefaultTransitionContext<>(this, machine, entity, from, target, depth);
        contexts.add(ctx);
        ActionOutcome outcome;
        try {
            outcome = executeAction(machine, ref, edge, action, ctx);
        } catch (ActionFailure failure) {
            uow.journalStatus(journalIndex, ActionStatus.THREW);
            Throwable cause = failure.getCause();
            if (matchesFailsOn(edge, cause)) {
                return failureRoute(machine, ref, entity, edge, from, target, depth, savepoint, describe(cause), Optional.of(cause));
            }
            throw abort(machine, ref, from, target, Optional.of(action.name()), depth,
                    "the action " + action.name() + " threw " + cause, Optional.of(cause));
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
            return failureRoute(machine, ref, entity, edge, from, target, depth, savepoint, failed.reason(), Optional.empty());
        }
        uow.journalStatus(journalIndex, ActionStatus.OK);
        return new StepResult.Moved<>(from, target);
    }

    /** Task 11 adds the Async arm: awaits the stage under the edge's deadline. */
    private <E, S extends Enum<S>> ActionOutcome executeAction(DefaultStateMachine<E, S> machine, EntityRef ref, EdgeSpec<E, S> edge,
                                                               ActionRef<E, S> action, DefaultTransitionContext<E, S> ctx) {
        ActionOutcome outcome = switch (action) {
            case ActionRef.Sync<E, S> sync -> {
                try {
                    yield sync.action().process(ctx);
                } catch (StateMachineException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw new ActionFailure(e);
                }
            }
            case ActionRef.Async<E, S> async -> throw new UnsupportedOperationException("asynchronous actions: implemented in task 11");
        };
        if (outcome == null) {
            throw abort(machine, ref, ctx.from(), ctx.to(), Optional.of(action.name()), ctx.depth(),
                    "the action " + action.name() + " returned null", Optional.empty());
        }
        return outcome;
    }

    /** Task 11 adds the Async arm. */
    private <E, S extends Enum<S>> GuardResult evaluateGuard(DefaultStateMachine<E, S> machine, EntityRef ref, S from, S target,
                                                             int depth, GuardRef<E, S> guard, DefaultGuardContext<E, S> ctx) {
        GuardResult verdict = switch (guard) {
            case GuardRef.Sync<E, S> sync -> {
                try {
                    yield sync.guard().test(ctx);
                } catch (StateMachineException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw abort(machine, ref, from, target, Optional.empty(), depth,
                            "the guard " + guard.name() + " threw " + e, Optional.of(e));
                }
            }
            case GuardRef.Async<E, S> async -> throw new UnsupportedOperationException("asynchronous guards: implemented in task 11");
        };
        if (verdict == null) {
            throw abort(machine, ref, from, target, Optional.empty(), depth,
                    "the guard " + guard.name() + " returned null", Optional.empty());
        }
        return verdict;
    }

    /**
     * Spec §6.6: roll back to the savepoint, annotate from → onFailure, run its action without guards and without its own failure route.
     */
    private <E, S extends Enum<S>> StepResult<S> failureRoute(DefaultStateMachine<E, S> machine, EntityRef ref, E entity,
                                                               EdgeSpec<E, S> edge, S from, S target, int depth, int savepoint,
                                                               String reason, Optional<Throwable> cause) {
        uow.rollbackTo(savepoint);
        Optional<String> actionName = edge.action().map(ActionRef::name);
        if (edge.onFailure().isEmpty()) {
            AbortDetails details = details(machine, ref, from, target, actionName);
            UnhandledFailureException unhandled = cause.isPresent()
                    ? new UnhandledFailureException(details, reason, cause.get())
                    : new UnhandledFailureException(details, reason);
            throw poisonIfNested(depth, unhandled);
        }
        S failState = edge.onFailure().get();
        EdgeSpec<E, S> route = machine.definition().edge(from, failState);
        Optional<String> routeAction = route.action().map(ActionRef::name);
        uow.append(new LogEntry(ref, from, failState, route.kind(), routeAction, depth, false));
        if (route.kind() == EdgeKind.ACTION) {
            ActionRef<E, S> action = route.action().orElseThrow();
            int journalIndex = uow.appendJournal(
                    new ActionRecord(machine.key(), entity, from, failState, action.name(), depth, ActionStatus.RUNNING));
            DefaultTransitionContext<E, S> ctx = new DefaultTransitionContext<>(this, machine, entity, from, failState, depth);
            contexts.add(ctx);
            ActionOutcome outcome;
            try {
                outcome = executeAction(machine, ref, route, action, ctx);
            } catch (ActionFailure failure) {
                uow.journalStatus(journalIndex, ActionStatus.THREW);
                throw abort(machine, ref, from, failState, Optional.of(action.name()), depth,
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
                throw abort(machine, ref, from, failState, Optional.of(action.name()), depth,
                        "the failure route action " + action.name() + " returned failed (" + nested.reason()
                                + ") after the original failure (" + reason + ")", cause);
            }
            uow.journalStatus(journalIndex, ActionStatus.OK);
        }
        return new StepResult.FailedTo<>(from, target, failState, reason, cause);
    }

    private <E, S extends Enum<S>> StepResult<S> rejected(DefaultStateMachine<E, S> machine, EntityRef ref, S from, S target,
                                                           int depth, String reason, Optional<String> guard) {
        if (depth == 0) {
            return new StepResult.Rejected<>(from, target, reason, guard);
        }
        String who = guard.map((String name) -> "guard " + name).orElse("the FORBID cell");
        throw abort(machine, ref, from, target, Optional.empty(), depth,
                "rejected inside a cascade by " + who + ": " + reason, Optional.empty());
    }

    private <E, S extends Enum<S>> S currentState(DefaultStateMachine<E, S> machine, EntityRef ref, E entity) {
        Optional<Enum<?>> pending = uow.pendingState(ref);
        if (pending.isPresent()) {
            return machine.key().states().cast(pending.get());
        }
        return machine.readState(entity);
    }

    private static <E, S extends Enum<S>> boolean matchesFailsOn(EdgeSpec<E, S> edge, Throwable cause) {
        if (cause instanceof Error || cause instanceof CancellationException) {
            return false;
        }
        for (Class<? extends Throwable> type : edge.failsOn()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Throwable cause) {
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private TransitionAbortedException abort(DefaultStateMachine<?, ?> machine, EntityRef ref, Enum<?> from, Enum<?> to,
                                             Optional<String> action, int depth, String message, Optional<Throwable> cause) {
        AbortDetails details = details(machine, ref, from, to, action);
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

    private AbortDetails details(DefaultStateMachine<?, ?> machine, EntityRef ref, Enum<?> from, Enum<?> to, Optional<String> action) {
        uow.markRunningAsThrew();
        return new AbortDetails(machine.key(), ref.instance(), from, to, action, uow.path(), uow.journal());
    }

    private AbortDetails details(ContextHandle caller) {
        uow.markRunningAsThrew();
        return new AbortDetails(caller.machineKey(), caller.entityInstance(), caller.fromState(), caller.toState(),
                Optional.empty(), uow.path(), uow.journal());
    }

    // Peers and cascading.

    <P, Q extends Enum<Q>> PeerView<P, Q> peer(ContextHandle caller, MachineKey<P, Q> key) {
        checkPoison(caller);
        return new DefaultPeerView<>(uow, registry.machineFor(key));
    }

    /** Cascade to a single peer. Any failure poisons the UnitOfWork: the parent cannot carry on as if nothing happened. */
    <P, Q extends Enum<Q>> Outcome<P, Q> cascade(ContextHandle caller, MachineKey<P, Q> key, P peer, Q target) {
        checkPoison(caller);
        try {
            return cascadeOne(caller, key, peer, target);
        } catch (StateMachineException e) {
            uow.poison(e);
            throw e;
        }
    }

    /**
     * Order of checks per §6.5: identity, convergent no-op, cycle, depth, and only then the step.
     * A no-op runs no code, so it can come before the cycle check without opening a hole.
     */
    private <P, Q extends Enum<Q>> Outcome<P, Q> cascadeOne(ContextHandle caller, MachineKey<P, Q> key, P peer, Q target) {
        DefaultStateMachine<P, Q> machine = registry.machineFor(key);
        EntityRef ref = uow.register(machine.refOf(peer));
        int depth = caller.contextDepth() + 1;
        Optional<Enum<?>> pending = uow.pendingState(ref);
        if (pending.isPresent() && pending.get() == target) {
            uow.append(new LogEntry(ref, target, target, EdgeKind.STAY, Optional.empty(), depth, true));
            return new Outcome.Ignored<>(peer, target, target);
        }
        Optional<InFlight> flight = uow.inFlight(ref);
        if (flight.isPresent()) {
            List<String> route = new ArrayList<>(uow.path());
            route.add(ref.toString());
            throw new CascadeCycleException(details(caller), route, flight.get().target(), target);
        }
        if (depth > uow.maxDepth()) {
            throw new CascadeDepthExceededException(details(caller), uow.maxDepth());
        }
        StepResult<Q> step = transition(machine, peer, target, depth);
        return switch (step) {
            case StepResult.Moved<Q> moved -> new Outcome.Applied<>(peer, moved.from(), moved.to(), List.of(), List.of());
            case StepResult.Ignored<Q> ignored -> new Outcome.Ignored<>(peer, ignored.from(), ignored.to());
            // Unreachable: at depth > 0, rejected(...) always aborts. Kept only for switch exhaustiveness.
            case StepResult.Rejected<Q> rejected ->
                    new Outcome.Rejected<>(peer, rejected.from(), rejected.to(), rejected.reason(), rejected.guard());
            case StepResult.FailedTo<Q> failed -> new Outcome.Failed<>(peer, failed.from(), failed.to(), failed.movedTo(),
                    failed.reason(), failed.cause(), List.of(), List.of());
        };
    }

    /**
     * FAIL_FAST aborts at the first failure. COLLECT tries every element under its own savepoint, rolls back
     * the failed one, and at the end poisons with the aggregate. It is the only place that clears the poison.
     */
    <P, Q extends Enum<Q>> List<Outcome<P, Q>> cascadeAll(ContextHandle caller, MachineKey<P, Q> key,
                                                          Collection<? extends P> peers, Q target, CascadePolicy policy) {
        checkPoison(caller);
        List<Outcome<P, Q>> outcomes = new ArrayList<>();
        List<TransitionAbortedException> errors = new ArrayList<>();
        for (P peer : peers) {
            if (policy == CascadePolicy.FAIL_FAST) {
                outcomes.add(cascade(caller, key, peer, target));
                continue;
            }
            int savepoint = uow.savepoint();
            try {
                outcomes.add(cascadeOne(caller, key, peer, target));
            } catch (TransitionAbortedException e) {
                uow.rollbackTo(savepoint);
                uow.clearPoison();
                errors.add(e);
            } catch (StateMachineException e) {
                uow.poison(e);
                throw e;
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
