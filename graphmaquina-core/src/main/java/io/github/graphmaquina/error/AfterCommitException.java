package io.github.graphmaquina.error;

import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.TransitionRecord;
import java.util.List;
import java.util.Objects;

/**
 * An after-commit hook threw a throwable that is not an Error, a checked exception included; an Error propagates
 * unchanged instead. A hook runs only for committed hops, so it fails after phase 2 had written every state of the
 * call: its phase is HOOK and its states written ALL. Nothing is rolled back: the caller's transaction decides.
 * {@link #hop()} is the committed hop whose hook threw and {@link #records()} every hop the call committed, in log
 * order. The hooks of the hops before {@link #hop()} ran; the later hooks and the watch reactions of the call did not.
 */
public final class AfterCommitException extends CallFailedException {

    private final List<ActionRecord> journal;
    private final List<TransitionRecord<?, ?, ?>> records;
    private final TransitionRecord<?, ?, ?> hop;
    private final String hook;

    /**
     * The hook {@code hook} of the committed {@code hop} threw {@code cause}; {@code records} are every hop the call
     * committed.
     */
    public AfterCommitException(MachineCall call, List<ActionRecord> journal, List<TransitionRecord<?, ?, ?>> records,
                                TransitionRecord<?, ?, ?> hop, String hook, Throwable cause) {
        super(call, describe(call, hook), cause);
        this.journal = List.copyOf(journal);
        this.records = List.copyOf(records);
        this.hop = Objects.requireNonNull(hop, "hop");
        this.hook = hook;
    }

    /** HOOK. */
    @Override
    public CallPhase phase() {
        return CallPhase.HOOK;
    }

    /** ALL: a hook runs only once every state of the call was written. */
    @Override
    public StatesWritten statesWritten() {
        return StatesWritten.ALL;
    }

    @Override
    public List<ActionRecord> journal() {
        return journal;
    }

    /** The name of the hook that threw. */
    public String hook() {
        return hook;
    }

    /** The committed hop whose hook threw: its machine, entity, endpoints, identifier, kind, action and depth. */
    public TransitionRecord<?, ?, ?> hop() {
        return hop;
    }

    /** Every hop the call committed, in log order, the one whose hook threw included. */
    public List<TransitionRecord<?, ?, ?>> records() {
        return records;
    }

    private static String describe(MachineCall call, String hook) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(hook, "hook");
        return "the states of " + call + " are already written, but the hook '" + hook + "' failed";
    }
}
