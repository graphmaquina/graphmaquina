package io.github.graphmaquina.fixtures;

import java.time.Instant;
import java.time.InstantSource;

/**
 * A clock for the trail of a call: its n-th read answers {@link #at(int) at(n)}, n seconds after the epoch, so each
 * read answers one second more than the one before. It counts its reads and runs {@link #onRead} before each one
 * answers. Not thread-safe: a call reads its clock on the thread running it.
 */
public final class TickingClock implements InstantSource {

    /** Runs before a read answers, the read not yet counted; a test replaces it to watch the reads or to throw. */
    public Runnable onRead = () -> { };
    private int reads;

    /** What the {@code read}-th read answers: {@code read} seconds after the epoch. */
    public static Instant at(int read) {
        return Instant.ofEpochSecond(read);
    }

    /** How many reads answered so far. */
    public int reads() {
        return reads;
    }

    @Override
    public Instant instant() {
        onRead.run();
        reads++;
        return at(reads);
    }
}
