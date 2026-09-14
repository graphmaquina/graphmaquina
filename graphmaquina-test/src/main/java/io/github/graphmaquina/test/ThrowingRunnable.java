package io.github.graphmaquina.test;

/**
 * A body of test code that is expected to fail, handed to
 * {@link TransitionFailureAssert#captureFailure(ThrowingRunnable)}. It declares {@code throws Exception} so a
 * lambda may call anything without wrapping it, and it returns nothing: the failure is the result.
 */
@FunctionalInterface
public interface ThrowingRunnable {

    void run() throws Exception;
}
