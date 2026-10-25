package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.FlowEntry;
import io.github.graphmaquina.api.GuardRef;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.SubmachineSpec;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.StateMachineException;
import io.github.graphmaquina.internal.Interrupts;
import io.github.graphmaquina.internal.Texts;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * One step of a call, the root's, a cascade's or an exit's: the state read, the edge resolved from it, then, with the
 * step's frame pushed, the guards, the hop and the action, or the rejection. The binding check and the exits of a
 * machine that takes part in a binding are {@link Submachines}'; the action is {@link Actions}'.
 */
final class Steps {

    private Steps() { }

    /**
     * The exit a {@link #step} runs: the bound node it leaves, which is its source, and the child whose terminal state
     * selected it.
     */
    record ExitOf(Enum<?> node, EntityRef child) { }

    /** What {@link #resolve} found for a step before its guards: a refusal, or the edge the step goes on along. */
    sealed interface Resolution<E, S extends Enum<S>, T extends Enum<T>>
            permits Resolution.Refused, Resolution.Selected {

        /**
         * The step was refused before its guards: the child is detached, no edge leaves its state with its
         * identifier, or the identifier is a reserved exit. {@code result} is the rejection a root step returns; a
         * nested step's refusal throws instead, so it never gets one.
         */
        record Refused<E, S extends Enum<S>, T extends Enum<T>>(StepResult<S, T> result)
                implements Resolution<E, S, T> { }

        /**
         * The step goes on along {@code transition}, the edge its state and identifier selected. {@code holder} is the
         * binding that holds the stepping child, as the binding check found it, for the exits the step calls for;
         * empty for a free child and for a machine that no graph binds as a sub-machine.
         */
        record Selected<E, S extends Enum<S>, T extends Enum<T>>(TransitionSpec<E, S, T> transition,
                                                                 Optional<Submachines.Holder> holder)
                implements Resolution<E, S, T> { }
    }

