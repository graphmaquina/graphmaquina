package io.github.graphmaquina.fixtures;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** What a call throws, here or on a thread of its own, and as the text that a test compares with a literal. */
public final class Thrown {

    private Thrown() { }

    /** What {@code call} throws, or empty when it returns. */
    public static Optional<RuntimeException> thrownBy(Runnable call) {
        try {
            call.run();
            return Optional.empty();
        } catch (RuntimeException error) {
            return Optional.of(error);
        }
    }

    /** What {@link #thrownBy} reports for {@code call} run on a thread of its own. */
    public static Optional<RuntimeException> thrownOnAnotherThread(Runnable call) {
        AtomicReference<Optional<RuntimeException>> thrown = new AtomicReference<>(Optional.empty());
        Thread other = new Thread(() -> thrown.set(thrownBy(call)));
        other.start();
        try {
            other.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return thrown.get();
    }

    /** {@code Class: message} of what was thrown, or "nothing". */
    public static String describe(Optional<RuntimeException> thrown) {
        return thrown.map((RuntimeException error) -> error.getClass().getSimpleName() + ": " + error.getMessage())
                .orElse("nothing");
    }
}
