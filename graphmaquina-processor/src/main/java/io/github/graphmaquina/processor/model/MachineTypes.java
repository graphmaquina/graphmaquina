package io.github.graphmaquina.processor.model;

import java.util.List;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;

/**
 * The types of a machine: its facade interface, the entity, the state enum and the transition enum, with the
 * constants of both enums in declaration order.
 */
public record MachineTypes(TypeElement facade, DeclaredType entity, TypeElement entityElement, DeclaredType states,
                           TypeElement statesElement, List<String> stateNames, DeclaredType transitions,
                           TypeElement transitionsElement, List<String> transitionNames) {

    public MachineTypes {
        stateNames = List.copyOf(stateNames);
        transitionNames = List.copyOf(transitionNames);
    }

    public String entityName() {
        return entityElement.getSimpleName().toString();
    }

    public String statesName() {
        return statesElement.getSimpleName().toString();
    }

    public String transitionsName() {
        return transitionsElement.getSimpleName().toString();
    }

    public boolean hasState(String name) {
        return stateNames.contains(name);
    }
}
