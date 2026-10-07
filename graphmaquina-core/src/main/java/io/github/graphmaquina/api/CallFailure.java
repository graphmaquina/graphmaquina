package io.github.graphmaquina.api;

import lombok.NonNull;

/**
 * How a call ended when it ended in a throwable, as an observer is told: the phase it was in, how far it got in
 * writing states, and the very throwable its caller receives, an Error included. A phase before the commit reports
 * {@link StatesWritten#NONE}, the commit {@code NONE} or {@code PARTIAL}, a hook or a reaction {@code ALL}; any other
 * combination is refused.
 *
 * @param phase where the call was when it failed
 * @param statesWritten how far it got in writing states through the setters
 * @param error the throwable the caller receives
 */
public record CallFailure(@NonNull CallPhase phase, @NonNull StatesWritten statesWritten, @NonNull Throwable error) {

    public CallFailure {
        boolean possible = switch (phase) {
            case RESOLVE, GUARD, ACTION -> statesWritten == StatesWritten.NONE;
            case COMMIT -> statesWritten != StatesWritten.ALL;
            case HOOK, REACTIONS -> statesWritten == StatesWritten.ALL;
        };
        if (!possible) {
            throw new IllegalArgumentException(
                    "a call that failed in " + phase + " cannot have written " + statesWritten);
        }
    }
}
