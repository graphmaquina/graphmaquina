package io.github.graphmaquina.api;

/**
 * How far a call got in writing states through the machines' setters. It says nothing about a database transaction
 * around the call, which commits or rolls back on its own, later. The names are stable identifiers.
 */
public enum StatesWritten {

    /** No setter was called. */
    NONE,

    /**
     * At least one setter was called and the writing did not finish: the setter that failed may or
     * may not have written.
     */
    PARTIAL,

    /** Every setter the call planned returned. */
    ALL
}
