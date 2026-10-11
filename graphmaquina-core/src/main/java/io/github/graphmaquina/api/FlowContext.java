package io.github.graphmaquina.api;

/**
 * The flow of one call, writable: a {@link FlowView} that can also put and remove values. An action receives it from
 * {@link TransitionContext#flow()}, which says when its writes are undone. Values are stored by reference and never
 * copied: a change made to a stored object is visible to every later read and is never undone, so prefer immutable
 * values.
 */
public interface FlowContext extends FlowView {

    /**
     * Stores {@code value} under {@code key}; a later read of an equal key answers it. The runtime log prints the
     * write at TRACE as {@code flow-put name:Type}, adding {@code =value} for a {@link FlowKey#visible} key.
     *
     * @throws NullPointerException if {@code key} or {@code value} is null
     * @throws ClassCastException if {@code value} is not an instance of {@code key.type()}
     */
    <V> void put(FlowKey<V> key, V value);

    /**
     * Removes the value of {@code key}, if any; a later read of an equal key answers empty. The runtime log prints the
     * write at TRACE as {@code flow-remove name:Type}.
     *
     * @throws NullPointerException if {@code key} is null
     */
    void remove(FlowKey<?> key);
}
