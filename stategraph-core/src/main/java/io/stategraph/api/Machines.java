package io.stategraph.api;

/** Base of every {@code @StateGraphs} interface: the set of machines built together, closed together. */
public interface Machines extends AutoCloseable {

    /** Closes the executors the set owns. Blocks until the operations already submitted through async() end. */
    @Override
    void close();
}
