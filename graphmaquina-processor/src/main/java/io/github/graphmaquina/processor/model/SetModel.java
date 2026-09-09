package io.github.graphmaquina.processor.model;

import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.TypeElement;

/** Everything the set generator needs. */
public record SetModel(TypeElement type, String packageName, List<MachineAccessor> machines) {

    public SetModel {
        machines = List.copyOf(machines);
    }

    public String setName() {
        return type.getSimpleName().toString();
    }

    public boolean hasAsync() {
        for (MachineAccessor accessor : machines) {
            if (accessor.machine().hasAsync()) {
                return true;
            }
        }
        return false;
    }

    /** Union of the machines' instance dependencies, in accessor order, no repeats. */
    public List<TypeElement> dependencies() {
        List<TypeElement> owners = new ArrayList<>();
        for (MachineAccessor accessor : machines) {
            for (TypeElement owner : accessor.machine().instanceDependencies()) {
                boolean known = false;
                for (TypeElement seen : owners) {
                    if (seen.getQualifiedName().contentEquals(owner.getQualifiedName())) {
                        known = true;
                    }
                }
                if (!known) {
                    owners.add(owner);
                }
            }
        }
        return owners;
    }
}
