package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.ActionRef;
import io.github.graphmaquina.api.ActionStatus;
import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.api.GuardRef;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.SubmachineSpec;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.error.AbortDetails;
import io.github.graphmaquina.error.AmbiguousIdentityException;
import io.github.graphmaquina.error.AmbiguousParentException;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.CascadeCycleException;
import io.github.graphmaquina.error.CascadeDepthExceededException;
import io.github.graphmaquina.error.CascadeException;
import io.github.graphmaquina.error.PoisonedUnitOfWorkException;
import io.github.graphmaquina.error.StateMachineException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UnhandledFailureException;
import io.github.graphmaquina.internal.FrozenFlow;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CompletionStage;

/**
 * Engine of a root transaction. All the state of the transition lives here and in the UnitOfWork, the phase of its
 * call included, and so does the call's log.
 */
final class Transaction {

    private final MachineRegistry registry;
    private final UnitOfWork uow;
    private final List<ContextHandle> contexts = new ArrayList<>();
    private final MachineKey<?, ?, ?> callKey;
    private final Object callEntity;
    /** The identifier the call requested; empty for a reevaluation, which requests none. */
    private final Optional<Enum<?>> requested;
    private final boolean reaction;
    /** Created by the first exception that needs it, unless an observer brought it; the same instance afterwards. */
    private Optional<MachineCall> call;
    /**
     * Where the call stands. Only the root's own step moves it, to GUARD before its first guard and to ACTION once
     * its guards passed; a cascaded step leaves it at ACTION. The root moves it on to COMMIT, HOOK and REACTIONS.
     */
    private CallPhase phase = CallPhase.RESOLVE;
    private boolean writing;
    /** The state the root's own step read and the edge it selected, recorded when that step throws; empty before. */
    private Optional<RootStep> rootStep = Optional.empty();
    /** The log of the call: {@link CallLog#OFF} when the call found both levels off. */
    private final CallLog log;

    Transaction(MachineRegistry registry, UnitOfWork uow, MachineKey<?, ?, ?> callKey, Object callEntity,
                Optional<Enum<?>> requested, boolean reaction, Optional<MachineCall> call, CallLog log) {
        this.registry = registry;
        this.uow = uow;
        this.callKey = callKey;
        this.callEntity = callEntity;
        this.requested = requested;
        this.reaction = reaction;
        this.call = call;
        this.log = log;
    }

    /** The log of the call this transaction runs. */
    CallLog log() { return log; }

