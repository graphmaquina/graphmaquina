package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.CallOrigin;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CallRequest;
import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.SubmachineStart;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.internal.CallFailureFlows;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * A root call of a machine, from its start to what its observers hear: its transaction, the step and phase 2 under the
 * reentrancy marker, the hooks, the watch reactions and the automatic chains of what it committed, its outcome or its
 * failure, and the lines its log prints around them.
 */
final class RootCall {

    private RootCall() { }

    /**
     * Root transaction of {@code machine}: new UoW with its deadline, reentrancy marker, step, phase 2, context
     * invalidation, then the hooks, the watch reactions and the automatic chains of what it committed, as
     * {@link PostCommit#after} says. Everything is prepared before the marker is set, so that from
     * {@code Reentrancy.enter} on, the only way out runs through the {@code finally} that releases it, with a store
     * before it invalidates the contexts, whatever the step throws, a StackOverflowError included. The transaction
     * keeps the phase of the call, which the observers are told when it throws.
     * Watch reactions are dispatched after the marker is released, so a reaction may start its own transaction; the
     * dispatch sets the marker again only while it runs an owner lookup, and a reevaluation while it runs its watch.
     * The automatic chains run after them, each attempt a root of its own.
     * {@code requested} is the identifier execute was asked for, and empty for a reevaluation or an advance;
     * {@code levels} is where the call stands in the watch and automatic chains, and {@code continuing} the entity
     * whose chain made this call an attempt of it, which its own commit chains no further, empty for any other call.
     * It returns the outcome with the summary of the commit.
     * When {@code machine}, the root's, records its trail, the call's flow is created before the step runs, and every
     * entry the call logs is marked on its clock. Once the reactions and the chains ran, the call's flow is frozen into
     * the outcome, its trail ending with the rejection of a rejected outcome, or into the empty view when the call
     * records no trail and no write of the call created its flow. A failure of the call gets the flow frozen as the
     * call left it, its trail ending with the failure, attached before the observers hear of the failure; an Error
     * gets none, and propagates as itself. The trail ends without that failure when the trail's clock throws anything
     * but an Error or answers null as it reads the failure's instant, as OutcomeFlows.endWith says. An Error the
     * trail's clock throws while the failure's flow is frozen propagates in place of the failure, and the observers
     * hear of that Error, in the phase the call reached; so does an Error that printing the failure throws while the
     * call classifies it, such as one from a message whose read throws it.
     * The observers hear of the call's start before its log does, and of its end after it: the start line follows
     * onStarted, and the end or failed line comes just before their end event, each inside a try that tells them of
     * an Error, so a backend Error on any of these lines ends the call and is the end event they hear. An execute logs
     * its start line here; a reevaluation or an advance logged its own before it read the state. Phase 2 moves the
     * call on to HOOK once its last setter returned, so that whatever fails from then on reports every state written:
     * building the committed hops and starts, their lines, logged before the hooks run, and the hooks; an Error gets no
     * failed line. At TRACE, phase 2 logs its check and its setters, each hook is logged before it runs and each of its
     * writes to the call's flow as it makes it, and the watch dispatch logs each owner lookup.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> Committed<E, S, T> run(
            DefaultStateMachine<E, S, T> machine, E entity, CallRequest request, Optional<Enum<?>> requested,
            CallOrigin origin, Optional<Observed> observed, CallLog log, Function<Transaction, StepResult<S, T>> body,
            ChainLevels levels, Optional<EntityRef> continuing) {
        MachineRegistry registry = machine.registry();
        UnitOfWork uow = new UnitOfWork(registry.maxCascadeDepth(), machine.deadlineNanos());
        if (machine.trail().isPresent()) {
            uow.recordTrail(entity, machine.trail().get());
        }
        Transaction tx = new Transaction(registry, uow, machine.key(), entity, requested,
                () -> new MachineCall(machine.key(), entity, request, requested, origin),
                observed.map(Observed::call), log);
        Committed<E, S, T> committed;
        try {
            if (log.debug() && requested.isPresent()) {
                log.start(machine.key(), requested.get().name());
            }
            committed = stepAndCommit(machine, tx, entity, body, levels, continuing);
        } catch (Throwable thrown) {
            throw failure(observed, tx, thrown);
        }
        ended(observed, tx, committed.outcome());
        return committed;
    }

    /**
     * The step and phase 2 under the reentrancy marker, released, with the contexts invalidated, whatever they throw;
     * then, once something was committed, its line, its hooks, its watch reactions and its automatic chains; then the
     * outcome, with the summary of the commit.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> Committed<E, S, T> stepAndCommit(
            DefaultStateMachine<E, S, T> machine, Transaction tx, E entity,
            Function<Transaction, StepResult<S, T>> body, ChainLevels levels, Optional<EntityRef> continuing) {
        StepResult<S, T> step;
        List<TransitionRecord<?, ?, ?>> records;
        List<SubmachineStart<?, ?, ?>> starts;
        List<LogEntry> committed;
        CommitSummary summary;
        boolean[] marker = Reentrancy.enter(machine.key(), entity);
        try {
            step = body.apply(tx);
            boolean commits = step instanceof StepResult.Applied<S, T> || step instanceof StepResult.Failed<S, T>;
            if (commits) {
                tx.enter(CallPhase.COMMIT);
                summary = Commit.apply(machine.registry(), tx);
                records = tx.uow().records();
                starts = tx.uow().starts();
                committed = tx.uow().entries();
            } else {
                summary = CommitSummary.EMPTY;
                records = List.of();
                starts = List.of();
                committed = List.of();
            }
        } finally {
            marker[0] = false;
            tx.invalidate();
        }
        return new Committed<>(PostCommit.after(tx, records, starts, committed, summary, levels, continuing)
                .outcome(tx, entity, step, records, starts), summary, Set.of());
    }

    /**
     * What the call throws for {@code thrown}, once the observers heard of it: an Error as itself; anything else as the
     * transaction classifies it, with the flow frozen as the call left it attached and, when the call logs, its failed
     * line logged. An Error that classifying, freezing or that line throws is thrown in its place.
     */
    private static CallFailedException failure(Optional<Observed> observed, Transaction tx, Throwable thrown) {
        if (thrown instanceof Error fatal) {
            throw failed(observed, tx, fatal);
        }
        CallFailedException own;
        try {
            own = tx.classify(thrown);
        } catch (Error fatal) {
            throw failed(observed, tx, fatal);
        }
        FlowView flow;
        try {
            flow = OutcomeFlows.frozenFlow(tx, own);
        } catch (Error fatal) {
            throw failed(observed, tx, fatal);
        }
        CallFailureFlows.attach(own, flow);
        if (tx.log().debug()) {
            try {
                tx.log().failed(tx.failureOf(own));
            } catch (Error fatal) {
                throw failed(observed, tx, fatal);
            }
        }
        return failed(observed, tx, own);
    }

