package io.github.graphmaquina.processor.model;

/** The reason every identifier a state declares no edge for is refused with. */
public record StatePolicyModel(String state, String otherwise) { }