    /**
     * One step requested by the call, a cascade or a watch, never the exit of a sub-machine: resolve (current state,
     * identifier) exactly once, as {@link #resolve} says, then guards, annotate, execute, failure route, as
     * {@link #runEdge} says. A machine that takes part in a binding takes {@link #step} instead, which runs its exits
     * too; any other pays one field read for that, besides what {@link #resolve} costs a machine that takes part in no
     * binding. When the root's own step throws once it has read its state, it records that state and its edge for
     * {@link Transaction#classify}. The root's own step hands the reference it registered to the call's log, which
     * prints it. A call that logs at TRACE logs the edge the step resolved, or that it found none, at the step's depth.
     * {@code input} is the operation input of the call, empty when it has none; a cascade passes none. The contexts
     * hand it out as it is.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> transition(
            Transaction tx, DefaultStateMachine<E, S, T> machine, E entity, T id, int depth, Optional<Object> input) {
        if (machine.hasBindings()) {
            return step(tx, machine, entity, id, depth, input, Optional.empty());
        }
        Transaction.requireTransition(machine.key(), id);
        EntityRef ref = register(tx, machine, entity, id, depth);
        S from = stateOf(tx, machine, ref, entity, id, depth, Optional.empty());
        try {
            return switch (resolve(tx, machine, ref, entity, from, id, depth, Optional.empty())) {
                case Resolution.Refused<E, S, T> refused -> refused.result();
                case Resolution.Selected<E, S, T> selected ->
                        runEdge(tx, machine, ref, entity, from, selected.transition(), depth, input, Optional.empty());
            };
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            if (depth == 0) {
                tx.recordRootStep(from, id, selectedDestination(machine, from, id));
            }
            throw e;
        }
    }

    /**
     * One step of a machine that takes part in a binding: read the state, then check the bindings of a sub-machine,
     * resolve (current state, identifier) exactly once and refuse a reserved exit, as {@link #resolve} says, then
     * guards, annotate, execute and failure route as {@link #runEdge} says; then, once the step's frame is popped, the
     * exits it calls for, as {@link Submachines#exits} says. {@code exitOf} is the captured bound node whose exit this
     * step runs, with the child whose terminal state selected it: the node supplies the source instead of rereading
     * the parent's getter, and grants reserved-exit privilege only at that source; the child is what the step's
     * resolved line names. It is empty for any other step. When the root's own step throws once it has read its state,
     * it records that state and its edge for {@link Transaction#classify}. The root's own step hands the reference it
     * registered to the call's log, which prints it. A call that logs at TRACE logs what {@link #resolve} says, at the
     * step's depth.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> step(
            Transaction tx, DefaultStateMachine<E, S, T> machine, E entity, T id, int depth, Optional<Object> input,
            Optional<ExitOf> exitOf) {
        Transaction.requireTransition(machine.key(), id);
        EntityRef ref = register(tx, machine, entity, id, depth);
        S from = stateOf(tx, machine, ref, entity, id, depth, exitOf);
        try {
            return switch (resolve(tx, machine, ref, entity, from, id, depth, exitOf)) {
                case Resolution.Refused<E, S, T> refused -> refused.result();
                case Resolution.Selected<E, S, T> selected -> {
                    StepResult<S, T> result =
                            runEdge(tx, machine, ref, entity, from, selected.transition(), depth, input, exitOf);
                    yield Submachines.exits(tx, machine, ref, entity, depth, result, selected.holder());
                }
            };
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            if (depth == 0) {
                tx.recordRootStep(from, id, selectedDestination(machine, from, id));
            }
            throw e;
        }
    }

    /**
     * The admission of the step of {@code ref} before its guards, once it has read its state {@code from}: for a
     * machine that some graph binds as a sub-machine, the binding check, as {@link Submachines#checkBindings} says,
     * which refuses a detached child; then the edge that {@code from} and {@code id} select, looked up exactly once and
     * refused as {@link #noTransition} says when there is none; then, unless {@code exitOf} is present, the refusal of
     * an identifier that is an exit of the sub-machine bound to {@code from}, as {@link #isReservedExit} says. A
     * refusal is {@link Resolution.Refused} at the root and throws when nested, as {@link #rejected} says; otherwise
     * the result is {@link Resolution.Selected}. {@code exitOf} is present only for the step of an exit, as
     * {@link #step} says: it grants that step reserved-exit privilege, and its child is what the resolved line names.
     * For a machine that no graph binds as a sub-machine, the binding check is one test of an empty list, and for one
     * whose own graph binds none, the reserved-exit check is one field read. A call that logs at TRACE logs what the
     * binding check found, the binding that holds the child or the reason it is detached, then the edge the step
     * resolved, or that it found none, at the step's depth. It pushes no frame and runs no guard.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> Resolution<E, S, T> resolve(
            Transaction tx, DefaultStateMachine<E, S, T> machine, EntityRef ref, E entity, S from, T id, int depth,
            Optional<ExitOf> exitOf) {
        Optional<Submachines.Holder> holder = Optional.empty();
        if (!machine.bindingsAsChild().isEmpty()) {
            Submachines.BindingCheck check = Submachines.checkBindings(tx, machine, ref, entity, from, id, depth);
            traceCheck(tx, ref, depth, check);
            if (check.detached().isPresent()) {
                return new Resolution.Refused<>(rejected(tx, machine, ref, from, id, Optional.empty(), depth,
                        check.detached().get(), RejectionKind.DETACHED, Optional.empty(), exitOf));
            }
            holder = check.holder();
        }
        Optional<TransitionSpec<E, S, T>> selected = machine.definition().findTransition(from, id);
        if (selected.isEmpty()) {
            return new Resolution.Refused<>(noTransition(tx, machine, ref, from, id, depth, exitOf));
        }
        TransitionSpec<E, S, T> transition = selected.orElseThrow();
        if (tx.log().trace()) {
            tx.log().trace(depth, resolved(ref, transition, exitOf));
        }
        if (exitOf.isEmpty() && isReservedExit(machine, from, id)) {
            return new Resolution.Refused<>(rejected(tx, machine, ref, from, id, Optional.of(transition.to()), depth,
                    id.name() + " leaves " + from.name() + " only when its sub-machine ends",
                    RejectionKind.RESERVED_EXIT, Optional.empty(), exitOf));
        }
        return new Resolution.Selected<>(transition, holder);
    }

    /**
     * Registers the entity of a step in the unit of work under its machine's identity; the root's own step hands the
     * reference to the call's log, which prints it. An identity that cannot be read aborts the root's step as
     * {@link Transaction#unread} says; nested, what it threw propagates as it is.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> EntityRef register(
            Transaction tx, DefaultStateMachine<E, S, T> machine, E entity, T id, int depth) {
        try {
            EntityRef ref = tx.uow().register(machine.refOf(entity));
            if (depth == 0 && tx.log().debug()) {
                tx.log().entity(ref);
            }
            return ref;
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            if (depth > 0) {
                throw e;
            }
            throw tx.unread(machine, entity, id, e);
        }
    }

    /**
     * The state a step starts from: the bound node that {@code exitOf} names for an exit, its current state for any
     * other step. A state that cannot be read aborts the root's step as {@link Transaction#unread} says; nested, what
     * reading it threw propagates as it is.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> S stateOf(
            Transaction tx, DefaultStateMachine<E, S, T> machine, EntityRef ref, E entity, T id, int depth,
            Optional<ExitOf> exitOf) {
        try {
            return exitOf.isPresent()
                    ? machine.key().states().cast(exitOf.get().node()) : currentState(tx, machine, ref, entity);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            if (depth > 0) {
                throw e;
            }
            throw tx.unread(machine, entity, id, e);
        }
    }

    /**
     * The destination of the edge a root step that threw had selected for {@code (from, id)}, looked up again for its
     * abort: a definition answers the same edge for the same key every time. Empty when the definition declares none,
     * and when the lookup itself throws, as it does for a state of another enum, which only a getter that broke its
     * type returns.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> Optional<Enum<?>> selectedDestination(
            DefaultStateMachine<E, S, T> machine, S from, T id) {
        try {
            Optional<TransitionSpec<E, S, T>> edge = machine.definition().findTransition(from, id);
            return edge.isPresent() ? Optional.of(edge.get().to()) : Optional.empty();
        } catch (RuntimeException lookup) {
            return Optional.empty();
        }
    }

    /**
     * Logs at TRACE what the binding check of the step of {@code ref} found: the reason the child is detached, or the
     * binding that holds it, with its parent; nothing for a free child.
     */
    private static void traceCheck(Transaction tx, EntityRef ref, int depth, Submachines.BindingCheck check) {
        if (check.detached().isPresent()) {
            if (tx.log().trace()) {
                tx.log().trace(depth, "detached " + CallLog.show(ref) + " reason=\"" + check.detached().get() + "\"");
            }
        } else if (check.holder().isPresent() && tx.log().trace()) {
            tx.log().trace(depth, "binding " + CallLog.show(ref) + " parent="
                    + CallLog.show(check.holder().get().parent()) + " node="
                    + check.holder().get().binding().spec().state().name());
        }
    }