    /**
     * Ends the call with {@code outcome}: logs its end line, when the call logs, then tells the observers, if any. An
     * Error the end line throws ends the call instead: the observers hear of that Error, and it propagates.
     */
    private static <E, S extends Enum<S>, T extends Enum<T>> Outcome<E, S, T> ended(
            Optional<Observed> observed, Transaction tx, Outcome<E, S, T> outcome) {
        if (tx.log().debug()) {
            try {
                tx.log().end(outcome);
            } catch (Error fatal) {
                throw failed(observed, tx, fatal);
            }
        }
        return completed(observed, outcome);
    }

    /**
     * Tells the registry's observers that a call of {@code machine} starts; reads no clock and allocates nothing when
     * it has none. The end events measure from the {@code System.nanoTime()} read of the call's log, when the call
     * logs, and from a read of their own otherwise, so a call reads {@code System.nanoTime()} once at its start either
     * way; the clock of a trail is another one.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> Optional<Observed> observe(
            DefaultStateMachine<E, S, T> machine, E entity, CallRequest request, Optional<Enum<?>> requested,
            CallOrigin origin, CallLog log) {
        Observers observers = machine.registry().observers();
        if (observers.isEmpty()) {
            return Optional.empty();
        }
        long startNanos = log == CallLog.OFF ? System.nanoTime() : log.startNanos();
        return Optional.of(observers.started(new MachineCall(machine.key(), entity, request, requested, origin),
                startNanos));
    }

    /**
     * The call that the own failure of a reevaluation or an advance carries: the observed one, or a new one when
     * nobody observes it.
     */
    static <E, S extends Enum<S>, T extends Enum<T>> MachineCall callOf(
            DefaultStateMachine<E, S, T> machine, E entity, Optional<Observed> observed, CallRequest request,
            CallOrigin origin) {
        return observed.isPresent()
                ? observed.get().call()
                : new MachineCall(machine.key(), entity, request, Optional.empty(), origin);
    }

    /** Tells the observers, if any, that the call returned {@code outcome}. */
    private static <O extends Outcome<?, ?, ?>> O completed(Optional<Observed> observed, O outcome) {
        if (observed.isPresent()) {
            observed.get().completed(outcome);
        }
        return outcome;
    }

    /**
     * Ends a reevaluation or an advance that ran no step with {@code outcome}: logs its end line, then tells the
     * observers, if any. An Error the end line throws ends the call instead, in RESOLVE with nothing written: the
     * observers hear of that Error, and it propagates.
     */
    static <O extends Outcome<?, ?, ?>> O ignored(Optional<Observed> observed, CallLog log, O outcome) {
        if (log.debug()) {
            try {
                log.end(outcome);
            } catch (Error fatal) {
                failed(observed, CallPhase.RESOLVE, StatesWritten.NONE, fatal);
                throw fatal;
            }
        }
        return completed(observed, outcome);
    }

    /** Tells the observers, if any, that the call ended in {@code thrown}, as its transaction classifies it. */
    private static <X extends Throwable> X failed(Optional<Observed> observed, Transaction tx, X thrown) {
        if (observed.isPresent()) {
            observed.get().failed(tx.failureOf(thrown));
        }
        return thrown;
    }

    static void failed(Optional<Observed> observed, CallPhase phase, StatesWritten written, Throwable thrown) {
        if (observed.isPresent()) {
            observed.get().failed(new CallFailure(phase, written, thrown));
        }
    }
}
