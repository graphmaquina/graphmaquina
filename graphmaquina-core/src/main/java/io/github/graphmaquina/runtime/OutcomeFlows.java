package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.FlowEntry;
import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.SubmachineStart;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.internal.FrozenFlow;
import io.github.graphmaquina.internal.Interrupts;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;

/**
 * The call's flow as an outcome or a failure of the call hands it out: frozen with the values present at the end of
 * the call and, when the call records its trail, the trail that the outcome or the failure ends.
 */
final class OutcomeFlows {

    private OutcomeFlows() { }

    /**
     * The call's flow as an applied or a failed outcome hands it out: frozen, with the root call's entity, the values
     * present now and, when the call records its trail, the trail, whose hop and start entries wrap {@code records}
     * and {@code starts}, the outcome's own. {@link FlowView#empty()} when the call records no trail and no write of
     * the call created the flow. It never creates the flow.
     */
    static FlowView frozenFlow(Transaction tx, List<TransitionRecord<?, ?, ?>> records,
                               List<SubmachineStart<?, ?, ?>> starts) {
        UnitOfWork uow = tx.uow();
        if (!uow.recordsTrail()) {
            return frozen(tx, List.of());
        }
        return frozen(tx, uow.trailOf(records, starts));
    }

    /**
     * The call's flow as a rejected outcome hands it out: as {@link #frozenFlow(Transaction, List, List)} says, the
     * trail built from the log and ended by the {@code Rejected} entry of {@code rejected}, at the instant the clock
     * reads now. A clock that throws, or answers null as {@link CallFlow#now} refuses, fails the call, which classifies
     * the exception: its abort names the state the step read, its identifier and the destination of the edge it
     * selected, as {@link #recordRejectedStep} says.
     */
    static FlowView frozenFlow(Transaction tx, StepResult.Rejected<?, ?> rejected) {
        UnitOfWork uow = tx.uow();
        if (!uow.recordsTrail()) {
            return frozen(tx, List.of());
        }
        List<FlowEntry> trail = uow.trailOf(uow.records(), uow.starts());
        Instant at;
        try {
            at = CallFlow.now(uow.clock());
        } catch (Throwable undated) {
            recordRejectedStep(tx, rejected);
            throw undated;
        }
        trail.add(new FlowEntry.Rejected(tx.callKey(), tx.callEntity(), rejected.from(), rejected.transition(),
                rejected.kind(), rejected.reason(), rejected.guard(), at));
        return frozen(tx, trail);
    }

    /**
     * The call's flow as {@code failure}, a failure of this call, hands it out: as
     * {@link #frozenFlow(Transaction, List, List)} says, the trail built from the log as the call left it and ended as
     * {@link #endWith} says, with what {@link Transaction#failureOf} tells the observers of {@code failure}. The hop
     * and start entries of an {@link AfterCommitException} wrap the records and starts it lists, which the commit
     * built; those of any other failure wrap records and starts built from the log once, here.
     */
    static FlowView frozenFlow(Transaction tx, CallFailedException failure) {
        UnitOfWork uow = tx.uow();
        if (!uow.recordsTrail()) {
            return frozen(tx, List.of());
        }
        List<FlowEntry> trail = failure instanceof AfterCommitException hooked
                ? uow.trailOf(hooked.records(), hooked.starts())
                : uow.trailOf(uow.records(), uow.starts());
        endWith(trail, tx.failureOf(failure), uow.clock());
        return frozen(tx, trail);
    }

    /**
     * Ends {@code trail} with the {@code Failed} entry of {@code failure}, at the instant {@code clock} reads now. A
     * clock that throws anything but an Error, a checked exception included, or answers null as {@link CallFlow#now}
     * refuses, leaves the entry out, and the exception of {@code failure}, which the call throws, carries that
     * exception as suppressed, unless the clock threw that very exception, which cannot suppress itself; an
     * InterruptedException interrupts the thread again, and an Error propagates.
     */
    static void endWith(List<FlowEntry> trail, CallFailure failure, InstantSource clock) {
        try {
            trail.add(new FlowEntry.Failed(failure, CallFlow.now(clock)));
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable thrown) {
            Interrupts.keep(thrown);
            if (thrown != failure.error()) {
                failure.error().addSuppressed(thrown);
            }
        }
    }

    /** The call's flow frozen with {@code trail}, or {@link FlowView#empty()} when the call has no flow. */
    private static FlowView frozen(Transaction tx, List<FlowEntry> trail) {
        Optional<CallFlow> flow = tx.uow().flow();
        if (flow.isEmpty()) {
            return FlowView.empty();
        }
        return new FrozenFlow(Optional.of(flow.get().rootEntity()), flow.get().values(), trail);
    }

    /**
     * Records, as what the root's own step had read and selected before {@code rejected}, its state, its identifier
     * and the destination of the edge it selected: a guard and a reserved exit refuse the edge the step selected, so
     * its destination is named; a request that matched no edge and a sub-machine that no parent holds selected none.
     */
    private static void recordRejectedStep(Transaction tx, StepResult.Rejected<?, ?> rejected) {
        boolean selected = rejected.kind() == RejectionKind.GUARD || rejected.kind() == RejectionKind.RESERVED_EXIT;
        tx.recordRootStep(rejected.from(), rejected.transition(), selected
                ? destinationOf(tx.registry().anyMachineFor(tx.callKey()), rejected.from(), rejected.transition())
                : Optional.empty());
    }

    /** {@link Steps#selectedDestination} for the machine of the call, whose types the rejection no longer carries. */
    private static <E, S extends Enum<S>, T extends Enum<T>> Optional<Enum<?>> destinationOf(
            DefaultStateMachine<E, S, T> machine, Enum<?> from, Enum<?> id) {
        return Steps.selectedDestination(machine, machine.key().states().cast(from),
                machine.key().transitions().cast(id));
    }
}