    /** The call this transaction runs: created the first time it is needed, the same instance afterwards. */
    MachineCall call() {
        if (call.isEmpty()) {
            call = Optional.of(new MachineCall(callKey, callEntity, requested, reaction));
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

    /**
     * A handle on the call's flow for a context of this call at {@code depth}, checked by {@code guard}, that
     * context's own check. It does not create the flow: the first write of the call does, with the entity of the
     * root call.
     */
    FlowHandle flow(Runnable guard, int depth) {
        return FlowHandle.of(uow, callEntity, guard, log, depth);
    }

    /**
     * A handle on the call's flow for the post-commit hook named {@code hook}, which runs for the committed hop
     * {@code hop}. It does not create the flow either: its first write does, if no step of the call wrote.
     */
    FlowHandle.Hook hookFlow(String hook, EntityRef hop) {
        return FlowHandle.hook(uow, callEntity, log, hook, hop);
    }

    /**
     * The call's flow as the call hands it out: frozen, with the root call's entity and the values present now, or
     * {@link FlowView#empty()} when no write of the call created the flow. It never creates the flow.
     */
    FlowView frozenFlow() {
        Optional<CallFlow> flow = uow.flow();
        if (flow.isEmpty()) {
            return FlowView.empty();
        }
        return new FrozenFlow(Optional.of(flow.get().rootEntity()), flow.get().values());
    }

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

    /** For reevaluate, which has no per-call input to carry; a cascade passes no input to the full overload either. */
    <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> transition(DefaultStateMachine<E, S, T> machine,
                                                                          E entity, T id, int depth) {
        return transition(machine, entity, id, depth, Optional.empty());
    }

    /**
     * One step requested by the call, a cascade or a watch, never the exit of a sub-machine: resolve (current state,
     * identifier) exactly once, guards, annotate, execute, failure route. A machine that takes part in a binding takes
     * {@link #step} instead, which checks its bindings and runs its exits too; any other pays one field read for that.
     * When the root's own step throws once it has read its state, it records that state and its edge for
     * {@link #classify}. The root's own step hands the reference it registered to the call's log, which prints it.
     * A call that logs at TRACE logs the edge the step resolved, or that it found none, at the step's depth.
     * {@code input} is the operation input of the call, empty when it has none; the contexts hand it out as it is.
     */
    <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> transition(DefaultStateMachine<E, S, T> machine,
                                                                          E entity, T id, int depth,
                                                                          Optional<Object> input) {
        if (machine.hasBindings()) {
            return step(machine, entity, id, depth, input, Optional.empty());
        }
        machine.requireTransition(id);
        EntityRef ref;
        S from;
        try {
            ref = uow.register(machine.refOf(entity));
            if (depth == 0 && log.debug()) {
                log.entity(ref);
            }
            from = currentState(machine, ref, entity);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            if (depth > 0) {
                throw e;
            }
            throw unread(machine, entity, id, e);
        }
        try {
            Optional<TransitionSpec<E, S, T>> selected = machine.definition().findTransition(from, id);
            if (selected.isEmpty()) {
                if (log.trace()) {
                    log.trace(depth, noEdge(ref, from, id));
                }
                return rejected(machine, ref, from, id, Optional.empty(), depth,
                        machine.definition().policy(from).reasonFor(id), RejectionKind.NO_TRANSITION, Optional.empty(),
                        Optional.empty());
            }
            TransitionSpec<E, S, T> transition = selected.orElseThrow();
            if (log.trace()) {
                log.trace(depth, resolved(ref, transition, Optional.empty()));
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
                                RejectionKind.GUARD, Optional.of(guard.name()), Optional.empty());
                    }
                }
                if (depth == 0) {
                    phase = CallPhase.ACTION;
                }
                int savepoint = uow.savepoint();
                uow.append(new LogEntry.Hop(ref, transition, depth));
                if (transition.action().isEmpty()) {
                    return new StepResult.Applied<>(from, id, transition.to());
                }
                return runAction(machine, ref, entity, transition, depth, savepoint, input);
            } finally {
                uow.pop();
            }
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            if (depth == 0) {
                rootStep = Optional.of(new RootStep(from, selectedEdge(machine, from, id)));
            }
            throw e;
        }
    }

    /**
     * The edge a root step that threw had selected for {@code (from, id)}, looked up again for its abort: a definition
     * answers the same edge for the same key every time. Empty when the definition declares none, and when the lookup
     * itself throws, as it does for a state of another enum, which only a getter that broke its type returns.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> Optional<? extends TransitionSpec<?, ?, ?>> selectedEdge(
            DefaultStateMachine<E, S, T> machine, S from, T id) {
        try {
            return machine.definition().findTransition(from, id);
        } catch (RuntimeException lookup) {
            return Optional.empty();
        }
    }

    /** What the root's own step had read and selected when it threw; {@code edge} is empty when it selected none. */
    private record RootStep(Enum<?> from, Optional<? extends TransitionSpec<?, ?, ?>> edge) { }

    /**
     * The exit a {@link #step} runs: the bound node it leaves, which is its source, and the child whose terminal state
     * selected it.
     */
    private record ExitOf(Enum<?> node, EntityRef child) { }

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
     * One step of a machine that takes part in a binding: read the state, check the bindings of a sub-machine,
     * resolve (current state, identifier) exactly once, refuse a reserved exit, then guards, annotate, execute and
     * failure route as {@link #runEdge} says; then, once the step's frame is popped, the exits it calls for, as
     * {@link #exits} says. {@code exitOf} is the captured bound node whose exit this step runs, with the child whose
     * terminal state selected it: the node supplies the source instead of rereading the parent's getter, and grants
     * reserved-exit privilege only at that source; the child is what the step's resolved line names. It is empty
     * for any other step. For a machine that no graph binds as a sub-machine, the binding check
     * is one test of an empty list, and for one whose own graph binds none, the reserved-exit check is one field read.
     * When the root's own step throws once it has read its state, it records that state and its edge for
     * {@link #classify}. The root's own step hands the reference it registered to the call's log, which prints it.
     * A call that logs at TRACE logs what the binding check found, the binding that holds the child or the reason it
     * is detached, then the edge the step resolved, or that it found none, at the step's depth.
     */
    private <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> step(DefaultStateMachine<E, S, T> machine,
                                                                            E entity, T id, int depth,
                                                                            Optional<Object> input,
                                                                            Optional<ExitOf> exitOf) {
        machine.requireTransition(id);
        EntityRef ref;
        S from;
        try {
            ref = uow.register(machine.refOf(entity));
            if (depth == 0 && log.debug()) {
                log.entity(ref);
            }
            from = exitOf.isPresent()
                    ? machine.key().states().cast(exitOf.get().node()) : currentState(machine, ref, entity);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            if (depth > 0) {
                throw e;
            }
            throw unread(machine, entity, id, e);
        }
        try {
            Optional<Holder> holder = Optional.empty();
            if (!machine.bindingsAsChild().isEmpty()) {
                BindingCheck check = checkBindings(machine, ref, entity, from, id, depth);
                if (check.detached().isPresent()) {
                    if (log.trace()) {
                        log.trace(depth, "detached " + CallLog.show(ref) + " reason=\"" + check.detached().get()
                                + "\"");
                    }
                    return rejected(machine, ref, from, id, Optional.empty(), depth, check.detached().get(),
                            RejectionKind.DETACHED, Optional.empty(), exitOf);
                }
                holder = check.holder();
                if (holder.isPresent() && log.trace()) {
                    log.trace(depth, "binding " + CallLog.show(ref) + " parent=" + CallLog.show(holder.get().parent())
                            + " node=" + holder.get().binding().spec().state().name());
                }
            }
            Optional<TransitionSpec<E, S, T>> selected = machine.definition().findTransition(from, id);
            if (selected.isEmpty()) {
                if (log.trace()) {
                    log.trace(depth, noEdge(ref, from, id));
                }
                return rejected(machine, ref, from, id, Optional.empty(), depth,
                        machine.definition().policy(from).reasonFor(id), RejectionKind.NO_TRANSITION, Optional.empty(),
                        exitOf);
            }
            TransitionSpec<E, S, T> transition = selected.orElseThrow();
            if (log.trace()) {
                log.trace(depth, resolved(ref, transition, exitOf));
            }
            if (exitOf.isEmpty() && isReservedExit(machine, from, id)) {
                return rejected(machine, ref, from, id, Optional.of(transition.to()), depth,
                        id + " leaves " + from + " only when its sub-machine ends", RejectionKind.RESERVED_EXIT,
                        Optional.empty(), exitOf);
            }
            StepResult<S, T> result;
            uow.push(ref, transition);
            try {
                result = runEdge(machine, ref, entity, from, transition, depth, input, exitOf);
            } finally {
                uow.pop();
            }
            return exits(machine, ref, entity, depth, result, holder);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            if (depth == 0) {
                rootStep = Optional.of(new RootStep(from, selectedEdge(machine, from, id)));
            }
            throw e;
        }
    }

    /**
     * The selected edge of a {@link #step}, with the step's frame pushed: guards, annotate, execute, failure route.
     * The hop is logged as {@link #appendHop} says.
     */
    private <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> runEdge(
            DefaultStateMachine<E, S, T> machine, EntityRef ref, E entity, S from, TransitionSpec<E, S, T> transition,
            int depth, Optional<Object> input, Optional<ExitOf> exitOf) {
        if (depth == 0 && !transition.guards().isEmpty()) {
            phase = CallPhase.GUARD;
        }
        for (GuardRef<E, S, T> guard : transition.guards()) {
            DefaultGuardContext<E, S, T> guardContext =
                    new DefaultGuardContext<>(this, machine, entity, transition, depth, input);
            contexts.add(guardContext);
            GuardResult verdict = evaluateGuard(machine, ref, transition, depth, guard, guardContext);
            if (verdict instanceof GuardResult.Reject reject) {
                return rejected(machine, ref, from, transition.transition(), Optional.of(transition.to()), depth,
                        reject.reason(), RejectionKind.GUARD, Optional.of(guard.name()), exitOf);
            }
        }
        if (depth == 0) {
            phase = CallPhase.ACTION;
        }
        int savepoint = uow.savepoint();
        appendHop(machine, ref, entity, transition, depth);
        if (transition.action().isEmpty()) {
            return new StepResult.Applied<>(from, transition.transition(), transition.to());
        }
        return runAction(machine, ref, entity, transition, depth, savepoint, input);
    }

    /**
     * The exits a finished step calls for (spec R8, R9), run once its frame is popped, so its entity is no longer in
     * flight. Only a step that ends applied, or failed with its route taken, calls for one. Eligibility uses the
     * current pending state: an action may have restarted the entity after its own hop. The original result remains
     * the call's outcome and the diagnostic origin.
     * <ul>
     *   <li>When that state holds a sub-machine in the step's own graph, the step has just entered it, and
     *       {@link #exitEntered} runs the node's exit if the child it started already stands in a terminal state.</li>
     *   <li>Otherwise, when the binding check found the binding that holds the stepping entity and the state is
     *       terminal in the entity's graph, {@link #exitParent} runs the parent's exit.</li>
     * </ul>
     * Each exit is a step of its own, which runs its own exits in turn, so a chain climbs one binding per level.
     * Returns {@code result} itself: the outcome of a call is its own step's.
     */
    private <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> exits(
            DefaultStateMachine<E, S, T> machine, EntityRef ref, E entity, int depth, StepResult<S, T> result,
            Optional<Holder> holder) {
        if (holder.isEmpty() && !machine.bindsSubmachines()) {
            return result;
        }
        S reached;
        T last;
        if (result instanceof StepResult.Applied<S, T> applied) {
            reached = applied.to();
            last = applied.transition();
        } else if (result instanceof StepResult.Failed<S, T> failed) {
            reached = failed.movedTo();
            last = failed.failureTransition();
        } else {
            return result;
        }
        S current = machine.key().states().cast(uow.pendingState(ref).orElseThrow());
        if (machine.bindsSubmachines()) {
            Optional<SubmachineSpec<E, S, T, ?, ?, ?>> bound = machine.definition().submachine(current);
            if (bound.isPresent() && exitEntered(machine, ref, entity, bound.get(), depth, machine, ref,
                    result.from(), last, reached)) {
                return result;
            }
        }
        if (holder.isPresent() && machine.definition().terminals().contains(current)) {
            exitParent(machine, ref, result.from(), last, current, depth, holder.get().binding(),
                    holder.get().parent(), holder.get().logAtCheck());
        }
        return result;
    }

    /**
     * Completes surviving recursively started bindings deepest first, after the entering action and frame. Each
     * containing node and actual child reference comes from the current pending state and its last surviving start,
     * never another lookup. A start has no transition: until a real exit runs, failures describe the original entering
     * step, which {@code origin}, {@code originRef}, {@code from}, {@code last} and {@code reached} retain. The starts
     * one hop logs for its descendants share that hop's depth; every actual exit increases depth and processes its own
     * exits normally. When a descendant's exit enters the node again, the latest start under the parent logged since
     * the selection decides, as in {@link #exitParent}: another child cancels the node's exit, and for the same child
     * the exit runs only if its state, read again, is terminal. The position of the log at the selection marks what
     * came after; starts are never compared by value.
     * Returns whether any exit ran, including an exit that already completed the containing machine.
     */
    private <E, S extends Enum<S>, T extends Enum<T>, P, Q extends Enum<Q>, U extends Enum<U>> boolean exitEntered(
            DefaultStateMachine<E, S, T> machine, EntityRef ref, E entity,
            SubmachineSpec<E, S, T, P, Q, U> spec, int depth, DefaultStateMachine<?, ?, ?> origin,
            EntityRef originRef, Enum<?> from, Enum<?> last, Enum<?> reached) {
        Optional<LogEntry.Start> selected = uow.lastStartUnder(ref);
        if (selected.isEmpty() || selected.get().node() != spec.state()
                || !selected.get().ref().key().equals(spec.childKey())) {
            return false;
        }
        LogEntry.Start start = selected.get();
        int selectedAt = uow.savepoint();
        DefaultStateMachine<P, Q, U> childMachine = registry.machineFor(spec.childKey());
        Q state = spec.childKey().states().cast(uow.pendingState(start.ref()).orElseThrow());
        boolean exited = false;
        if (childMachine.bindsSubmachines()) {
            Optional<SubmachineSpec<P, Q, U, ?, ?, ?>> descendant = childMachine.definition().submachine(state);
            if (descendant.isPresent()) {
                exited = exitEntered(childMachine, start.ref(), spec.childKey().entity().cast(start.ref().instance()),
                        descendant.get(), depth, origin, originRef, from, last, reached);
            }
        }
        Optional<LogEntry.Start> reentry = uow.lastStartUnderSince(ref, selectedAt);
        if (uow.inFlight(ref).isPresent() || uow.pendingState(ref).orElseThrow() != spec.state()
                || (reentry.isPresent() && reentry.get().node() == spec.state()
                        && !reentry.get().ref().equals(start.ref()))) {
            return exited;
        }
        state = spec.childKey().states().cast(uow.pendingState(start.ref()).orElseThrow());
        if (!childMachine.definition().terminals().contains(state)) {
            return exited;
        }
        exit(machine, ref, spec, start.ref(), state, origin, originRef, from, last, reached, depth);
        return true;
    }

    /**
     * Spec R8: the step of a child along {@code last} ended in {@code terminal}, a terminal state of its graph, under
     * {@code binding}, which held it with the parent {@code parentRef}. The parent's exit runs unless the parent is in
     * flight, entering the node or leaving it, or its pending state left the node, or the latest start under the
     * parent logged at {@code logAtCheck} or later, the size of the log when the child's binding check ran, started
     * another child in this node: an action of the child's step may have moved the parent, or entered the node again
     * with a new child.
     * Otherwise the exit retains the binding's node as its source, so a parent moved externally since the binding
     * check conflicts with that source at commit before any state is written. Neither the parent's state nor its
     * childOf is read again: the binding check at the start of the child's step decided which parent holds the child,
     * and only this unit of work's own effects since then can cancel the exit.
     */
    private <A, B extends Enum<B>, C extends Enum<C>, P, Q extends Enum<Q>, U extends Enum<U>> void exitParent(
            DefaultStateMachine<?, ?, ?> machine, EntityRef ref, Enum<?> from, Enum<?> last, Enum<?> terminal,
            int depth, SubmachineBinding<A, B, C, P, Q, U> binding, EntityRef parentRef, int logAtCheck) {
        SubmachineSpec<A, B, C, P, Q, U> spec = binding.spec();
        if (uow.inFlight(parentRef).isPresent()) {
            return;
        }
        Optional<Enum<?>> pending = uow.pendingState(parentRef);
        if (pending.isPresent() && pending.get() != spec.state()) {
            return;
        }
        Optional<LogEntry.Start> reentry = uow.lastStartUnderSince(parentRef, logAtCheck);
        if (reentry.isPresent() && reentry.get().node() == spec.state() && !reentry.get().ref().equals(ref)) {
            return;
        }
        DefaultStateMachine<A, B, C> parentMachine = registry.machineFor(binding.parentKey());
        exit(parentMachine, parentRef, spec, ref, spec.childKey().states().cast(terminal), machine, ref, from, last,
                terminal, depth);
    }

    /**
     * Runs on the parent {@code parentRef} the exit that {@code terminal}, the state the child {@code child} ended in,
     * selects in {@code spec}: a step at the depth after {@code depth}, the depth of the step that ended, in the same
     * unit of work, without input and with the privilege of a reserved exit. A terminal that {@code spec} maps to no
     * exit, which only a hand-written graph can hold, and an exit deeper than maxCascadeDepth abort the step that
     * ended, which {@code machine}, {@code ref}, {@code from}, {@code last} and {@code reached} describe; the exit
     * it selected is logged at TRACE before the depth is checked.
     */
    private <A, B extends Enum<B>, C extends Enum<C>, P, Q extends Enum<Q>, U extends Enum<U>> void exit(
            DefaultStateMachine<A, B, C> parentMachine, EntityRef parentRef, SubmachineSpec<A, B, C, P, Q, U> spec,
            EntityRef child, Q terminal, DefaultStateMachine<?, ?, ?> machine, EntityRef ref, Enum<?> from,
            Enum<?> last, Enum<?> reached, int depth) {
        Optional<C> exit = Optional.ofNullable(spec.exits().get(terminal));
        if (exit.isEmpty()) {
            throw abort(machine, ref, from, Optional.of(last), Optional.of(reached), Optional.empty(), depth,
                    "the terminal " + terminal + " of " + spec.childKey() + " has no exit in " + spec.state(),
                    Optional.empty());
        }
        if (log.trace()) {
            log.trace(depth, "submachine-exit " + CallLog.show(child) + " terminal=" + terminal.name() + " parent="
                    + CallLog.show(parentRef) + " exit=" + exit.get().name());
        }
        if (depth + 1 > uow.maxDepth()) {
            throw poisonIfNested(depth, new CascadeDepthExceededException(call(),
                    details(machine, ref, from, Optional.of(last), Optional.of(reached), Optional.empty()),
                    uow.maxDepth()));
        }
        step(parentMachine, parentMachine.key().entity().cast(parentRef.instance()), exit.get(), depth + 1,
                Optional.empty(), Optional.of(new ExitOf(spec.state(), child)));
    }

    /**
     * The binding check of a child's step (spec R7), for a machine that some graph binds as a sub-machine, once the
     * child's state is read and before its edge is looked up. Each binding, in the order the registry indexed them,
     * asks its parentOf for the child's parent; a parent it finds joins the unit of work with its machine's identity.
     * The binding is active when, besides, the parent's childOf answers this very child and the parent is in the bound
     * node or leaving it, as {@link #holds} says. Returns the active binding with its parent, or, when some binding
     * found a parent and none is active, the reason of the DETACHED rejection, naming the node of each binding that
     * found one, in order. Both are empty for a free child, which no binding found a parent for. Two active bindings
     * abort the step with an {@link AmbiguousParentException} as cause, except one parent that holds the child both
     * in the node its pending state names and leaving another node, on an edge between two nodes that bind the same
     * child: the node it enters, whose entry has just restarted the child, holds it. A lookup that returns null or
     * throws, and a parent or child that cannot be read, abort it too, as {@link #bindingAbort} says. Every lookup runs
     * inside the root, under its reentrancy marker.
     */
    private <E, S extends Enum<S>, T extends Enum<T>> BindingCheck checkBindings(
            DefaultStateMachine<E, S, T> machine, EntityRef ref, E entity, S from, T id, int depth) {
        Optional<Holder> active = Optional.empty();
        Optional<Hold> activeHold = Optional.empty();
        List<Enum<?>> found = new ArrayList<>();
        for (SubmachineBinding<?, ?, ?, ?, ?, ?> binding : machine.bindingsAsChild()) {
            Optional<EntityRef> parent = findParent(machine, ref, entity, from, id, depth, binding);
            if (parent.isEmpty()) {
                continue;
            }
            found.add(binding.spec().state());
            Optional<Hold> hold = holds(machine, ref, from, id, depth, binding, parent.get());
            if (hold.isEmpty()) {
                continue;
            }
            if (active.isPresent()) {
                if (active.get().parent().equals(parent.get()) && activeHold.get() != hold.get()) {
                    if (hold.get() == Hold.IN_NODE) {
                        active = Optional.of(new Holder(binding, parent.get(), uow.savepoint()));
                        activeHold = hold;
                    }
                    continue;
                }
                SubmachineBinding<?, ?, ?, ?, ?, ?> first = active.get().binding();
                AmbiguousParentException ambiguous = new AmbiguousParentException(machine.key(), first.parentKey(),
                        first.spec().state(), binding.parentKey(), binding.spec().state());
                throw bindingAbort(machine, ref, from, id, depth, ambiguous.getMessage(), Optional.of(ambiguous));
            }
            active = Optional.of(new Holder(binding, parent.get(), uow.savepoint()));
            activeHold = hold;
        }
        if (active.isPresent() || found.isEmpty()) {
            return new BindingCheck(active, Optional.empty());
        }
        StringJoiner reason = new StringJoiner(" or ", "no parent holds it in ", "");
        for (Enum<?> node : found) {
            reason.add(node.name());
        }
        return new BindingCheck(Optional.empty(), Optional.of(reason.toString()));
    }

    /**
     * What the binding check found for a child's step: the binding that holds the child, or, when some binding found
     * a parent and none holds the child, the reason of the DETACHED rejection. Both are empty for a free child.
     */
    private record BindingCheck(Optional<Holder> holder, Optional<String> detached) { }

    /**
     * The binding that holds a child for one step, the parent it found, registered in the unit of work, and the size
     * of the log when the binding check ran: what is logged from there on happened during the child's step.
     */
    private record Holder(SubmachineBinding<?, ?, ?, ?, ?, ?> binding, EntityRef parent, int logAtCheck) { }

    /** How a binding holds a child: its parent is in the bound node, or in flight on an edge that leaves it. */
    private enum Hold { IN_NODE, LEAVING }

    /**
     * The parent that {@code binding}'s parentOf answers for {@code entity}, the child, registered in the unit of
     * work with the identity of the parent's machine; empty when it answers none.
     */
    private <A, B extends Enum<B>, C extends Enum<C>, P, Q extends Enum<Q>, U extends Enum<U>> Optional<EntityRef>
            findParent(DefaultStateMachine<?, ?, ?> machine, EntityRef ref, Object entity, Enum<?> from, Enum<?> id,
                       int depth, SubmachineBinding<A, B, C, P, Q, U> binding) {
        String name = binding.parentKey() + "." + binding.spec().state();
        Optional<A> parent;
        try {
            parent = binding.spec().parentOf().apply(binding.spec().childKey().entity().cast(entity));
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            Throwable cause = Unwrap.unwrap(e);
            throw bindingAbort(machine, ref, from, id, depth, "the parentOf of " + name + " threw " + cause,
                    Optional.of(cause));
        }
        if (parent == null) {
            throw bindingAbort(machine, ref, from, id, depth, "the parentOf of " + name + " returned null",
                    Optional.empty());
        }
        if (parent.isEmpty()) {
            return Optional.empty();
        }
        DefaultStateMachine<A, B, C> parentMachine = registry.machineFor(binding.parentKey());
        try {
            return Optional.of(uow.register(parentMachine.refOf(parent.get())));
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            throw bindingAbort(machine, ref, from, id, depth, "checking the binding " + name + " threw " + e,
                    Optional.of(e));
        }
    }

    /**
     * How {@code binding} holds the child {@code ref}, if it does: the childOf of the parent {@code parentRef} answers
     * an entity with the child's identity, which must be the child's own instance, and the parent's state, its pending
     * one first, is the bound node ({@link Hold#IN_NODE}), or the parent is in flight on an edge that leaves the node
     * ({@link Hold#LEAVING}). Another child, or none, is no hold; another instance with the child's identity aborts the
     * step with an {@link AmbiguousIdentityException} as cause, and so does a parent whose state is null, with an
     * {@code UninitializedStateException}.
     */
    private <A, B extends Enum<B>, C extends Enum<C>, P, Q extends Enum<Q>, U extends Enum<U>> Optional<Hold> holds(
            DefaultStateMachine<?, ?, ?> machine, EntityRef ref, Enum<?> from, Enum<?> id, int depth,
            SubmachineBinding<A, B, C, P, Q, U> binding, EntityRef parentRef) {
        SubmachineSpec<A, B, C, P, Q, U> spec = binding.spec();
        String name = binding.parentKey() + "." + spec.state();
        A parent = binding.parentKey().entity().cast(parentRef.instance());
        Optional<P> child;
        try {
            child = spec.childOf().apply(parent);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            Throwable cause = Unwrap.unwrap(e);
            throw bindingAbort(machine, ref, from, id, depth, "the childOf of " + name + " threw " + cause,
                    Optional.of(cause));
        }
        if (child == null) {
            throw bindingAbort(machine, ref, from, id, depth, "the childOf of " + name + " returned null",
                    Optional.empty());
        }
        if (child.isEmpty()) {
            return Optional.empty();
        }
        B state;
        try {
            EntityRef answered = registry.machineFor(spec.childKey()).refOf(child.get());
            if (!answered.equals(ref)) {
                return Optional.empty();
            }
            if (answered.instance() != ref.instance()) {
                throw new AmbiguousIdentityException(ref.key(), ref.id(), ref.instance(), answered.instance());
            }
            state = currentState(registry.machineFor(binding.parentKey()), parentRef, parent);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            throw bindingAbort(machine, ref, from, id, depth, "checking the binding " + name + " threw " + e,
                    Optional.of(e));
        }
        if (state == spec.state()) {
            return Optional.of(Hold.IN_NODE);
        }
        Optional<InFlight> flight = uow.inFlight(parentRef);
        if (flight.isPresent() && flight.get().spec().from() == spec.state()
                && flight.get().spec().to() != spec.state()) {
            return Optional.of(Hold.LEAVING);
        }
        return Optional.empty();
    }

    /**
     * An abort of a child's step from its binding check, before its edge was looked up: in RESOLVE at the root, in
     * ACTION and poisoning when nested. A lookup's throwable that is not an Error is the cause, unwrapped from
     * CompletionException and ExecutionException; what reading the parent or the child threw is the cause as it is.
     */
    private TransitionAbortedException bindingAbort(DefaultStateMachine<?, ?, ?> machine, EntityRef ref, Enum<?> from,
                                                    Enum<?> id, int depth, String message, Optional<Throwable> cause) {
        return abort(machine, ref, from, Optional.of(id), Optional.empty(), Optional.empty(), depth, message, cause);
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
     * holds a sub-machine, the start of that child follows right after it, as {@link #start} says; a machine whose
     * graph binds no sub-machine pays one field read. Both entries sit after the savepoint the caller took, so a
     * rollback to it drops them together.
     */
    private <E, S extends Enum<S>, T extends Enum<T>> void appendHop(DefaultStateMachine<E, S, T> machine,
                                                                     EntityRef ref, E entity,
                                                                     TransitionSpec<E, S, T> edge, int depth) {
        uow.append(new LogEntry.Hop(ref, edge, depth));
        if (machine.bindsSubmachines()) {
            Optional<SubmachineSpec<E, S, T, ?, ?, ?>> binding = machine.definition().submachine(edge.to());
            if (binding.isPresent()) {
                start(machine, ref, edge, depth, machine, ref, entity, binding.get());
            }
        }
    }

    /**
     * Starts the child that {@code spec} binds to the node {@code parent} entered: registers it with its machine's
     * identity, reads where it stands, its pending state first, logs the start at the depth of the entering step, in
     * the unit of work and, for a call that logs at TRACE, in the call's log, and starts the grandchild the same way
     * when the initial state of the child is bound too. A child that cannot be found or read aborts the step that
     * entered the node, which {@code machine}, {@code ref} and {@code edge} describe, before that step's action runs;
     * nested, the abort poisons. An Error propagates.
     */
    private <E, S extends Enum<S>, T extends Enum<T>, P, Q extends Enum<Q>, U extends Enum<U>> void start(
            DefaultStateMachine<?, ?, ?> machine, EntityRef ref, TransitionSpec<?, ?, ?> edge, int depth,
            DefaultStateMachine<E, S, T> parentMachine, EntityRef parentRef, E parent,
            SubmachineSpec<E, S, T, P, Q, U> spec) {
        DefaultStateMachine<P, Q, U> childMachine = registry.machineFor(spec.childKey());
        P child = childOf(machine, ref, edge, depth, parentMachine, parent, spec);
        EntityRef childRef;
        Q previous;
        try {
            childRef = uow.register(childMachine.refOf(child));
            previous = currentState(childMachine, childRef, child);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            throw entryAbort(machine, ref, edge, depth,
                    "reading the sub-machine of " + parentMachine.key() + "." + spec.state() + " threw " + e,
                    Optional.of(e));
        }
        Q initial = childMachine.definition().initial();
        uow.appendStart(new LogEntry.Start(childRef, previous, initial, parentRef, spec.state(), depth));
        if (log.trace()) {
            log.trace(depth, "submachine-start " + CallLog.show(childRef) + " " + previous.name() + " -> "
                    + initial.name() + " parent=" + CallLog.show(parentRef) + " node=" + spec.state().name());
        }
        if (childMachine.bindsSubmachines()) {
            Optional<SubmachineSpec<P, Q, U, ?, ?, ?>> grandchild = childMachine.definition().submachine(initial);
            if (grandchild.isPresent()) {
                start(machine, ref, edge, depth, childMachine, childRef, child, grandchild.get());
            }
        }
    }

    /**
     * The child that {@code spec}'s childOf returns for {@code parent}. An empty answer, a null one, which breaks the
     * lookup's contract, and a throwable that is not an Error, unwrapped from CompletionException and
     * ExecutionException, each abort the entering step.
     */
    private <E, S extends Enum<S>, T extends Enum<T>, P> P childOf(
            DefaultStateMachine<?, ?, ?> machine, EntityRef ref, TransitionSpec<?, ?, ?> edge, int depth,
            DefaultStateMachine<E, S, T> parentMachine, E parent, SubmachineSpec<E, S, T, P, ?, ?> spec) {
        String binding = parentMachine.key() + "." + spec.state();
        Optional<P> child;
        try {
            child = spec.childOf().apply(parent);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable e) {
            Throwable cause = Unwrap.unwrap(e);
            throw entryAbort(machine, ref, edge, depth, "the childOf of " + binding + " threw " + cause,
                    Optional.of(cause));
        }
        if (child == null) {
            throw entryAbort(machine, ref, edge, depth, "the childOf of " + binding + " returned null",
                    Optional.empty());
        }
        if (child.isEmpty()) {
            throw entryAbort(machine, ref, edge, depth,
                    parentMachine.key() + " entered " + spec.state() + " with no " + spec.childKey(), Optional.empty());
        }
        return child.get();
    }

    /** An abort of the step that entered a bound node along {@code edge}, before its action ran. */
    private TransitionAbortedException entryAbort(DefaultStateMachine<?, ?, ?> machine, EntityRef ref,
                                                  TransitionSpec<?, ?, ?> edge, int depth, String message,
                                                  Optional<Throwable> cause) {
        return abort(machine, ref, edge.from(), Optional.of(edge.transition()), Optional.of(edge.to()),
                Optional.empty(), depth, message, cause);
    }

    private <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> runAction(
            DefaultStateMachine<E, S, T> machine, EntityRef ref, E entity, TransitionSpec<E, S, T> transition,
            int depth, int savepoint, Optional<Object> input) {
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
            if (RecoverySelector.admits(transition, cause)) {
                // A poisoned unit of work never commits, and every route commits: the poison is thrown before a route
                // is chosen and before the reason reads the throwable's message, so no recovery action can run, as
                // when the action returns.
                if (uow.isPoisoned()) {
                    throw uow.poison().orElseThrow();
                }
                RecoverySelector.Route<T> selected = RecoverySelector.thrown(transition, cause);
                return failureRoute(machine, ref, entity, transition, depth, savepoint, selected.recovery(),
                        selected.failureKind(), describe(cause), Optional.of(cause), input);
            }
            throw abort(machine, ref, transition.from(), Optional.of(transition.transition()),
                    Optional.of(transition.to()), Optional.of(action.name()), depth,
                    "the action " + action.name() + " threw " + cause, Optional.of(cause));
        } catch (CallFailedException e) {
            uow.journalStatus(journalIndex, ActionStatus.THREW);
            // Reaching here: a null stage or outcome, a wait that timed out, was interrupted or was refused, or a
            // CallFailedException that a hand-written stage's handle throws.
            throw poisonOr(e);
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
     * What the engine throws for {@code own}: the failure that poisoned the transaction when the transaction is
     * poisoned, or {@code own} itself. The engine passes a failure of this call that a guard or an action threw or
     * completed its stage with, and any CallFailedException that ends an action step without coming from the action's
     * throws or completions: its own abort for a null outcome or stage or for a wait that timed out, was interrupted
     * or was refused, or one that a hand-written stage's handle throws, of this call or of another.
     */
    private StateMachineException poisonOr(CallFailedException own) {
        return uow.isPoisoned() ? uow.poison().orElseThrow() : own;
    }

    /**
     * What the engine throws for a guard's exception: a failure of this call as {@link #poisonOr} says; anything else,
     * a library exception of another call included, an abort of the call in its current phase with it as cause.
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
     * Runs the action as {@link #invokeAction} says. A call that logs at TRACE logs its start and how it ended, with
     * the time it took: the outcome it returned, or the class of what ended it, the throwable the action threw or
     * completed its stage with, or the engine's own abort. The line names that class alone and never reads the
     * throwable's message, so the engine reads it, if at all, when it would without the log, after the poison check
     * for a throwable a declared type accepts. An Error logs no end.
     */
    private <E, S extends Enum<S>, T extends Enum<T>> ActionOutcome executeAction(
            DefaultStateMachine<E, S, T> machine, EntityRef ref, TransitionSpec<E, S, T> transition,
            ActionRef<E, S, T> action, DefaultTransitionContext<E, S, T> ctx) {
        if (!log.trace()) {
            return invokeAction(machine, ref, transition, action, ctx);
        }
        String name = "action " + action.name();
        log.trace(ctx.depth(), name + " start mode=" + (action instanceof ActionRef.Async ? "async" : "sync"));
        long started = System.nanoTime();
        ActionOutcome outcome;
        try {
            outcome = invokeAction(machine, ref, transition, action, ctx);
        } catch (RuntimeException thrown) {
            Throwable cause = thrown instanceof ActionFailure failure ? failure.getCause() : thrown;
            log.trace(ctx.depth(), name + " THREW error=" + cause.getClass().getName() + " elapsed="
                    + CallLog.micros(System.nanoTime() - started));
            throw thrown;
        }
        String end = outcome instanceof ActionOutcome.Failed failed
                ? " FAILED" + (failed.failureKind().isPresent() ? " kind=" + failed.failureKind().get().getSimpleName()
                        : "") + " reason=\"" + failed.reason() + "\""
                : " OK";
        log.trace(ctx.depth(), name + end + " elapsed=" + CallLog.micros(System.nanoTime() - started));
        return outcome;
    }

    /**
     * Sync actions run inline; async ones return a stage that is awaited under the edge's deadline. Whatever the action
     * throws, an Error aside, or completes its stage with, checked exceptions included, which Kotlin or
     * {@code @SneakyThrows} code can throw without declaring them, and library exceptions too, becomes an ActionFailure
     * that its caller classifies. An Error is never routed: thrown as itself, it propagates; wrapped in
     * CompletionException or ExecutionException, it is unwrapped and aborts. Exceptional stage completions hand the
     * throwable they completed with to ActionFailure; the engine reads no message before classification, though the JDK
     * may already have read it, as when {@code supplyAsync} wrapped the throwable in a CompletionException. Before it
     * waits for a stage, a call that logs at TRACE logs the budget the wait has left: none when neither the edge nor
     * the call sets a limit.
     */
    private <E, S extends Enum<S>, T extends Enum<T>> ActionOutcome invokeAction(
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
                long deadline = edgeDeadline(edgeBudget);
                if (log.trace()) {
                    log.trace(ctx.depth(), "action " + action.name() + " awaiting budget=" + (deadline == Long.MAX_VALUE
                            ? "none" : CallLog.micros(Math.max(0L, deadline - System.nanoTime()))));
                }
                Optional<ActionOutcome> result = await(machine, ref, ctx.from(), Optional.of(ctx.transition()),
                        Optional.of(ctx.to()), Optional.of(action.name()), ctx.depth(), "the action " + action.name(),
                        stage, deadline);
                if (result.isEmpty()) {
                    throw abort(machine, ref, ctx.from(), Optional.of(ctx.transition()), Optional.of(ctx.to()),
                            Optional.of(action.name()), ctx.depth(), "the action " + action.name() + " returned null",
                            Optional.empty());
                }
                yield result.orElseThrow();
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
     * Sync guards run inline; async ones return a stage that is awaited under the transaction's deadline. For whatever
     * the guard throws or completes its stage with, an Error inside the stage included, the engine throws what
     * {@link #guardFailure} says: a library exception of another call is a cause like any other. An Error thrown as
     * itself propagates. A verdict is logged at TRACE with the time the guard took; a guard that throws logs none.
     */
    private <E, S extends Enum<S>, T extends Enum<T>> GuardResult evaluateGuard(
            DefaultStateMachine<E, S, T> machine, EntityRef ref, TransitionSpec<E, S, T> transition, int depth,
            GuardRef<E, S, T> guard, DefaultGuardContext<E, S, T> ctx) {
        long started = log.trace() ? System.nanoTime() : 0L;
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
                    Optional<GuardResult> result = await(machine, ref, transition.from(), id, to, Optional.empty(),
                            depth, "the guard " + guard.name(), stage, uow.deadlineNanos());
                    if (result.isEmpty()) {
                        throw abort(machine, ref, transition.from(), id, to, Optional.empty(), depth,
                                "the guard " + guard.name() + " returned null", Optional.empty());
                    }
                    yield result.orElseThrow();
                } catch (ActionFailure failure) {
                    throw guardFailure(machine, ref, transition, depth, guard, "failed:", failure.getCause());
                }
            }
        };
        if (verdict == null) {
            throw abort(machine, ref, transition.from(), id, to, Optional.empty(), depth,
                    "the guard " + guard.name() + " returned null", Optional.empty());
        }
        if (log.trace()) {
            String said = verdict instanceof GuardResult.Reject reject
                    ? " REJECT reason=\"" + reject.reason() + "\"" : " PASS";
            log.trace(depth, "guard " + guard.name() + said + " elapsed="
                    + CallLog.micros(System.nanoTime() - started));
        }
        return verdict;
    }

    /**
     * Waits under the deadline. Timeout, interruption and the refusal of the thread's ForkJoinPool to let it block
     * are aborts, each caused by what ended the wait; the user's stage is left running.
     */
    private <R> Optional<R> await(DefaultStateMachine<?, ?, ?> machine, EntityRef ref, Enum<?> from,
                                  Optional<Enum<?>> id, Optional<Enum<?>> to, Optional<String> action, int depth,
                                  String what, CompletionStage<R> stage, long deadlineNanos) {
        try {
            return Await.completedOrAwait(stage, deadlineNanos);
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
     * {@code recovery} leaves the failure unhandled. The result carries {@code failureKind} as it is. A call that logs
     * at TRACE logs the route, or the unhandled failure, once the rollback is done.
     */
    private <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> failureRoute(
            DefaultStateMachine<E, S, T> machine, EntityRef ref, E entity, TransitionSpec<E, S, T> failing, int depth,
            int savepoint, Optional<T> recovery, Optional<Class<? extends FailureKind>> failureKind, String reason,
            Optional<Throwable> cause, Optional<Object> input) {
        uow.rollbackTo(savepoint);
        S from = failing.from();
        Optional<String> actionName = failing.action().map(ActionRef::name);
        if (recovery.isEmpty()) {
            if (log.trace()) {
                log.trace(depth, "unhandled " + failing.transition().name() + " reason=\"" + reason + "\"");
            }
            AbortDetails details = details(machine, ref, from, Optional.of(failing.transition()),
                    Optional.of(failing.to()), actionName);
            UnhandledFailureException unhandled = cause.isPresent()
                    ? new UnhandledFailureException(call(), details, reason, cause.get())
                    : new UnhandledFailureException(call(), details, reason);
            throw poisonIfNested(depth, unhandled);
        }
        T recoveryId = recovery.orElseThrow();
        if (log.trace()) {
            log.trace(depth, "route " + failing.transition().name() + " -> " + recoveryId.name() + " rollback-to="
                    + savepoint + (failureKind.isPresent() ? " kind=" + failureKind.get().getSimpleName() : "")
                    + " reason=\"" + reason + "\"");
        }
        TransitionSpec<E, S, T> route = machine.definition().findTransition(from, recoveryId).orElseThrow();
        // The failed action has ended. Keep one frame for the entity, now identifying the recovery edge.
        uow.pop();
        uow.push(ref, route);
        appendHop(machine, ref, entity, route, depth);
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
                // Reaching here: what reaches the same catch in runAction.
                throw poisonOr(e);
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

    /**
     * A rejection: at the root, the step's result; nested, an abort that poisons, whose message says whether it
     * happened inside a cascade or inside the exit of the node {@code exitOf} names.
     */
    private <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> rejected(
            DefaultStateMachine<E, S, T> machine, EntityRef ref, S from, T id, Optional<S> to, int depth, String reason,
            RejectionKind kind, Optional<String> guard, Optional<ExitOf> exitOf) {
        if (depth == 0) {
            return new StepResult.Rejected<>(from, id, reason, kind, guard);
        }
        String who = switch (kind) {
            case NO_TRANSITION -> "no such transition";
            case GUARD -> "guard " + guard.orElseThrow();
            case DETACHED -> "detached sub-machine";
            case RESERVED_EXIT -> "reserved exit";
        };
        String inside = exitOf.isPresent() ? "the exit of " + exitOf.get().node() : "a cascade";
        throw abort(machine, ref, from, Optional.of(id), to.map((S state) -> (Enum<?>) state), Optional.empty(), depth,
                "rejected inside " + inside + " by " + who + ": " + reason, Optional.empty());
    }

    private <E, S extends Enum<S>, T extends Enum<T>> S currentState(DefaultStateMachine<E, S, T> machine,
                                                                      EntityRef ref, E entity) {
        Optional<Enum<?>> pending = uow.pendingState(ref);
        if (pending.isPresent()) {
            return machine.key().states().cast(pending.get());
        }
        return machine.readState(entity);
    }

    /**
     * The reason a routed throwable reports, whichever declared type admitted it: the throwable's simple name and its
     * message. An anonymous class has an empty simple name, so its binary name stands in: a reason is never blank.
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
     * reaches the abort. The abort names what the root's own step recorded when it threw: the state it read, and the
     * edge it selected with that edge's identifier and destination, or else the identifier the call requested.
     */
    CallFailedException classify(Throwable error) {
        if (error instanceof CallFailedException failure && owns(failure)) {
            return failure;
        }
        uow.markRunningAsThrew();
        Optional<Enum<?>> from = rootStep.map(RootStep::from);
        Optional<? extends TransitionSpec<?, ?, ?>> edge =
                rootStep.isPresent() ? rootStep.get().edge() : Optional.empty();
        Optional<Enum<?>> transition = edge.isPresent() ? Optional.of(edge.get().transition()) : requested;
        Optional<Enum<?>> to = edge.isPresent() ? Optional.of(edge.get().to()) : Optional.empty();
        AbortDetails details = new AbortDetails(callKey, callEntity, from, transition, to, Optional.empty(),
                uow.path(), uow.journal());
        return new TransitionAbortedException(call(), phase, details, "the engine could not go on: " + error, error);
    }

    /** At depth greater than zero, the failure poisons the UoW: the parent cannot go on as if nothing happened. */
    private <X extends StateMachineException> X poisonIfNested(int depth, X error) {
        if (depth > 0) {
            tracePoison(depth, error);
            uow.poison(error);
        }
        return error;
    }

    /**
     * Logs at TRACE, at {@code depth}, the failure that is about to poison the unit of work, unless an earlier one
     * already did: a transaction keeps its first poison.
     */
    private void tracePoison(int depth, StateMachineException error) {
        if (log.trace() && !uow.isPoisoned()) {
            log.trace(depth, "poison error=" + CallLog.show(error));
        }
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
        return cascade(caller, key, peer, id, Optional.empty());
    }

    /**
     * {@link #cascade(ContextHandle, MachineKey, Object, Enum)}, or one element of a FAIL_FAST cascadeAll, whose
     * {@code policy} the element's TRACE line names; it is empty for a single cascade and when the call does not log
     * at TRACE.
     */
    private <P, Q extends Enum<Q>, U extends Enum<U>> Outcome<P, Q, U> cascade(
            ContextHandle caller, MachineKey<P, Q, U> key, P peer, U id, Optional<CascadePolicy> policy) {
        checkPoison(caller);
        requireTransition(key, id);
        try {
            return cascadeOne(caller, key, peer, id, policy);
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
            tracePoison(caller.contextDepth(), own);
            uow.poison(own);
            return own;
        }
        TransitionAbortedException wrapper = new TransitionAbortedException(call(), phase, details(caller),
                "the cascade to " + target + " failed: " + error, error);
        tracePoison(caller.contextDepth(), wrapper);
        uow.poisonWrapping(wrapper);
        return wrapper;
    }

    /**
     * Order of checks: identity, re-entry, depth, and only then the step, as {@link #cascadeTo} says. {@code policy}
     * is the policy of the cascadeAll that runs this element, for its TRACE line, as {@link #cascadeTo} says.
     */
    private <P, Q extends Enum<Q>, U extends Enum<U>> Outcome<P, Q, U> cascadeOne(
            ContextHandle caller, MachineKey<P, Q, U> key, P peer, U id, Optional<CascadePolicy> policy) {
        DefaultStateMachine<P, Q, U> machine = registry.machineFor(key);
        machine.requireTransition(id);
        return cascadeTo(caller, machine, uow.register(machine.refOf(peer)), peer, id, policy);
    }

    /**
     * The cascade to {@code peer}, once its identity is registered as {@code ref}: re-entry, depth, and only then the
     * step. There is no convergent no-op: a request that resolves no edge is rejected, and a request on an entity
     * already in flight is a cycle. A call that logs at TRACE logs the cascade before the checks and its result once
     * the step returns, both at the depth of the step that asked for it; {@code policy} is the policy of the
     * cascadeAll that runs this element, which the first line names, empty for a single cascade and when the call
     * does not log at TRACE.
     */
    private <P, Q extends Enum<Q>, U extends Enum<U>> Outcome<P, Q, U> cascadeTo(
            ContextHandle caller, DefaultStateMachine<P, Q, U> machine, EntityRef ref, P peer, U id,
            Optional<CascadePolicy> policy) {
        int depth = caller.contextDepth() + 1;
        if (log.trace()) {
            log.trace(caller.contextDepth(), "cascade " + CallLog.show(ref) + " " + id.name()
                    + (policy.isPresent() ? " policy=" + policy.get().name() : ""));
        }
        Optional<InFlight> flight = uow.inFlight(ref);
        if (flight.isPresent()) {
            throw cascadeCycle(caller, flight.orElseThrow(), id);
        }
        if (depth > uow.maxDepth()) {
            throw new CascadeDepthExceededException(call(), details(caller), uow.maxDepth());
        }
        Outcome<P, Q, U> outcome = outcomeOf(peer, transition(machine, peer, id, depth, Optional.empty()));
        if (log.trace()) {
            log.trace(caller.contextDepth(), "cascade-result " + CallLog.show(ref) + " " + id.name() + " code="
                    + outcome.code().name());
        }
        return outcome;
    }

    /** The cascade path plus the entity that was re-entered, with the identifier in flight and the one requested. */
    private CascadeCycleException cascadeCycle(ContextHandle caller, InFlight flight, Enum<?> requested) {
        List<String> route = new ArrayList<>(uow.path());
        route.add(flight.ref().toString());
        return new CascadeCycleException(call(), details(caller), route, flight.transition(), requested);
    }

    /**
     * Adapts a nested step to an outcome: records, reactions, starts and the flow belong to the root, so the lists stay
     * empty here and the flow is {@link FlowView#empty()}, which the constructors without it give.
     */
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

    /**
     * The rest of cascadeAll once its elements are accepted: the identifier, the machine, then every element. A call
     * that logs at TRACE logs each COLLECT element it rolls back, once the identity of the element was registered,
     * with the abort that ended its step.
     */
    private <P, Q extends Enum<Q>, U extends Enum<U>> List<Outcome<P, Q, U>> cascadeEach(
            ContextHandle caller, MachineKey<P, Q, U> key, List<P> elements, U id, CascadePolicy policy) {
        requireTransition(key, id);
        requireMachine(caller, key);
        List<Outcome<P, Q, U>> outcomes = new ArrayList<>();
        List<TransitionAbortedException> errors = new ArrayList<>();
        // Only the TRACE lines read the policy and the element's reference: a call that does not log at TRACE keeps
        // neither, and allocates nothing for them.
        Optional<CascadePolicy> traced = log.trace() ? Optional.of(policy) : Optional.empty();
        for (P peer : elements) {
            if (policy == CascadePolicy.FAIL_FAST) {
                outcomes.add(cascade(caller, key, peer, id, traced));
                continue;
            }
            int savepoint = uow.savepoint();
            Optional<EntityRef> target = Optional.empty();
            try {
                DefaultStateMachine<P, Q, U> machine = registry.machineFor(key);
                EntityRef ref = uow.register(machine.refOf(peer));
                if (log.trace()) {
                    target = Optional.of(ref);
                }
                outcomes.add(cascadeTo(caller, machine, ref, peer, id, traced));
            } catch (Throwable error) {
                if (error instanceof TransitionAbortedException abort && owns(abort)
                        && !uow.poisonWrapsAForeignFailure()) {
                    uow.rollbackTo(savepoint);
                    uow.clearPoison();
                    if (target.isPresent()) {
                        log.trace(caller.contextDepth(), "collect-rollback " + CallLog.show(target.get()) + " "
                                + id.name() + " error=" + CallLog.show(abort));
                    }
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
            tracePoison(caller.contextDepth(), aggregate);
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
