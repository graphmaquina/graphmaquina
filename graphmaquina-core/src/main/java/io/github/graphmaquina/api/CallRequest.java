package io.github.graphmaquina.api;

/**
 * What a call asks of a machine, as {@link MachineCall#request()} reports it.
 */
public enum CallRequest {

    /** An {@code execute}: the call requests an identifier. */
    EXECUTE,

    /** A {@code reevaluate}: the machine selects the edge its watch picks, if any. */
    REEVALUATE,

    /** An {@code advance}: the machine takes the automatic edge of the entity's state, if any. */
    ADVANCE
}
