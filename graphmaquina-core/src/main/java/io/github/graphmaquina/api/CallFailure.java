package io.github.graphmaquina.api;

import lombok.NonNull;

/**
 * How a call ended when it ended in a throwable, as a {@link MachineObserver} is told: the phase it was in, how far
 * it got in writing states, and the very throwable its caller receives, an Error included. A phase before the commit
 * reports {@link StatesWritten#NONE}, the commit {@code NONE} or {@code PARTIAL}, a hook or a reaction {@code ALL};
 * any other combination is refused.
 *
 * @param phase where the call was when it failed
 * @param statesWritten how far it got in writing states through the setters
 * @param error the throwable the caller receives
 */
public record CallFailure(@NonNull CallPhase phase, @NonNull StatesWritten statesWritten, @NonNull Throwable error) {

    public CallFailure {
        // Not a switch: on another class's enum, javac compiles one to a synthetic class with a static initializer,
        // which assembling a registry cannot name to initialise, as MachineRegistry.staticallyInitialized says.
        boolean possible;
        if (phase == CallPhase.COMMIT) {
            possible = statesWritten != StatesWritten.ALL;
        } else if (phase == CallPhase.HOOK || phase == CallPhase.REACTIONS) {
            possible = statesWritten == StatesWritten.ALL;
        } else {
            possible = statesWritten == StatesWritten.NONE;
        }
        if (!possible) {
            throw new IllegalArgumentException(
                    "a call that failed in " + phase + " cannot have written " + statesWritten);
        }
    }
}
