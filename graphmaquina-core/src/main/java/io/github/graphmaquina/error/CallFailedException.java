package io.github.graphmaquina.error;

import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.internal.CallFailureFlows;
import java.util.List;
import java.util.Objects;

/**
 * A call that failed, as the call itself classifies it. Every exception a call throws is an Error, which propagates
 * unchanged, or one of these, of that very call: any other exception involved is its cause. {@link #call()} is the call
 * and {@link #phase()} where it was. {@link #statesWritten()} says how far it got in writing states through the
 * setters; it says nothing about a database transaction around the call. {@link #journal()} lists which actions ran,
 * which matters more than the phase when deciding whether a retry is safe: an action that ran may have had external
 * effects that nothing undoes. {@link #flow()} is the call's flow, frozen when the call failed. A refused argument and
 * a reentrant call are refused before the call starts, and are not call failures. Only
 * {@link TransitionAbortedException}, before the commit, {@link CommitFailedException}, during it, and
 * {@link AfterCommitException}, after it, extend it.
 */
public abstract class CallFailedException extends StateMachineException {

    static {
        CallFailureFlows.register((CallFailedException failure, FlowView frozen) -> failure.flow = frozen);
    }

    private final MachineCall call;
    /**
     * Set by the root of the call through {@link CallFailureFlows}, never null. Not transient: writing a call failure
     * fails anyway, on its call and on this flow, neither of which is serializable, so no deserialized copy of a
     * failure exists to leave it null.
     */
    private FlowView flow = FlowView.empty();

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

    /**
     * The flow of the call, frozen by its root when the call failed and attached before the observers hear of the
     * failure: the root call's entity and the values present then, each under the key that put it. They are what the
     * steps wrote up to the failure, minus the writes of each step whose failure its edge expects, a failure it
     * returned or a throwable of a declared type, which the engine rolls back whether a route takes it or the step
     * aborts with {@link UnhandledFailureException}, and of each {@code COLLECT} element that aborted. No other abort
     * undoes a write, so the step that aborted keeps its writes, and so do the steps it cascaded to. After the commit,
     * they also hold what the hooks wrote, a failing hook's writes before it threw included. The view is immutable,
     * any thread can read it for as long as it is kept, and it compares by value; its values are the very objects
     * that were put. When the root machine records its trail, {@link FlowView#trail()} lists the hops and the
     * sub-machine starts the call took up to the failure, those a rollback dropped left out, then a
     * {@link io.github.graphmaquina.api.FlowEntry.Failed} entry with this failure, read when the root caught it,
     * unless the trail's clock throws or answers null then, which leaves the entry out; a reevaluation that failed
     * before it ran an edge, reading the state, a chain of watch reactions over its limit, or a watch function that
     * threw, has the entity, no value and at most that entry. Without the trail, the flow is
     * {@link FlowView#empty()}, without an entity, when no step or hook of the call wrote to it, since reading never
     * creates it, and for such a reevaluation. A failure of another call that is the cause of this one keeps the flow
     * of its own call. An Error that ends a call carries no flow.
     */
    public FlowView flow() {
        return flow;
    }
}
