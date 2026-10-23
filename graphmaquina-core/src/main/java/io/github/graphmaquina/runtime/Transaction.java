package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.Machine;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.error.AbortDetails;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.PoisonedUnitOfWorkException;
import io.github.graphmaquina.error.StateMachineException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.internal.Interrupts;
import io.github.graphmaquina.internal.Texts;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Engine of a root transaction: the state of its call, which lives here and in the UnitOfWork, the phase of the call
 * and its log included, the call's entry points, and its aborts, poison and classification. The engine's work on that
 * state takes the transaction as its first argument: a step as {@link Steps} runs it, the sub-machines as
 * {@link Submachines} does, the actions as {@link Actions}, the failure routes as {@link FailureRoutes}, the peer reads
 * and cascades as {@link Cascades}, and the flow an outcome or a failure hands out as {@link OutcomeFlows}. Wherever
 * the engine contains what application code threw on the calling thread, through a failure route, an abort or a
 * poison, an InterruptedException interrupts the thread again, as {@link Interrupts} says; one that completes a stage
 * leaves the thread as it is.
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
    /**
     * The state the root's own step read, its identifier and the destination of the edge it selected, recorded when
     * that step throws or when the clock cannot date its rejection; empty before.
     */
    private Optional<RootStep> rootStep = Optional.empty();
    /** The log of the call: {@link CallLog#OFF} when the call found both levels off. */
    private final CallLog log;
    /**
     * Set by the first statement of the catch of every cascade, with a store, which needs neither stack nor memory: a
     * cascade of the call failed, and what it failed with may not be the poison yet. {@link #settle} records it.
     * COLLECT clears it, with the poison, when it collects the abort of an element. Package-private, so that the
     * catches of {@link Cascades} store into it as {@link #cascadeEach} does, never through a call.
     */
    boolean condemned;

    Transaction(MachineRegistry registry, UnitOfWork uow, MachineKey<?, ?, ?> callKey, Object callEntity,
                Optional<Enum<?>> requested, boolean reaction, Optional<MachineCall> call, CallLog log) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.uow = Objects.requireNonNull(uow, "uow");
        this.callKey = Objects.requireNonNull(callKey, "callKey");
        this.callEntity = Objects.requireNonNull(callEntity, "callEntity");
        this.requested = Objects.requireNonNull(requested, "requested");
        this.reaction = reaction;
        this.call = Objects.requireNonNull(call, "call");
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * The transaction of no call, which a context of the machine with the key {@code key} holds in place of the
     * transaction of its call once invalidated, so that a context kept after its call keeps neither that call nor its
     * unit of work reachable. Nothing uses it: an invalidated context refuses every operation before it reaches its
     * transaction. Its unit of work is its own, and empty, and {@code key} stands in for its entity.
     */
    static Transaction released(MachineRegistry registry, MachineKey<?, ?, ?> key) {
        return new Transaction(registry, new UnitOfWork(0, 0L), key, key, Optional.empty(), false, Optional.empty(),
                CallLog.OFF);
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

    /** The registry the call runs in: its root machine's. */
    MachineRegistry registry() { return registry; }

    /** Where the call stands now. */
    CallPhase phase() { return phase; }

    /** The key of the root call's machine. */
    MachineKey<?, ?, ?> callKey() { return callKey; }

    /** The entity of the root call. */
    Object callEntity() { return callEntity; }

    /** Keeps {@code context}, a context the call handed out, for {@link #invalidate}. */
    void track(ContextHandle context) {
        contexts.add(context);
    }

    /**
     * Records what the root's own step had read and selected for {@link #classify}: the state {@code from}, the
     * identifier {@code id} and the destination {@code to} of the edge it selected, empty when it selected none.
     */
    void recordRootStep(Enum<?> from, Enum<?> id, Optional<Enum<?>> to) {
        rootStep = Optional.of(new RootStep(from, id, to));
    }

    /**
     * A handle on the call's flow for a context of this call at {@code depth}, which reaches the call's unit of work
     * through {@code unit}, that context's own check. It does not create the flow: the first write of the call does,
     * with the entity of the root call, unless the call records its trail, whose flow exists from its start.
     */
    FlowHandle flow(Supplier<UnitOfWork> unit, int depth) {
        return FlowHandle.of(unit, callEntity, log, depth);
    }

    /**
     * A handle on the call's flow for the post-commit hook named {@code hook}, which runs for the committed hop
     * {@code hop}. It does not create the flow either: its first write does, if no step of the call wrote and the
     * call records no trail.
     */
    FlowHandle.Hook hookFlow(String hook, EntityRef hop) {
        return FlowHandle.hook(uow, callEntity, log, hook, hop);
    }

    void enter(CallPhase next) { phase = next; }

    /** Phase 2 is about to call a setter: from here a failure in COMMIT may leave the states partially written. */
    void writing() { writing = true; }

    /**
     * How far the call got in writing states: none before the commit, all after it, and during it what the setters
     * did.
     */
    StatesWritten statesWritten() {
        // Not a switch: on another class's enum, javac compiles one to a synthetic class with a static initializer,
        // which assembling a registry cannot name to initialise, as MachineRegistry.staticallyInitialized says.
        if (phase == CallPhase.COMMIT) {
            return writing ? StatesWritten.PARTIAL : StatesWritten.NONE;
        }
        if (phase == CallPhase.HOOK || phase == CallPhase.REACTIONS) {
            return StatesWritten.ALL;
        }
        return StatesWritten.NONE;
    }

    <P, Q extends Enum<Q>, U extends Enum<U>> MachineKey<P, Q, U> keyFor(Class<? extends Machine<P, Q, U>> facade) {
        return registry.keyFor(facade);
    }

    void invalidate() {
        for (ContextHandle context : contexts) {
            context.invalidate();
        }
    }

    /** For reevaluate, which has no per-call input to carry. */
    <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> transition(DefaultStateMachine<E, S, T> machine,
                                                                          E entity, T id, int depth) {
        return transition(machine, entity, id, depth, Optional.empty());
    }

    /**
     * A step of the call at {@code depth}: the root's own step, which execute requests or a reevaluation's watch
     * selects, as {@link Steps#transition} runs it; a cascade calls Steps.transition directly. {@code input} is the
     * operation input of the call, empty when it has none; the contexts hand it out as it is.
     */
    <E, S extends Enum<S>, T extends Enum<T>> StepResult<S, T> transition(DefaultStateMachine<E, S, T> machine,
                                                                          E entity, T id, int depth,
                                                                          Optional<Object> input) {
        return Steps.transition(this, machine, entity, id, depth, input);
    }

    /**
     * What the root's own step had read and selected when it threw or when the clock could not date its rejection: the
     * state it read, the identifier it resolved, and the destination of the edge it selected, empty when it selected
     * none.
     */
    private record RootStep(Enum<?> from, Enum<?> id, Optional<Enum<?>> to) { }

    TransitionAbortedException abort(DefaultStateMachine<?, ?, ?> machine, EntityRef ref, Enum<?> from,
                                     Optional<Enum<?>> id, Optional<Enum<?>> to, Optional<String> action,
                                     int depth, String message, Optional<Throwable> cause) {
        return poisonIfNested(depth, unpoisonedAbort(machine, ref, from, id, to, action, message, cause));
    }

    /**
     * {@code abort}, which the engine built for a step that took a failure route, holding as suppressed
     * {@code original}, the throwable that selected the route, when there is one and it is not the abort's own cause.
     * Adding it reads no message.
     */
    static <X extends TransitionAbortedException> X keepingOriginal(X abort, Optional<Throwable> original) {
        if (original.isPresent() && original.get() != abort.getCause()) {
            abort.addSuppressed(original.get());
        }
        return abort;
    }

    /** {@link #abort} that poisons nothing yet: an EngineAbort carries it to where the call is poisoned with it. */
    TransitionAbortedException unpoisonedAbort(DefaultStateMachine<?, ?, ?> machine, EntityRef ref, Enum<?> from,
                                               Optional<Enum<?>> id, Optional<Enum<?>> to, Optional<String> action,
                                               String message, Optional<Throwable> cause) {
        AbortDetails details = details(machine, ref, from, id, to, action);
        return cause.isPresent()
                ? new TransitionAbortedException(call(), phase, details, message, cause.get())
                : new TransitionAbortedException(call(), phase, details, message);
    }

    /**
     * The root entity's identity or state could not be read: the call aborts in RESOLVE before this step read the
     * entity's state.
     */
    TransitionAbortedException unread(DefaultStateMachine<?, ?, ?> machine, Object entity, Enum<?> id,
                                      Throwable error) {
        Interrupts.keep(error);
        Throwable cause = Unwrap.unwrap(error);
        AbortDetails details = new AbortDetails(machine.key(), entity, Optional.empty(), Optional.of(id),
                Optional.empty(), Optional.empty(), uow.path(), uow.journal());
        return new TransitionAbortedException(call(), CallPhase.RESOLVE, details,
                "reading the entity threw " + Texts.shown(cause), cause);
    }

    /**
     * The exception the call throws for {@code error}: a failure of this call as it is; anything else, which only
     * engine code that calls into the application can let through before the commit, an identity's hashCode while
     * the log is rolled back for instance, an abort of the call in its current phase with it as cause. The commit,
     * the hooks and the reactions classify every failure of their own where it happens, so a phase after ACTION never
     * reaches the abort. The abort names what the root's own step recorded when it threw, or when the clock could
     * not date its rejection: the state it read, the identifier it resolved and the destination of the edge it
     * selected; before the step read its state, it names the identifier the call requested.
     */
    CallFailedException classify(Throwable error) {
        if (error instanceof CallFailedException failure && owns(failure)) {
            return failure;
        }
        Interrupts.keep(error);
        uow.markRunningAsThrew();
        Optional<Enum<?>> from = rootStep.map(RootStep::from);
        Optional<Enum<?>> transition = rootStep.isPresent() ? Optional.of(rootStep.get().id()) : requested;
        Optional<Enum<?>> to = rootStep.isPresent() ? rootStep.get().to() : Optional.empty();
        AbortDetails details = new AbortDetails(callKey, callEntity, from, transition, to, Optional.empty(),
                uow.path(), uow.journal());
        return new TransitionAbortedException(call(), phase, details,
                "the engine could not go on: " + Texts.shown(error), error);
    }

    /** At depth greater than zero, the failure poisons the UoW: the parent cannot go on as if nothing happened. */
    <X extends StateMachineException> X poisonIfNested(int depth, X error) {
        if (depth > 0) {
            poisonAt(depth, error);
        }
        return error;
    }

    /**
     * Stores the first poison before its TRACE line: a backend Error cannot interrupt that bookkeeping.
     */
    void poisonAt(int depth, StateMachineException error) {
        boolean first = !uow.isPoisoned();
        uow.poison(error);
        if (first) {
            tracePoison(depth, error);
        }
    }

    /**
     * Logs at TRACE, at {@code depth}, the first poison already stored in the unit of work.
     */
    void tracePoison(int depth, StateMachineException error) {
        if (log.trace()) {
            log.trace(depth, "poison error=" + CallLog.show(error));
        }
    }

    /**
     * What the engine throws for {@code own}: the failure that poisoned the transaction when the transaction is
     * poisoned, or {@code own} itself. The engine passes a failure of this call that a guard or an action threw or
     * completed its stage with, and any CallFailedException that ends an action step without coming from the action's
     * throws or completions: its own abort for a null outcome or stage or for a wait that timed out, was interrupted
     * or was refused, or one that a hand-written stage's handle throws, of this call or of another.
     */
    StateMachineException poisonOr(CallFailedException own) {
        return uow.isPoisoned() ? uow.poison().orElseThrow() : own;
    }

    AbortDetails details(DefaultStateMachine<?, ?, ?> machine, EntityRef ref, Enum<?> from, Optional<Enum<?>> id,
                         Optional<Enum<?>> to, Optional<String> action) {
        uow.markRunningAsThrew();
        return new AbortDetails(machine.key(), ref.instance(), Optional.of(from), id, to, action, uow.path(),
                uow.journal());
    }

    AbortDetails details(ContextHandle caller) {
        uow.markRunningAsThrew();
        return new AbortDetails(caller.machineKey(), caller.entityInstance(),
                Optional.of(caller.fromState()), Optional.of(caller.requestedTransition()),
                Optional.of(caller.toState()), Optional.empty(), uow.path(), uow.journal());
    }

    /**
     * {@link #details(ContextHandle)} for when printing what was in flight threw an Error: the path names each
     * entity's machine alone, so building it prints no identity.
     */
    AbortDetails bareDetails(ContextHandle caller) {
        uow.markRunningAsThrew();
        return new AbortDetails(caller.machineKey(), caller.entityInstance(),
                Optional.of(caller.fromState()), Optional.of(caller.requestedTransition()),
                Optional.of(caller.toState()), Optional.empty(), uow.machinePath(), uow.journal());
    }

    /**
     * Resolves the facade a cascade names: an unknown facade poisons, as an unknown key does once the cascade looks its
     * machine up, and the action receives the abort that carries it. The catch condemns the call first, as a cascade's
     * does, so the call stays poisoned when an Error strikes while that poison is recorded. A VirtualMachineError that
     * strikes while the facade resolves poisons nothing: the identifier is checked next, so the cascade has not
     * accepted its arguments yet.
     */
    <P, Q extends Enum<Q>, U extends Enum<U>> MachineKey<P, Q, U> cascadeKeyFor(
            ContextHandle caller, Class<? extends Machine<P, Q, U>> facade) {
        Cascades.checkCascade(this, caller);
        try {
            return keyFor(facade);
        } catch (StateMachineException e) {
            // First a store, as in Cascades.cascade: the call stays condemned if an Error strikes while its poison is
            // recorded.
            condemned = true;
            throw Cascades.poisonWith(this, caller, facade.getSimpleName(), e);
        }
    }

    /**
     * Refuses an identifier from another machine's catalogue, which only a caller that bypasses generics can pass, and
     * names it by {@code name()}, never through a {@code toString()} that could change or replace the refusal.
     */
    static void requireTransition(MachineKey<?, ?, ?> key, Enum<?> id) {
        if (!key.transitions().isInstance(id)) {
            throw new IllegalArgumentException(
                    "transition must belong to " + key.transitions().getName() + ": " + id.name());
        }
    }

    /**
     * The rest of Cascades.cascadeAll once its elements are accepted: the identifier, the machine, then every element.
     * Once the identifier is accepted, all of it runs inside a catch whose first statement condemns the call with a
     * store, which needs neither stack nor memory, as the catch of a single cascade does: whatever ends the cascade
     * from then on poisons the call, the lookup of the machine included, and so does an Error that strikes where no
     * element's catch runs, between two elements, while an element's arguments are checked again or while the outcomes
     * are gathered. Such an Error leaves as itself, and {@link #settle} records it, unless a poison is stored already.
     * Each COLLECT element runs as {@link Cascades#collect} says, and the aggregate is built as
     * {@link Cascades#aggregate} says.
     */
    <P, Q extends Enum<Q>, U extends Enum<U>> List<Outcome<P, Q, U>> cascadeEach(
            ContextHandle caller, MachineKey<P, Q, U> key, List<P> elements, U id, CascadePolicy policy) {
        requireTransition(key, id);
        try {
            Cascades.requireMachine(this, caller, key);
            List<Outcome<P, Q, U>> outcomes = new ArrayList<>();
            List<TransitionAbortedException> errors = new ArrayList<>();
            // Only the TRACE lines read the policy and the element's reference: a call that does not log at TRACE keeps
            // neither, and allocates nothing for them.
            Optional<CascadePolicy> traced = log.trace() ? Optional.of(policy) : Optional.empty();
            for (P peer : elements) {
                if (policy == CascadePolicy.FAIL_FAST) {
                    outcomes.add(Cascades.cascade(this, caller, key, peer, id, traced));
                    continue;
                }
                Cascades.collect(this, caller, key, peer, id, traced, outcomes, errors);
            }
            if (!errors.isEmpty()) {
                throw Cascades.aggregate(this, caller, errors);
            }
            return List.copyOf(outcomes);
        } catch (Throwable error) {
            // First a store, which cannot overflow: once the identifier is accepted, whatever ends the cascade poisons
            // the call, an Error that strikes between two elements, where no element's catch runs, too. It leaves as
            // itself, and settle records it unless a poison is stored already.
            condemned = true;
            throw error;
        }
    }

    void checkPoison(ContextHandle caller) {
        settle(caller);
        if (uow.isPoisoned()) {
            throw new PoisonedUnitOfWorkException(call(), details(caller), uow.poison().orElseThrow());
        }
    }

    /**
     * Records a failed cascade that left no poison, as an abort of {@code caller}'s step that carries no cause: a
     * VirtualMachineError that the cascade let through unrecorded, or a failure whose recording overflowed the stack,
     * which the step's action caught and went on from. The engine calls this on that step's own stack, unwound from
     * the Error, before it reads or stores a poison there: before it checks the poison for a peer read or a cascade,
     * and first when the action ends, unless an Error it throws as itself ends it. The abort is a failure that is no
     * failure of the call, so COLLECT does not collect it, and a poison already stored stays: the call keeps its first.
     */
    void settle(ContextHandle caller) {
        if (condemned && !uow.isPoisoned()) {
            uow.poisonWrapping(new TransitionAbortedException(call(), phase, details(caller),
                    "the action caught an Error that ended one of its cascades: the call cannot commit"));
        }
    }
}
