package io.github.graphmaquina.fixtures;

import java.time.Duration;

/** The deadlines that the tests of asynchronous work set and wait under. */
public final class AsyncDeadlines {

    /** A budget that no test reaches. */
    public static final Duration HOUR = Duration.ofHours(1);

    /** A budget that a test lets run out, far shorter than {@link #GENEROUS}. */
    public static final Duration SHORT = Duration.ofMillis(100);

    /** How long a test waits for a call that must end by itself, far longer than {@link #SHORT}. */
    public static final Duration GENEROUS = Duration.ofSeconds(10);

    private AsyncDeadlines() { }
}
