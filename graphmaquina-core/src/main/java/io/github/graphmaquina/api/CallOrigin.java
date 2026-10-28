package io.github.graphmaquina.api;

/**
 * Who made a call, as {@link MachineCall#origin()} reports it.
 */
public enum CallOrigin {

    /** The application called the machine. */
    APPLICATION,

    /** A watch reaction called it, as a call of its own, after a peer moved. */
    WATCH_REACTION
}
