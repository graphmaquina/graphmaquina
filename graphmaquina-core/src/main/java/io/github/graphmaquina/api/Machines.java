package io.github.graphmaquina.api;

/** Base of every {@code @StateGraphs} interface: the set of machines built together, closed together. */
public interface Machines extends AutoCloseable {

    /**
     * Closes the default executors of the set's machines, blocking until the operations they already accepted end.
     * An executor the application supplied is neither closed nor awaited. An interrupt of the calling thread, before
     * or during the wait, reaches every default executor not yet closed, not only the one awaited: each interrupts the
     * threads of its operations, so a call waiting for a stage aborts as interrupted, and this method still waits for
     * them to end, then returns with the thread interrupted.
     */
    @Override
    void close();
}
