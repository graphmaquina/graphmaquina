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
    REJECT,
    REJECTS,
    WATCH,
    WATCHES,
    WHEN
}
