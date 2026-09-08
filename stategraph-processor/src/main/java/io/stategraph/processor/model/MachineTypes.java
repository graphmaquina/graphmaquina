package io.stategraph.processor.model;

import java.util.List;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;

/** The types of a machine: its facade interface, the entity and the state enum, with the enum's constants in order. */
public record MachineTypes(TypeElement facade, DeclaredType entity, TypeElement entityElement, DeclaredType states,
                           TypeElement statesElement, List<String> stateNames) {

    public MachineTypes {
        stateNames = List.copyOf(stateNames);
    }

    public String entityName() {
        return entityElement.getSimpleName().toString();
    }

    public String statesName() {
        return statesElement.getSimpleName().toString();
    }

    public boolean hasState(String name) {
        return stateNames.contains(name);
    }
}
