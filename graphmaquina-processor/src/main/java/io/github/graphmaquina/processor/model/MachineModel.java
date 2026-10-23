package io.github.graphmaquina.processor.model;

import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.TypeElement;

/** Everything the generators need about one machine declared with the vocabulary generated for it. */
public record MachineModel(MachineTypes types, String packageName, String initial, List<String> terminals,
                           List<String> cycleAllowed, Property stateGetter, Property stateSetter, Property idGetter,
                           List<StatePolicyModel> policies, List<RejectionModel> rejections,
                           List<TransitionModel> transitions, List<OperationModel> operations,
                           List<WatchModel> watches, List<SubmachineModel> submachines) {

    public MachineModel {
        terminals = List.copyOf(terminals);
        cycleAllowed = List.copyOf(cycleAllowed);
        policies = List.copyOf(policies);
        rejections = List.copyOf(rejections);
        transitions = List.copyOf(transitions);
        operations = List.copyOf(operations);
        watches = List.copyOf(watches);
        submachines = List.copyOf(submachines);
    }

    public String facadeName() {
        return types.facade().getSimpleName().toString();
    }

    /**
     * Every declared edge: the ones the interface declares itself first, then the operations', in declaration order.
     */
    public List<TransitionModel> allTransitions() {
        List<TransitionModel> all = new ArrayList<>(transitions);
        for (OperationModel operation : operations) {
            all.addAll(operation.transitions());
        }
        return all;
    }

    public boolean hasAsync() {
        for (TransitionModel transition : allTransitions()) {
            if (transition.async()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The distinct machines this machine binds as sub-machines, in order of first appearance among its
     * {@code submachines}: the companion's {@code graph(...)} takes the definition of each, in this order.
     */
    public List<MachineTypes> children() {
        List<MachineTypes> children = new ArrayList<>();
        for (SubmachineModel submachine : submachines) {
            boolean known = false;
            for (MachineTypes child : children) {
                if (child.facade().getQualifiedName().contentEquals(submachine.child().facade().getQualifiedName())) {
                    known = true;
                }
            }
            if (!known) {
                children.add(submachine.child());
            }
        }
        return children;
    }

    /**
     * The classes with at least one instance member the companion calls, in order of first appearance and without
     * repeats: those of the edges the interface declares itself, then those of the watches, then the operations',
     * then the lookups of the sub-machines.
     */
    public List<TypeElement> instanceDependencies() {
        List<TypeElement> owners = new ArrayList<>();
        List<Binding> bindings = new ArrayList<>();
        for (TransitionModel transition : transitions) {
            bindings.addAll(transition.bindings());
        }
        for (WatchModel watch : watches) {
            bindings.addAll(watch.bindings());
        }
        for (OperationModel operation : operations) {
            for (TransitionModel transition : operation.transitions()) {
                bindings.addAll(transition.bindings());
            }
        }
        for (SubmachineModel submachine : submachines) {
            submachine.parentLookup().ifPresent(bindings::add);
        }
        for (Binding binding : bindings) {
            if (binding.isStatic()) {
                continue;
            }
            boolean known = false;
            for (TypeElement owner : owners) {
                if (owner.getQualifiedName().contentEquals(binding.owner().getQualifiedName())) {
                    known = true;
                }
            }
            if (!known) {
                owners.add(binding.owner());
            }
        }
        return owners;
    }
}
