package consumer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The waits that the tests of the controlled asynchronous fixtures share. Each gives up after two seconds with an
 * {@link AssertionError} that says what never happened, instead of hanging the build.
 */
final class BoundedWaits {

    private BoundedWaits() { }

    /** Waits until the thread sits in a timed wait, which is where a blocked {@code awaitInvocation} is. */
    static void awaitBlocked(Thread thread) {
        long start = System.nanoTime();
        while (thread.getState() != Thread.State.TIMED_WAITING) {
            if (System.nanoTime() - start > TimeUnit.SECONDS.toNanos(2)) {
                throw new AssertionError("the waiter never blocked, it is " + thread.getState());
            }
            Thread.onSpinWait();
        }
    }

    /** Waits for the latch to open and fails with {@code timeoutMessage} if it has not within two seconds. */
    static void awaitLatch(CountDownLatch latch, String timeoutMessage) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError(timeoutMessage);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test thread interrupted while waiting on a latch", interrupted);
        }
    }
}
