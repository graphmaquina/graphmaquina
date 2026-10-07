package io.github.graphmaquina.error;

import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.StatesWritten;
import java.util.List;
import java.util.Objects;

/**
 * A call that failed, as the call itself classifies it. Every exception a call throws is an Error, which propagates
 * unchanged, or one of these, of that very call: any other exception involved is its cause. {@link #call()} is the call
 * and {@link #phase()} where it was. {@link #statesWritten()} says how far it got in writing states through the
 * setters; it says nothing about a database transaction around the call. {@link #journal()} lists which actions ran,
 * which matters more than the phase when deciding whether a retry is safe: an action that ran may have had external
 * effects that nothing undoes. A refused argument and a reentrant call are refused before the call starts, and are not
 * call failures. Only {@link TransitionAbortedException}, before the commit, {@link CommitFailedException}, during it,
 * and {@link AfterCommitException}, after it, extend it.
 */
public abstract class CallFailedException extends StateMachineException {

    private final MachineCall call;

    CallFailedException(MachineCall call, String message) {
        super(message);
        this.call = Objects.requireNonNull(call, "call");
    }

    CallFailedException(MachineCall call, String message, Throwable cause) {
        super(message, cause);
        this.call = Objects.requireNonNull(call, "call");
    }

    /** The call that failed: one instance per call, the one its observers, if any, were notified with. */
    public MachineCall call() {
        return call;
    }

    /** The phase the call was in when it failed. */
    public abstract CallPhase phase();

    /** How many of the call's states the setters had written when it failed. */
    public abstract StatesWritten statesWritten();

    /** The actions that ran in the call, in order, each with its final status; none is RUNNING. */
    public abstract List<ActionRecord> journal();
}
