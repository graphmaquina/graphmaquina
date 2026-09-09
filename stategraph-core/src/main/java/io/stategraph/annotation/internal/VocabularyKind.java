package io.stategraph.annotation.internal;

/**
 * Identifies the role of a generated vocabulary annotation.
 *
 * <p>This is an internal generator and processor SPI and is not an application extension point.
 */
public enum VocabularyKind {
    DEFINITION,
    ROW,
    ROWS,
    ACTION,
    MOVE,
    CELL,
    CELLS,
    FORBID,
    FORBIDS,
    WATCH,
    WATCHES,
    WHEN
}
