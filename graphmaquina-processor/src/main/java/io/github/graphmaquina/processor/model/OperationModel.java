package io.github.graphmaquina.processor.model;

import java.util.List;
import java.util.Optional;
import javax.lang.model.type.TypeMirror;

/**
 * A facade method, the identifier it executes, every edge its own declarations open, the payload its
 * declaration takes, which is the type javac resolved for the second parameter, and the names the method declares
 * its parameters with: {@code entityParameter} for the entity and {@code inputParameter} for the payload, present
 * exactly when {@code inputType} is.
 *
 * <p>{@code transitionId} is the identity all of the method's annotations agree on; it is never derived from an
 * action's name nor from the method's Java name. The payload belongs to the operation and not to an edge: an
 * edge reached by a watch or a cascade carries no argument of its own.</p>
 */
public record OperationModel(String name, String transitionId, List<TransitionModel> transitions,
                             Optional<TypeMirror> inputType, String entityParameter,
                             Optional<String> inputParameter) {

    public OperationModel {
        transitions = List.copyOf(transitions);
    }
}
