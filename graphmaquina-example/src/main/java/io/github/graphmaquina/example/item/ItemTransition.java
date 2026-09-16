package io.github.graphmaquina.example.item;

/**
 * Identifiers of the item machine's transitions. They are what a caller, a cascade or a watch names; the
 * destination is read from the edge the identifier selects, never the other way round.
 */
public enum ItemTransition { START_PACKING, PACK, CANCEL }
