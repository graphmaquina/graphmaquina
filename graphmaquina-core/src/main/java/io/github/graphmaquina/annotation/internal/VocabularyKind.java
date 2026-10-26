package io.github.graphmaquina.annotation.internal;

/**
 * Identifies the role of a generated vocabulary annotation.
 *
 * <p>This is an internal generator and processor SPI and is not an application extension point.
 */
public enum VocabularyKind {
    DEFINITION,
    STATE_POLICY,
    STATE_POLICIES,
    TRANSITION,
    TRANSITIONS,
    /** One recovery rule, written only inside the {@code recoveries} of a {@link #TRANSITION}. */
    RECOVERY,
    REJECT,
    REJECTS,
    WATCH,
    WATCHES,
    WHEN,
    SUBMACHINE,
    SUBMACHINES,
    /** One exit of a sub-machine, written only inside the {@code exits} of a {@link #SUBMACHINE}. */
    EXIT,
    /** One method of a facade that checks, without running it, the operation that owns the identifier it names. */
    CHECK
}
