package io.github.graphmaquina.test.probe;

/**
 * How one call to a wrapped state setter stands, as the {@link StateWriteProbe} saw it. It describes the call
 * and not its effect: {@code RETURNED} says the setter returned without throwing, never that the entity now
 * holds the requested state, since what an arbitrary setter does with the value is the setter's business.
 */
public enum WriteStatus {

    /**
     * The call was entered and has not left yet. That is what a setter blocked on a latch or on a slow
     * dependency looks like while another thread reads the probe; when the call leaves, the probe replaces the
     * record with a {@link #RETURNED} or {@link #THREW} one.
     */
    RUNNING,

    /** The call returned normally: the wrapped setter returned without throwing. */
    RETURNED,

    /** The call left by throwing, either the scheduled failure or whatever the wrapped setter threw. */
    THREW
}