    /**
     * A step whose state and identifier select no edge: rejected, with the reason the state's policy gives, as
     * {@link #rejected} says, once a call that logs at TRACE logged that the step found no edge.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> noTransition(
            Transaction tx, DefaultStateMachine<E, S, T> machine, EntityRef ref, S from, T id, int depth,
            Optional<ExitOf> exitOf) {
        if (tx.log().trace()) {
            tx.log().trace(depth, noEdge(ref, from, id));
        }
        return rejected(tx, machine, ref, from, id, Optional.empty(), depth,
                machine.definition().policy(from).reasonFor(id), RejectionKind.NO_TRANSITION, Optional.empty(), exitOf);
    }

    /** The text of the TRACE line of a step that found no edge: {@code no-edge <ref> <from> <T>}. */
    private static String noEdge(EntityRef ref, Enum<?> from, Enum<?> id) {
        return "no-edge " + CallLog.show(ref) + " " + from.name() + " " + id.name();
    }

    /**
     * The text of the TRACE line of a step that resolved {@code edge}:
     * {@code resolved <ref> <from> -<T>-> <to> kind=<kind>[ exit-of=<child>]}, the child only for an exit.
     */
    private static String resolved(EntityRef ref, TransitionSpec<?, ?, ?> edge, Optional<ExitOf> exitOf) {
        return "resolved " + CallLog.show(ref) + " " + edge.from().name() + " -" + edge.transition().name() + "-> "
                + edge.to().name() + " kind=" + edge.kind().name()
                + (exitOf.isPresent() ? " exit-of=" + CallLog.show(exitOf.get().child()) : "");
    }

