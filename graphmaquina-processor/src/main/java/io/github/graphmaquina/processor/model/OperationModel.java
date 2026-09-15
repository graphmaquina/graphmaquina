package io.github.graphmaquina.processor.model;

import java.util.Optional;
import javax.lang.model.type.TypeMirror;

/**
 * A facade method, the cell(s) it triggers and the payload its declaration takes, which is the type javac
 * resolved for the second parameter. The payload belongs to the operation and not to the cell: a cell reached
 * by a watch or a cascade carries no argument of its own.
 */
public record OperationModel(String name, CellModel cell, Optional<TypeMirror> inputType) {

    /** An operation declared as {@code operation(E)}, which takes no input. */
    public OperationModel(String name, CellModel cell) {
        this(name, cell, Optional.empty());
    }
}
