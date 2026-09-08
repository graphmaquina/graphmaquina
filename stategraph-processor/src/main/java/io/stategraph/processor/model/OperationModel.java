package io.stategraph.processor.model;

/** A facade method and the cell(s) it triggers. */
public record OperationModel(String name, CellModel cell) { }
