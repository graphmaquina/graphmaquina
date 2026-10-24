package io.github.graphmaquina.error;

import io.github.graphmaquina.api.ActionRecord;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.SubmachineStart;
import io.github.graphmaquina.api.TransitionRecord;
import java.util.List;
import java.util.Objects;
import lombok.Getter;
import lombok.NonNull;

/**
 * An after-commit hook threw a throwable that is not an Error, a checked exception included; an Error propagates
 * unchanged instead. When that throwable is an InterruptedException, the engine interrupts the thread again. A hook
 * runs only for committed hops, so it fails after phase 2 had written every state of the call: its phase is HOOK and
 * its states written ALL. Nothing is rolled back: the caller's transaction decides. {@link #hop()} is the committed hop
 * whose hook threw, {@link #records()} every hop the call committed, in log order, and {@link #starts()} every
 * sub-machine it started; a start runs no hook. The hooks of the hops before {@link #hop()} ran; the later hooks and
 * the watch reactions of the call did not. {@link #flow()} holds what the steps wrote and what the hooks that ran
 * wrote, the failing hook's writes before it threw included, and, when the call records its trail, every hop and start
 * the call committed, wrapping the very records and starts this exception lists, then the entry of this failure when
 * the trail's clock could read its instant.
 */
@Getter
public final class AfterCommitException extends CallFailedException {

    private final List<ActionRecord> journal;

    /** Every hop the call committed, in log order, the one whose hook threw included. */
    private final List<TransitionRecord<?, ?, ?>> records;

    /** Every sub-machine the call started, in log order, as {@code Outcome.starts()} would have reported them. */
    private final List<SubmachineStart<?, ?, ?>> starts;

    /** The committed hop whose hook threw: its machine, entity, endpoints, identifier, kind, action and depth. */
    private final TransitionRecord<?, ?, ?> hop;

    /** The name of the hook that threw. */
    private final String hook;

    /**
     * The hook {@code hook} of the committed {@code hop} threw {@code cause}; {@code records} are every hop the call
     * committed, and {@code starts} every sub-machine it started.
     */
    public AfterCommitException(MachineCall call, List<ActionRecord> journal, List<TransitionRecord<?, ?, ?>> records,
                                List<SubmachineStart<?, ?, ?>> starts, TransitionRecord<?, ?, ?> hop, String hook,
                                Throwable cause) {
        super(call, describe(call, hook), cause);
        this.journal = List.copyOf(journal);
        this.records = List.copyOf(records);
        this.starts = List.copyOf(starts);
        this.hop = Objects.requireNonNull(hop, "hop");
        this.hook = hook;
    }

    /** As the canonical constructor, for a call that started no sub-machine. */
    public AfterCommitException(MachineCall call, List<ActionRecord> journal, List<TransitionRecord<?, ?, ?>> records,
                                TransitionRecord<?, ?, ?> hop, String hook, Throwable cause) {
        this(call, journal, records, List.of(), hop, hook, cause);
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

    private static String describe(@NonNull MachineCall call, @NonNull String hook) {
        return "the states of " + call + " are already written, but the hook '" + hook + "' failed";
    }
}