    /**
     * The selected edge of a {@link #transition} or a {@link #step}, with the step's frame pushed until it ends:
     * guards, as {@link #admitGuards} says, then annotate, execute, failure route. The hop is logged as
     * {@link #appendHop} says.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> runEdge(
            Transaction tx, DefaultStateMachine<E, S, T> machine, EntityRef ref, E entity, S from,
            TransitionSpec<E, S, T> transition, int depth, Optional<Object> input, Optional<ExitOf> exitOf) {
        UnitOfWork uow = tx.uow();
        uow.push(ref, transition);
        try {
            Optional<StepResult<S, T>> refused =
                    admitGuards(tx, machine, ref, entity, from, transition, depth, input, exitOf);
            if (refused.isPresent()) {
                return refused.get();
            }
            if (depth == 0) {
                tx.enter(CallPhase.ACTION);
            }
            int savepoint = uow.savepoint();
            appendHop(tx, machine, ref, entity, transition, depth, Optional.empty());
            if (transition.action().isEmpty()) {
                return new StepResult.Applied<>(from, transition.transition(), transition.to());
            }
            return Actions.runAction(tx, machine, ref, entity, transition, depth, savepoint, input);
        } finally {
            uow.pop();
        }
    }

    /**
     * The guards of {@code transition}, the edge the step of {@code ref} selected from {@code from}, run in
     * declaration order once the step's frame is pushed, which the caller does. Each gets a new context of its own,
     * which the call tracks, and is recorded as the guard in flight, as {@link Transaction#guarding} says, right
     * before it is evaluated as {@link #evaluateGuard} says. The first that rejects refuses the step, as
     * {@link #rejected} says, and the guards after it do not run. The root's own step enters GUARD before its first
     * guard. Returns that refusal at the root, where it is the step's result, since a nested one throws; empty when
     * every guard passed or the edge has none.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> Optional<StepResult<S, T>> admitGuards(
            Transaction tx, DefaultStateMachine<E, S, T> machine, EntityRef ref, E entity, S from,
            TransitionSpec<E, S, T> transition, int depth, Optional<Object> input, Optional<ExitOf> exitOf) {
        if (depth == 0 && !transition.guards().isEmpty()) {
            tx.enter(CallPhase.GUARD);
        }
        for (GuardRef<E, S, T> guard : transition.guards()) {
            DefaultGuardContext<E, S, T> guardContext =
                    new DefaultGuardContext<>(tx, machine, entity, transition, depth, input);
            tx.track(guardContext);
            tx.guarding(guard.name());
            GuardResult verdict = evaluateGuard(tx, machine, ref, transition, depth, guard, guardContext);
            if (verdict instanceof GuardResult.Reject reject) {
                return Optional.of(rejected(tx, machine, ref, from, transition.transition(),
                        Optional.of(transition.to()), depth, reject.reason(), RejectionKind.GUARD,
                        Optional.of(guard.name()), exitOf));
            }
        }
        return Optional.empty();
    }

    /**
     * Whether {@code id} is an exit of the sub-machine bound to {@code from} in the machine's own graph (spec R4), so
     * that a request for it from {@code from} is refused. A machine whose graph binds no sub-machine pays one field
     * read.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> boolean isReservedExit(
            DefaultStateMachine<E, S, T> machine, S from, T id) {
        if (!machine.bindsSubmachines()) {
            return false;
        }
        Optional<SubmachineSpec<E, S, T, ?, ?, ?>> binding = machine.definition().submachine(from);
        return binding.isPresent() && binding.get().exits().containsValue(id);
    }

    /**
     * Logs the hop of {@code entity} along {@code edge}. When the edge enters a state of the machine's own graph that
     * holds a sub-machine, the start of that child follows right after it, as {@link Submachines#start} says; a
     * machine whose graph binds no sub-machine pays one field read. Both entries sit after the savepoint the caller
     * took, so a rollback to it drops them together. {@code recovery} is present only on the hop of a failure route in
     * a call that records its trail: it goes on the hop's mark as soon as the hop is marked, before the child starts,
     * so a start that aborts leaves the hop in the trail with what it recovered from.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> void appendHop(Transaction tx,
                                                                    DefaultStateMachine<E, S, T> machine,
                                                                    EntityRef ref, E entity,
                                                                    TransitionSpec<E, S, T> edge, int depth,
                                                                    Optional<FlowEntry.Recovery> recovery) {
        UnitOfWork uow = tx.uow();
        uow.append(new LogEntry.Hop(ref, edge, depth));
        if (recovery.isPresent()) {
            uow.flow().orElseThrow().recovery(uow.savepoint() - 1, recovery.get());
        }
        if (machine.bindsSubmachines()) {
            Optional<SubmachineSpec<E, S, T, ?, ?, ?>> binding = machine.definition().submachine(edge.to());
            if (binding.isPresent()) {
                Submachines.start(tx, machine, ref, edge, depth, machine, ref, entity, binding.get());
            }
        }
    }

    /**
     * Sync guards run inline; async ones return a stage that is awaited under the transaction's deadline, as
     * {@link #asyncVerdict} says. For whatever the guard throws or completes its stage with, an Error inside the stage
     * included, the engine throws what {@link #guardFailure} says: a library exception of another call is a cause like
     * any other. An InterruptedException that the guard throws on the calling thread interrupts the thread again; one
     * that completes its stage leaves the thread as it is. An Error thrown as itself propagates. A verdict is logged at
     * TRACE with the time the guard took; a guard that throws logs none.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> GuardResult evaluateGuard(
            Transaction tx, DefaultStateMachine<E, S, T> machine, EntityRef ref, TransitionSpec<E, S, T> transition,
            int depth, GuardRef<E, S, T> guard, DefaultGuardContext<E, S, T> ctx) {
        long started = tx.log().trace() ? System.nanoTime() : 0L;
        Optional<Enum<?>> id = Optional.of(transition.transition());
        Optional<Enum<?>> to = Optional.of(transition.to());
        GuardResult verdict = switch (guard) {
            case GuardRef.Sync<E, S, T> sync -> {
                try {
                    yield sync.guard().test(ctx);
                } catch (Error fatal) {
                    throw fatal;
                } catch (Throwable e) {
                    Interrupts.keep(e);
                    throw guardFailure(tx, machine, ref, transition, depth, guard, "threw", Unwrap.unwrap(e));
                }
            }
            case GuardRef.Async<E, S, T> async -> asyncVerdict(tx, machine, ref, transition, depth, async, ctx, id, to);
        };
        if (verdict == null) {
            throw tx.abort(machine, ref, transition.from(), id, to, Optional.empty(), depth,
                    "the guard " + guard.name() + " returned null", Optional.empty());
        }
        if (tx.log().trace()) {
            String said = verdict instanceof GuardResult.Reject reject
                    ? " REJECT reason=\"" + reject.reason() + "\"" : " PASS";
            tx.log().trace(depth, "guard " + guard.name() + said + " elapsed="
                    + CallLog.micros(System.nanoTime() - started));
        }
        return verdict;
    }

    /**
     * The verdict of the asynchronous {@code guard}: the stage it returns, awaited under the transaction's deadline,
     * where {@code id} and {@code to} name the edge in the aborts. A null stage, a null verdict and a wait that timed
     * out, was interrupted or was refused abort the call, the last three poisoning it at once when nested.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> GuardResult asyncVerdict(
            Transaction tx, DefaultStateMachine<E, S, T> machine, EntityRef ref, TransitionSpec<E, S, T> transition,
            int depth, GuardRef.Async<E, S, T> guard, DefaultGuardContext<E, S, T> ctx, Optional<Enum<?>> id,
            Optional<Enum<?>> to) {
        CompletionStage<GuardResult> stage;
        try {
            stage = guard.guard().test(ctx);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            Interrupts.keep(e);
            throw guardFailure(tx, machine, ref, transition, depth, guard, "threw", Unwrap.unwrap(e));
        }
        if (stage == null) {
            throw tx.abort(machine, ref, transition.from(), id, to, Optional.empty(), depth,
                    "the guard " + guard.name() + " returned a null stage", Optional.empty());
        }
        try {
            Optional<GuardResult> result = Actions.await(tx, machine, ref, transition.from(), id, to, Optional.empty(),
                    "the guard " + guard.name(), stage, tx.uow().deadlineNanos());
            if (result.isEmpty()) {
                throw tx.abort(machine, ref, transition.from(), id, to, Optional.empty(), depth,
                        "the guard " + guard.name() + " returned null", Optional.empty());
            }
            return result.orElseThrow();
        } catch (EngineAbort aborted) {
            throw tx.poisonIfNested(depth, aborted.abort());
        } catch (ActionFailure failure) {
            throw guardFailure(tx, machine, ref, transition, depth, guard, "failed:", failure.getCause());
        }
    }

    /**
     * What the engine throws for a guard's exception: a failure of this call as {@link Transaction#poisonOr} says;
     * anything else, a library exception of another call included, an abort of the call in its current phase with it
     * as cause.
     */
    private static StateMachineException guardFailure(Transaction tx, DefaultStateMachine<?, ?, ?> machine,
                                                      EntityRef ref, TransitionSpec<?, ?, ?> transition, int depth,
                                                      GuardRef<?, ?, ?> guard, String what, Throwable cause) {
        if (cause instanceof CallFailedException own && tx.owns(own)) {
            return tx.poisonOr(own);
        }
        return tx.abort(machine, ref, transition.from(), Optional.of(transition.transition()),
                Optional.of(transition.to()), Optional.empty(), depth,
                "the guard " + guard.name() + " " + what + " " + Texts.shown(cause), Optional.of(cause));
    }

