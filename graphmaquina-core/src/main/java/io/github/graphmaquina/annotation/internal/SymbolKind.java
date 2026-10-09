package io.github.graphmaquina.annotation.internal;

/**
 * Identifies categories implemented by generated symbol types.
 *
 * <p>This is an internal generator and processor SPI. Applications should use the public symbol interfaces.
 */
public enum SymbolKind {
    ACTION,
    GUARD,
    HOOK,
    CONDITION,
    PROPERTY,
    LOOKUP
}
