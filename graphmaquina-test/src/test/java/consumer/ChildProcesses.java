package consumer;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The cleanup of the child JVMs that the published-artifact tests start and wait for: whatever happened during the
 * wait, no child is left running, and a cleanup that fails does not hide why the wait failed.
 */
final class ChildProcesses {

    private ChildProcesses() { }

    /**
     * Runs in the {@code finally} of a test that waited for {@code process}. Forcibly stops the child if it is still
     * alive and waits up to ten seconds for it to be reaped, raising an {@link AssertionError} with
     * {@code stillAliveMessage} when it is not. A {@link RuntimeException} or {@link Error} of this cleanup is added
     * as suppressed to {@code originalFailure} when the test already failed, and thrown otherwise.
     */
    static void reap(Process process, Optional<Throwable> originalFailure, String stillAliveMessage) {
        try {
            destroyAndAwait(process, stillAliveMessage);
        } catch (RuntimeException | Error cleanupFailure) {
            if (originalFailure.isEmpty()) {
                throw cleanupFailure;
            }
            originalFailure.get().addSuppressed(cleanupFailure);
        }
    }

    private static void destroyAndAwait(Process process, String stillAliveMessage) {
        if (process.isAlive()) {
            process.destroyForcibly();
            if (!awaitReaped(process)) {
                throw new AssertionError(stillAliveMessage);
            }
        }
    }

    /** Waits up to ten seconds for the process to end; an interruption on the way is restored once the wait is over. */
    private static boolean awaitReaped(Process process) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean reaped = false;
        boolean cleanupInterrupted = false;
        while (!reaped) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                reaped = process.waitFor(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                cleanupInterrupted = true;
            }
        }
        if (cleanupInterrupted) {
            Thread.currentThread().interrupt();
        }
        return reaped;
    }
}
