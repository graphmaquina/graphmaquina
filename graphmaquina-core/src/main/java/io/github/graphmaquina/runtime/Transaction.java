package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionRef;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.GuardRef;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.error.AbortDetails;
import io.github.graphmaquina.error.CallFailedException;
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

/**
 * Engine of a root transaction. All the state of the transition lives here and in the UnitOfWork, the phase of its
 * call included.
 */
final class Transaction {

    private final MachineRegistry registry;
    private final UnitOfWork uow;
    private final List<ContextHandle> contexts = new ArrayList<>();
    private final MachineKey<?, ?, ?> callKey;
    private final Object callEntity;
    /** The identifier the call requested; null for a reevaluation, which requests none. */
    private final Enum<?> requested;
    private final boolean reaction;
    /** Created by the first exception that needs it, unless an observer brought it; the same instance afterwards. */
    private Optional<MachineCall> call;
    /**
     * Where the call stands. Only the root's own step moves it, to GUARD before its first guard and to ACTION once
     * its guards passed; a cascaded step leaves it at ACTION. The root moves it on to COMMIT, HOOK and REACTIONS.
     */
    private CallPhase phase = CallPhase.RESOLVE;
    private boolean writing;
    /** The state the root's own step read, null until it was read. */
    private Enum<?> rootFrom;
    /** The edge the root's own step selected, null until it selected one. */
    private TransitionSpec<?, ?, ?> rootSpec;

    Transaction(MachineRegistry registry, UnitOfWork uow, MachineKey<?, ?, ?> callKey, Object callEntity,
                Enum<?> requested, boolean reaction, Optional<MachineCall> call) {
        this.registry = registry;
        this.uow = uow;
        this.callKey = callKey;
        this.callEntity = callEntity;
        this.requested = requested;
        this.reaction = reaction;
        this.call = call;
    }

    /** The call this transaction runs: created the first time it is needed, the same instance afterwards. */
    MachineCall call() {
        if (call.isEmpty()) {
            call = Optional.of(new MachineCall(callKey, callEntity, Optional.ofNullable(requested), reaction));
        }
        return call.get();
    }

    /** Whether {@code failure} is a failure of this very call. */
    boolean owns(CallFailedException failure) {
        return call.isPresent() && failure.call() == call.get();
    }

    /**
     * What the observers are told of {@code thrown}: a failure of this call keeps its own values, anything else gets
     * the call's.
     */
    CallFailure failureOf(Throwable thrown) {
        if (thrown instanceof CallFailedException failure && owns(failure)) {
            return new CallFailure(failure.phase(), failure.statesWritten(), failure);
        }
        return new CallFailure(phase, statesWritten(), thrown);
    }

    UnitOfWork uow() { return uow; }

    void enter(CallPhase next) { phase = next; }

    /** Phase 2 is about to call a setter: from here a failure in COMMIT may leave the states partially written. */
    void writing() { writing = true; }

    /**
     * How far the call got in writing states: none before the commit, all after it, and during it what the setters
     * did.
     */
    StatesWritten statesWritten() {
        return switch (phase) {
            case RESOLVE, GUARD, ACTION -> StatesWritten.NONE;
            case COMMIT -> writing ? StatesWritten.PARTIAL : StatesWritten.NONE;
            case HOOK, REACTIONS -> StatesWritten.ALL;
        };
    }

    <P, Q extends Enum<Q>, U extends Enum<U>> MachineKey<P, Q, U> keyFor(Class<? extends Machine<P, Q, U>> facade) {
        return registry.keyFor(facade);
    }

    void invalidate() {
        for (ContextHandle context : contexts) {
            context.invalidate();
        }
    }

    /** For reevaluate, which has no per-call input to carry; a cascade passes {@code null} to the full overload. */
    <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> transition(DefaultStateMachine<E, S, T> machine,
                                                                          E entity, T id, int depth) {
        return transition(machine, entity, id, depth, null);
    }

