package io.github.graphmaquina.processor.model;

import java.util.List;
import java.util.Optional;
import javax.lang.model.type.TypeMirror;

/**
 * A facade method, the identifier it executes, every edge its own declarations open, and the payload its
 * declaration takes, which is the type javac resolved for the second parameter.
 *
 * <p>{@code transitionId} is the identity all of the method's annotations agree on; it is never derived from an
 * action's name nor from the method's Java name. The payload belongs to the operation and not to an edge: an
 * edge reached by a watch or a cascade carries no argument of its own.</p>
 */
public record OperationModel(String name, String transitionId, List<TransitionModel> transitions,
                             Optional<TypeMirror> inputType) {

    public OperationModel {
        transitions = List.copyOf(transitions);
    }
}
