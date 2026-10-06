package io.github.graphmaquina.api;

/** Base of every {@code @StateGraphs} interface: the set of machines built together, closed together. */
public interface Machines extends AutoCloseable {

    /**
     * Closes the default executors of the set's machines, blocking until the operations they already accepted end.
     * An executor the application supplied is neither closed nor awaited.
     */
    @Override
    void close();
}