    /** One step: resolve (current state, identifier) exactly once, guards, annotate, execute, failure route. */
    <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> transition(DefaultStateMachine<E, S, T> machine,
                                                                          E entity, T id, int depth, Object input) {
        machine.requireTransition(id);
        EntityRef ref;
        S from;
        try {
            ref = uow.register(machine.refOf(entity));
            from = currentState(machine, ref, entity);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            if (depth > 0) {
                throw e;
            }
            throw unread(machine, entity, id, e);
        }
        if (depth == 0) {
            rootFrom = from;
        }
        Optional<TransitionSpec<E, S, T>> selected = machine.definition().findTransition(from, id);
        if (selected.isEmpty()) {
            return rejected(machine, ref, from, id, Optional.empty(), depth,
                    machine.definition().policy(from).reasonFor(id), RejectionKind.NO_TRANSITION, Optional.empty());
        }
        TransitionSpec<E, S, T> transition = selected.orElseThrow();
        if (depth == 0) {
            rootSpec = transition;
        }
        uow.push(ref, transition);
        try {
            if (depth == 0 && !transition.guards().isEmpty()) {
                phase = CallPhase.GUARD;
            }
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
            if (depth == 0) {
                phase = CallPhase.ACTION;
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
            DefaultStateMachine<E, S, T> machine, EntityRef ref, E entity, TransitionSpec<E, S, T> transition,
            int depth, int savepoint, Object input) {
        ActionRef<E, S, T> action = transition.action().orElseThrow();
        int journalIndex = uow.appendJournal(new ActionRecord(machine.key(), entity, transition.from(),
                transition.transition(), transition.to(), action.name(), depth, ActionStatus.RUNNING));
        DefaultTransitionContext<E, S, T> ctx =
                new DefaultTransitionContext<>(this, machine, entity, transition, depth, input);
        contexts.add(ctx);
        ActionOutcome outcome;
        try {
            outcome = executeAction(machine, ref, transition, action, ctx);
        } catch (ActionFailure failure) {
            uow.journalStatus(journalIndex, ActionStatus.THREW);
            Throwable cause = failure.getCause();
            if (cause instanceof CallFailedException own && owns(own)) {
                throw poisonOr(own);
            }
            if (matchesFailsOn(transition, cause)) {
                // A poisoned unit of work never commits, and the failure route commits: the poison is thrown before
                // any recovery action can run, as it is when the action returns.
                if (uow.isPoisoned()) {
                    throw uow.poison().orElseThrow();
                }
                return failureRoute(machine, ref, entity, transition, depth, savepoint, transition.onFailure(),
                        Optional.empty(), describe(cause), Optional.of(cause), input);
            }
            throw abort(machine, ref, transition.from(), Optional.of(transition.transition()),
                    Optional.of(transition.to()), Optional.of(action.name()), depth,
                    "the action " + action.name() + " threw " + cause, Optional.of(cause));
        } catch (CallFailedException e) {
            uow.journalStatus(journalIndex, ActionStatus.THREW);
            throw e;
        }
        if (uow.isPoisoned()) {
            uow.journalStatus(journalIndex, ActionStatus.THREW);
            throw uow.poison().orElseThrow();
        }
        if (outcome instanceof ActionOutcome.Failed failed) {
            uow.journalStatus(journalIndex, ActionStatus.FAILED);
            return failureRoute(machine, ref, entity, transition, depth, savepoint,
                    RecoverySelector.returned(transition, failed.failureKind()), failed.failureKind(), failed.reason(),
                    Optional.empty(), input);
        }
        uow.journalStatus(journalIndex, ActionStatus.OK);
        return new StepResult.Applied<>(transition.from(), transition.transition(), transition.to());
    }

    /**
     * What a failure of this call that a callback let through ends the call with: the failure that poisoned the
     * transaction when one did, which a failed cascade always does, or the failure itself.
     */
    private StateMachineException poisonOr(CallFailedException own) {
        return uow.isPoisoned() ? uow.poison().orElseThrow() : own;
    }

    /**
     * What a guard's exception ends the call with: a failure of this call as {@link #poisonOr} says; anything else, a
     * library exception of another call included, an abort of the call in its current phase with it as cause.
     */
    private StateMachineException guardFailure(DefaultStateMachine<?, ?, ?> machine, EntityRef ref,
                                               TransitionSpec<?, ?, ?> transition, int depth, GuardRef<?, ?, ?> guard,
                                               String what, Throwable cause) {
        if (cause instanceof CallFailedException own && owns(own)) {
            return poisonOr(own);
        }
        return abort(machine, ref, transition.from(), Optional.of(transition.transition()),
                Optional.of(transition.to()), Optional.empty(), depth,
                "the guard " + guard.name() + " " + what + " " + cause, Optional.of(cause));
    }

    /**
     * Sync actions run inline; async ones return a stage that is awaited under the edge's deadline. Whatever the action
     * throws, an Error aside, or completes its stage with, checked exceptions included, which Kotlin or
     * {@code @SneakyThrows} code can throw without declaring them, and library exceptions too, becomes an ActionFailure
     * that its caller classifies. An Error is never routed: thrown as itself, it propagates; wrapped in
     * CompletionException or ExecutionException, it is unwrapped and aborts.
     */
    private <E, S extends Enum<S>, T extends Enum<T>> ActionOutcome executeAction(
            DefaultStateMachine<E, S, T> machine, EntityRef ref, TransitionSpec<E, S, T> transition,
            ActionRef<E, S, T> action, DefaultTransitionContext<E, S, T> ctx) {
        ActionOutcome outcome = switch (action) {
            case ActionRef.Sync<E, S, T> sync -> {
                try {
                    yield sync.action().process(ctx);
                } catch (Error fatal) {
                    throw fatal;
                } catch (Throwable e) {
                    throw new ActionFailure(e);
                }
            }
            case ActionRef.Async<E, S, T> async -> {
                long edgeBudget = edgeBudgetNanos(transition);
                CompletionStage<ActionOutcome> stage;
                try {
                    stage = async.action().process(ctx);
                } catch (Error fatal) {
                    throw fatal;
                } catch (Throwable e) {
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
     * Sync guards run inline; async ones return a stage that is awaited under the transaction's deadline. Whatever
     * the guard throws or completes its stage with, an Error inside the stage included, ends the call as
     * {@link #guardFailure} says: a library exception of another call is a cause like any other. An Error thrown as
     * itself propagates.
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
                } catch (Error fatal) {
                    throw fatal;
                } catch (Throwable e) {
                    throw guardFailure(machine, ref, transition, depth, guard, "threw", Unwrap.unwrap(e));
                }
            }
            case GuardRef.Async<E, S, T> async -> {
                CompletionStage<GuardResult> stage;
                try {
                    stage = async.guard().test(ctx);
                } catch (Error fatal) {
                    throw fatal;
                } catch (Throwable e) {
                    throw guardFailure(machine, ref, transition, depth, guard, "threw", Unwrap.unwrap(e));
                }
                if (stage == null) {
                    throw abort(machine, ref, transition.from(), id, to, Optional.empty(), depth,
                            "the guard " + guard.name() + " returned a null stage", Optional.empty());
                }
                try {
                    yield await(machine, ref, transition.from(), id, to, Optional.empty(), depth,
                            "the guard " + guard.name(), stage, uow.deadlineNanos());
                } catch (ActionFailure failure) {
                    throw guardFailure(machine, ref, transition, depth, guard, "failed:", failure.getCause());
                }
            }
        };
        if (verdict == null) {
            throw abort(machine, ref, transition.from(), id, to, Optional.empty(), depth,
                    "the guard " + guard.name() + " returned null", Optional.empty());
        }
        return verdict;
    }

    /**
     * Waits under the deadline. Timeout, interruption and the refusal of the thread's ForkJoinPool to let it block
     * are aborts, each caused by what ended the wait; the user's stage is left running.
     */
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
        } catch (Await.Rejected rejected) {
            throw abort(machine, ref, from, id, to, action, depth,
                    "waiting for " + what + " was refused by the thread's ForkJoinPool",
                    Optional.of(rejected.getCause()));
        }
    }

    /**
     * The edge's own budget in nanoseconds, Long.MAX_VALUE when it sets no limit. It is converted before the action
     * runs, although its clock only starts once the action returns its stage; the conversion saturates and never
     * throws.
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
     * Roll back to the savepoint, then resolve {@code recovery}, the identifier the failure selected, from the
     * original origin and run that edge without guards and without a failure route of its own. An empty
     * {@code recovery} leaves the failure unhandled. The result carries {@code failureKind} as it is.
     */
    private <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> failureRoute(
            DefaultStateMachine<E, S, T> machine, EntityRef ref, E entity, TransitionSpec<E, S, T> failing, int depth,
            int savepoint, Optional<T> recovery, Optional<Class<? extends FailureKind>> failureKind, String reason,
            Optional<Throwable> cause, Object input) {
        uow.rollbackTo(savepoint);
        S from = failing.from();
        Optional<String> actionName = failing.action().map(ActionRef::name);
        if (recovery.isEmpty()) {
            AbortDetails details = details(machine, ref, from, Optional.of(failing.transition()),
                    Optional.of(failing.to()), actionName);
            UnhandledFailureException unhandled = cause.isPresent()
                    ? new UnhandledFailureException(call(), details, reason, cause.get())
                    : new UnhandledFailureException(call(), details, reason);
            throw poisonIfNested(depth, unhandled);
        }
        T recoveryId = recovery.orElseThrow();
        TransitionSpec<E, S, T> route = machine.definition().findTransition(from, recoveryId).orElseThrow();
        // The failed action has ended. Keep one frame for the entity, now identifying the recovery edge.
        uow.pop();
        uow.push(ref, route);
        uow.append(new LogEntry(ref, route, depth));
        if (route.action().isPresent()) {
            ActionRef<E, S, T> action = route.action().orElseThrow();
            int journalIndex = uow.appendJournal(new ActionRecord(machine.key(), entity, from, recoveryId, route.to(),
                    action.name(), depth, ActionStatus.RUNNING));
            DefaultTransitionContext<E, S, T> ctx =
                    new DefaultTransitionContext<>(this, machine, entity, route, depth, input);
            contexts.add(ctx);
            ActionOutcome outcome;
            try {
                outcome = executeAction(machine, ref, route, action, ctx);
            } catch (ActionFailure failure) {
                uow.journalStatus(journalIndex, ActionStatus.THREW);
                Throwable recoveryCause = failure.getCause();
                if (recoveryCause instanceof CallFailedException own && owns(own)) {
                    throw poisonOr(own);
                }
                throw abort(machine, ref, from, Optional.of(recoveryId), Optional.of(route.to()),
                        Optional.of(action.name()), depth,
                        "the failure route action " + action.name() + " threw after the original failure ("
                                + reason + ")",
                        Optional.of(recoveryCause));
            } catch (CallFailedException e) {
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
        return new StepResult.Failed<>(from, failing.transition(), failing.to(), recoveryId, route.to(), reason, cause,
                failureKind);
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

    private <E, S extends Enum<S>, T extends Enum<T>> S currentState(DefaultStateMachine<E, S, T> machine,
                                                                      EntityRef ref, E entity) {
        Optional<Enum<?>> pending = uow.pendingState(ref);
        if (pending.isPresent()) {
            return machine.key().states().cast(pending.get());
        }
        return machine.readState(entity);
    }

    private static <E, S extends Enum<S>, T extends Enum<T>> boolean matchesFailsOn(TransitionSpec<E, S, T> transition,
                                                                                     Throwable cause) {
        if (cause instanceof Error || cause instanceof CancellationException
                || cause instanceof StateMachineException) {
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
     * The reason a failsOn match reports: the throwable's simple name and its message. An anonymous class has an empty
     * simple name, so its binary name stands in: a reason is never blank.
     */
    private static String describe(Throwable cause) {
        String message = cause.getMessage();
        String type = cause.getClass().getSimpleName();
        return (type.isEmpty() ? cause.getClass().getName() : type) + (message == null ? "" : ": " + message);
    }

    private TransitionAbortedException abort(DefaultStateMachine<?, ?, ?> machine, EntityRef ref, Enum<?> from,
                                             Optional<Enum<?>> id, Optional<Enum<?>> to, Optional<String> action,
                                             int depth, String message, Optional<Throwable> cause) {
        AbortDetails details = details(machine, ref, from, id, to, action);
        TransitionAbortedException error = cause.isPresent()
                ? new TransitionAbortedException(call(), phase, details, message, cause.get())
                : new TransitionAbortedException(call(), phase, details, message);
        return poisonIfNested(depth, error);
    }

    /**
     * The root entity's identity or state could not be read: the call aborts in RESOLVE before this step read the
     * entity's state.
     */
    private TransitionAbortedException unread(DefaultStateMachine<?, ?, ?> machine, Object entity, Enum<?> id,
                                              Throwable error) {
        Throwable cause = Unwrap.unwrap(error);
        AbortDetails details = new AbortDetails(machine.key(), entity, Optional.empty(), Optional.of(id),
                Optional.empty(), Optional.empty(), uow.path(), uow.journal());
        return new TransitionAbortedException(call(), CallPhase.RESOLVE, details, "reading the entity threw " + cause,
                cause);
    }

    /**
     * The exception the call throws for {@code error}: a failure of this call as it is; anything else, which only
     * engine code that calls into the application can let through before the commit, an identity's hashCode while
     * the log is rolled back for instance, an abort of the call in its current phase with it as cause. The commit,
     * the hooks and the reactions classify every failure of their own where it happens, so a phase after ACTION never
     * reaches the abort.
     */
    CallFailedException classify(Throwable error) {
        if (error instanceof CallFailedException failure && owns(failure)) {
            return failure;
        }
        uow.markRunningAsThrew();
        Optional<Enum<?>> transition = rootSpec == null ? Optional.ofNullable(requested)
                : Optional.of(rootSpec.transition());
        Optional<Enum<?>> to = rootSpec == null ? Optional.empty() : Optional.of(rootSpec.to());
        AbortDetails details = new AbortDetails(callKey, callEntity, Optional.ofNullable(rootFrom), transition, to,
                Optional.empty(), uow.path(), uow.journal());
        return new TransitionAbortedException(call(), phase, details, "the engine could not go on: " + error, error);
    }

    /** At depth greater than zero, the failure poisons the UoW: the parent cannot go on as if nothing happened. */
    private <X extends StateMachineException> X poisonIfNested(int depth, X error) {
        if (depth > 0) {
            uow.poison(error);
        }
        return error;
    }

    private AbortDetails details(DefaultStateMachine<?, ?, ?> machine, EntityRef ref, Enum<?> from,
                                 Optional<Enum<?>> id, Optional<Enum<?>> to, Optional<String> action) {
        uow.markRunningAsThrew();
        return new AbortDetails(machine.key(), ref.instance(), Optional.of(from), id, to, action, uow.path(),
                uow.journal());
    }

    private AbortDetails details(ContextHandle caller) {
        uow.markRunningAsThrew();
        return new AbortDetails(caller.machineKey(), caller.entityInstance(),
                Optional.of(caller.fromState()), Optional.of(caller.requestedTransition()),
                Optional.of(caller.toState()), Optional.empty(), uow.path(), uow.journal());
    }

    // Peers and cascading.

    /**
     * {@code guard} is the caller context's own check: the view stays as alive as the context that made it. A read
     * stages nothing, so it never poisons: neither does an unknown key here, nor an unknown facade in peerKeyFor.
     */
    <P, Q extends Enum<Q>, U extends Enum<U>> PeerView<P, Q, U> peer(ContextHandle caller, Runnable guard,
                                                                     MachineKey<P, Q, U> key) {
        checkPoison(caller);
        return new DefaultPeerView<>(guard, uow, registry.machineFor(key));
    }

    /**
     * Resolves the facade a peer read names once the poison is checked, in the order cascadeKeyFor follows. A read
     * stages nothing, so an unknown facade throws here without poisoning.
     */
    <P, Q extends Enum<Q>, U extends Enum<U>> MachineKey<P, Q, U> peerKeyFor(ContextHandle caller,
                                                                           Class<? extends Machine<P, Q, U>> facade) {
        checkPoison(caller);
        return keyFor(facade);
    }

    /**
     * Resolves the facade a cascade names inside the poisoning scope: an unknown facade poisons, as an unknown key
     * does once the cascade looks its machine up, and the action receives the abort that carries it.
     */
    <P, Q extends Enum<Q>, U extends Enum<U>> MachineKey<P, Q, U> cascadeKeyFor(
            ContextHandle caller, Class<? extends Machine<P, Q, U>> facade) {
        checkPoison(caller);
        try {
            return keyFor(facade);
        } catch (StateMachineException e) {
            throw poisonWith(caller, facade.getSimpleName(), e);
        }
    }

    /**
     * Cascade to a single peer. An identifier from another catalogue is refused before anything starts, as the context
     * refuses a null argument before calling this: such a refusal stages nothing and poisons nothing. Once the cascade
     * starts, any failure poisons the UnitOfWork: the parent cannot carry on as if nothing happened. The action
     * receives the failure of this call as it is, or else the new abort, as {@link #poisonWith} says, and an Error as
     * itself.
     */
    <P, Q extends Enum<Q>, U extends Enum<U>> Outcome<P, Q, U> cascade(ContextHandle caller, MachineKey<P, Q, U> key,
                                                                       P peer, U id) {
        checkPoison(caller);
        requireTransition(key, id);
        try {
            return cascadeOne(caller, key, peer, id);
        } catch (Throwable error) {
            CallFailedException poisoned = poisonWith(caller, key.toString(), error);
            if (error instanceof Error fatal) {
                throw fatal;
            }
            throw poisoned;
        }
    }

    /** Refuses an identifier from another machine's catalogue, which only a caller that bypasses generics can pass. */
    private static void requireTransition(MachineKey<?, ?, ?> key, Enum<?> id) {
        if (!key.transitions().isInstance(id)) {
            throw new IllegalArgumentException("transition must belong to " + key.transitions().getName() + ": " + id);
        }
    }

    /**
     * Poisons the transaction with a failure that left a cascade, and returns the failure of this call as it is, or
     * else the new abort. A failure of this call is itself the poison. Anything else, an engine exception that
     * describes no call, a throwable from a callback the engine does not wrap such as an identity function, a library
     * exception of another call or an Error, poisons through an abort of the caller's step that names the cascade and
     * carries it as cause; COLLECT never collects that abort. A transaction keeps its first poison.
     */
    private CallFailedException poisonWith(ContextHandle caller, String target, Throwable error) {
        if (error instanceof CallFailedException own && owns(own)) {
            uow.poison(own);
            return own;
        }
        TransitionAbortedException wrapper = new TransitionAbortedException(call(), phase, details(caller),
                "the cascade to " + target + " failed: " + error, error);
        uow.poisonWrapping(wrapper);
        return wrapper;
    }

    /**
     * Order of checks: identity, re-entry, depth, and only then the step. There is no convergent no-op: a request
     * that resolves no edge is rejected, and a request on an entity already in flight is a cycle.
     */
    private <P, Q extends Enum<Q>, U extends Enum<U>> Outcome<P, Q, U> cascadeOne(
            ContextHandle caller, MachineKey<P, Q, U> key, P peer, U id) {
        DefaultStateMachine<P, Q, U> machine = registry.machineFor(key);
        machine.requireTransition(id);
        EntityRef ref = uow.register(machine.refOf(peer));
        int depth = caller.contextDepth() + 1;
        Optional<InFlight> flight = uow.inFlight(ref);
        if (flight.isPresent()) {
            throw cascadeCycle(caller, flight.orElseThrow(), id);
        }
        if (depth > uow.maxDepth()) {
            throw new CascadeDepthExceededException(call(), details(caller), uow.maxDepth());
        }
        return outcomeOf(peer, transition(machine, peer, id, depth, null));
    }

    /** The cascade path plus the entity that was re-entered, with the identifier in flight and the one requested. */
    private CascadeCycleException cascadeCycle(ContextHandle caller, InFlight flight, Enum<?> requested) {
        List<String> route = new ArrayList<>(uow.path());
        route.add(flight.ref().toString());
        return new CascadeCycleException(call(), details(caller), route, flight.transition(), requested);
    }

    /** Adapts a nested step to an outcome: records and reactions belong to the root, so they stay empty here. */
    private <P, Q extends Enum<Q>, U extends Enum<U>> Outcome<P, Q, U> outcomeOf(P peer, StepResult<Q, U> step) {
        return switch (step) {
            case StepResult.Applied<Q, U> applied ->
                    new Outcome.Applied<>(peer, applied.from(), applied.transition(), applied.to(), List.of(),
                            List.of());
            // Unreachable: a nested step executes an edge and never reevaluates. Kept only for switch exhaustiveness.
            case StepResult.Ignored<Q, U> ignored -> new Outcome.Ignored<>(peer, ignored.from(), ignored.reason());
            // Unreachable: at depth > 0, rejected(...) always aborts. Kept only for switch exhaustiveness.
            case StepResult.Rejected<Q, U> rejected -> new Outcome.Rejected<>(peer, rejected.from(),
                    rejected.transition(), rejected.reason(), rejected.kind(), rejected.guard());
            case StepResult.Failed<Q, U> failed -> new Outcome.Failed<>(peer, failed.from(), failed.transition(),
                    failed.to(), failed.failureTransition(), failed.movedTo(), failed.reason(), failed.cause(),
                    List.of(), List.of(), failed.failureKind());
        };
    }

    /**
     * FAIL_FAST aborts at the first failure. COLLECT tries every element under its own savepoint, rolls back
     * the failed one, and at the end poisons with the aggregate. It is the only place that clears the poison.
     * COLLECT gathers the aborts of the call only: any other failure poisons, through an abort that carries it, and
     * stops the cascade at once, a nested one too, once an element's step was poisoned through such an abort, whether
     * its action let it through, caught it or replaced it.
     * Once the poison is checked, the collection is copied, and every later step reads the copy alone: the elements
     * checked are the elements run, and a change to the collection while the cascade runs, by an element's action for
     * instance, changes neither which elements run nor their order. Copying is part of accepting the arguments: an
     * exception or an Error the collection throws while it is copied propagates as it is, and stages and poisons
     * nothing. The arguments are refused before any element runs, as cascade refuses its own, and the refusal stages
     * nothing and poisons nothing: a null element first, then an identifier from another catalogue, which the key
     * carries. The machine is looked up next, before any element runs, so an unknown key poisons whatever valid
     * elements the collection holds, and an empty collection too, as it does in cascade.
     */
    <P, Q extends Enum<Q>, U extends Enum<U>> List<Outcome<P, Q, U>> cascadeAll(ContextHandle caller,
                                                                                MachineKey<P, Q, U> key,
                                                                                Collection<? extends P> peers, U id,
                                                                                CascadePolicy policy) {
        checkPoison(caller);
        List<P> elements = elementsOf(peers);
        return cascadeEach(caller, key, elements, id, policy);
    }

    /**
     * cascadeAll by facade type, in the same order as by key, except that the identifier is checked after the facade
     * resolves: only a resolved facade gives the catalogue it is checked against. A null element is refused before the
     * facade is resolved, so it never poisons, and an unknown facade then poisons.
     */
    <P, Q extends Enum<Q>, U extends Enum<U>> List<Outcome<P, Q, U>> cascadeAll(
            ContextHandle caller, Class<? extends Machine<P, Q, U>> facade, Collection<? extends P> peers, U id,
            CascadePolicy policy) {
        checkPoison(caller);
        List<P> elements = elementsOf(peers);
        return cascadeEach(caller, cascadeKeyFor(caller, facade), elements, id, policy);
    }

    /** A copy of {@code peers}, read once, with a null element refused. */
    private static <P> List<P> elementsOf(Collection<? extends P> peers) {
        List<P> elements = new ArrayList<>(peers);
        for (P peer : elements) {
            Objects.requireNonNull(peer, "peers contains a null element");
        }
        return elements;
    }

    /** The rest of cascadeAll once its elements are accepted: the identifier, the machine, then every element. */
    private <P, Q extends Enum<Q>, U extends Enum<U>> List<Outcome<P, Q, U>> cascadeEach(
            ContextHandle caller, MachineKey<P, Q, U> key, List<P> elements, U id, CascadePolicy policy) {
        requireTransition(key, id);
        requireMachine(caller, key);
        List<Outcome<P, Q, U>> outcomes = new ArrayList<>();
        List<TransitionAbortedException> errors = new ArrayList<>();
        for (P peer : elements) {
            if (policy == CascadePolicy.FAIL_FAST) {
                outcomes.add(cascade(caller, key, peer, id));
                continue;
            }
            int savepoint = uow.savepoint();
            try {
                outcomes.add(cascadeOne(caller, key, peer, id));
            } catch (Throwable error) {
                if (error instanceof TransitionAbortedException abort && owns(abort)
                        && !uow.poisonWrapsAForeignFailure()) {
                    uow.rollbackTo(savepoint);
                    uow.clearPoison();
                    errors.add(abort);
                    continue;
                }
                CallFailedException poisoned = poisonWith(caller, key.toString(), error);
                if (error instanceof Error fatal) {
                    throw fatal;
                }
                throw poisoned;
            }
        }
        if (!errors.isEmpty()) {
            CascadeException aggregate = new CascadeException(call(), details(caller), errors);
            uow.poison(aggregate);
            throw aggregate;
        }
        return List.copyOf(outcomes);
    }

    /** Looks the machine of a cascade up inside the poisoning scope: an unknown key poisons, as in cascadeOne. */
    private void requireMachine(ContextHandle caller, MachineKey<?, ?, ?> key) {
        try {
            registry.anyMachineFor(key);
        } catch (StateMachineException e) {
            throw poisonWith(caller, key.toString(), e);
        }
    }

    private void checkPoison(ContextHandle caller) {
        if (uow.isPoisoned()) {
            throw new PoisonedUnitOfWorkException(call(), details(caller), uow.poison().orElseThrow());
        }
    }
}
