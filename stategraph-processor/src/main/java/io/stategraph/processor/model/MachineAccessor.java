package io.stategraph.processor.model;

/** One accessor of a @StateGraphs interface. */
public record MachineAccessor(String method, MachineModel machine) { }