    /**
     * A rejection: at the root, the step's result; nested, an abort that poisons, whose message says whether it
     * happened inside a cascade or inside the exit of the node {@code exitOf} names.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> rejected(
            Transaction tx, DefaultStateMachine<E, S, T> machine, EntityRef ref, S from, T id, Optional<S> to,
            int depth, String reason, RejectionKind kind, Optional<String> guard, Optional<ExitOf> exitOf) {
        if (depth == 0) {
            return new StepResult.Rejected<>(from, id, reason, kind, guard);
        }
        // Not a switch, as in Transaction.statesWritten.
        String who;
        if (kind == RejectionKind.NO_TRANSITION) {
            who = "no such transition";
        } else if (kind == RejectionKind.GUARD) {
            who = "guard " + guard.orElseThrow();
        } else if (kind == RejectionKind.DETACHED) {
            who = "detached sub-machine";
        } else {
            who = "reserved exit";
        }
        String inside = exitOf.isPresent() ? "the exit of " + exitOf.get().node().name() : "a cascade";
        throw tx.abort(machine, ref, from, Optional.of(id), to.map((S state) -> (Enum<?>) state), Optional.empty(),
                depth, "rejected inside " + inside + " by " + who + ": " + reason, Optional.empty());
    }

    /** The state of {@code entity}: its pending one in the unit of work, else what its machine's getter reads. */
    static <E, S extends Enum<S>, T extends Enum<T>> S currentState(Transaction tx,
                                                                     DefaultStateMachine<E, S, T> machine,
                                                                     EntityRef ref, E entity) {
        Optional<Enum<?>> pending = tx.uow().pendingState(ref);
        if (pending.isPresent()) {
            return machine.key().states().cast(pending.get());
        }
        return machine.readState(entity);
    }
}
