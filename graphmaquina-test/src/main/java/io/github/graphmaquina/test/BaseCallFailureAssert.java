package io.github.graphmaquina.test;

import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.test.internal.Checks;
import java.util.Objects;
import java.util.Optional;

/**
 * Expectations every failure of a call shares, whatever its kind: where the call was, how far it got in writing
 * states, which call it was, which actions ran and what the call left in its flow. They read the values the failure
 * carries and never its message.
 *
 * @param <SELF> the concrete view returned by every expectation
 */
public abstract class BaseCallFailureAssert<SELF extends BaseCallFailureAssert<SELF>>
        extends BaseFailureAssert<SELF> {

    private final CallFailedException failure;

    BaseCallFailureAssert(String context, CallFailedException failure) {
        super(context, failure);
        this.failure = failure;
    }

    /** The phase the failure reports, which is where the call was when it failed. */
    public SELF hasPhase(CallPhase expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "phase", expected, failure.phase());
        return self();
    }

    /** How far the failure reports the call got in writing states through the setters. */
    public SELF hasStatesWritten(StatesWritten expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.equal(context, "states written", expected, failure.statesWritten());
        return self();
    }

    /** Compared by reference: the call is the instance that the call's observers, if any, were notified with. */
    public SELF hasCallSameAs(MachineCall expected) {
        Objects.requireNonNull(expected, "expected");
        Checks.same(context, "call", expected, failure.call());
        return self();
    }

    /** The actions that ran in the call, in order, each with its final status; they survive the failure. */
    public JournalAssert journal() {
        return new JournalAssert(context + ".journal", failure.journal());
    }

    /**
     * The flow of the failed call, as {@link CallFailedException#flow()} describes it: what the call's steps and hooks
     * left there up to the failure, its root entity and, when its root machine records it, its trail. A failed call
     * that wrote nothing to its flow and records no trail hands out
     * {@link io.github.graphmaquina.api.FlowView#empty()}, which holds no entity, and so does a failure of the call
     * nested in the one it threw, such as an element of a cascade abort.
     */
    public FlowAssert flow() {
        return new FlowAssert(context + ".flow", Optional.of(failure.flow()));
    }
}
