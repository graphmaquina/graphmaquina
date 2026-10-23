package io.github.graphmaquina.processor.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.TypeElement;

/**
 * Everything the set generator needs.
 *
 * <p>A machine is asynchronous in the set when its own graph has an asynchronous edge, or when an edge that the
 * engine can run implicitly from one of its calls is asynchronous: its action or one of its guards is. The implicit
 * edges of a call are the exit edges of every binding of the set's machines in which the machine is the child, and
 * then, for each implicit edge in turn: its failure routes, its onFailure edge and the edge of each recovery rule,
 * from the same origin; when it leaves its machine in a terminal state and that machine is the child of a binding,
 * the exit edge that the binding maps that state to, up the chain of parents; and when it leaves its machine in a
 * state that holds a sub-machine, every exit edge of that binding. Entering a binding through the caller's own
 * edge or an implicit edge also starts the child's initial binding chain: a terminal initial descendant selects its
 * mapped exit. Those selected exits count too; own edges and unrelated descendant edges do not. A cascade that an
 * action requests is explicit and counts for nothing. These are the machines {@code MachineRegistry} refuses to
 * assemble without an asyncTimeout, so the set gives the shared timeout to exactly those.</p>
 */
public record SetModel(TypeElement type, String packageName, List<MachineAccessor> machines) {

    public SetModel {
        machines = List.copyOf(machines);
    }

    public String setName() {
        return type.getSimpleName().toString();
    }

    public boolean hasAsync() {
        for (MachineAccessor accessor : machines) {
            if (asynchronous(accessor.machine())) {
                return true;
            }
        }
        return false;
    }

    /** Whether the set treats {@code machine} as asynchronous, as the type's comment describes. */
    public boolean asynchronous(MachineModel machine) {
        return machine.hasAsync() || runsAsynchronousEdgeImplicitly(machine);
    }

    /** The accessor of the set's machine whose facade is {@code facade}, if the set holds it. */
    public Optional<MachineAccessor> accessorOf(TypeElement facade) {
        for (MachineAccessor accessor : machines) {
            if (accessor.machine().types().facade().getQualifiedName().contentEquals(facade.getQualifiedName())) {
                return Optional.of(accessor);
            }
        }
        return Optional.empty();
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

    /** One edge of a machine of the set: {@code transition} from its origin {@code from}. */
    private record Edge(MachineModel machine, String from, TransitionModel transition) {

        /** {@code <Facade>.<FROM> -<ID>->}, which names the edge in the set. */
        String describe() {
            return machine.types().facade().getQualifiedName() + "." + from + " -" + transition.transitionId() + "->";
        }
    }

    /**
     * Whether a call of {@code machine} can run an asynchronous edge implicitly. The walk visits each edge once, so
     * it ends on any chain of bindings.
     */
    private boolean runsAsynchronousEdgeImplicitly(MachineModel machine) {
        Deque<Edge> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        Set<String> initialized = new HashSet<>();
        for (MachineAccessor accessor : machines) {
            for (SubmachineModel submachine : accessor.machine().submachines()) {
                if (isMachine(submachine.child(), machine)) {
                    enqueueExits(accessor.machine(), submachine, queue, visited);
                }
            }
        }
        for (TransitionModel transition : machine.allTransitions()) {
            enqueueInitialExits(machine, transition.to(), queue, visited, initialized);
        }
        while (!queue.isEmpty()) {
            Edge edge = queue.removeFirst();
            if (edge.transition().async()) {
                return true;
            }
            edge.transition().onFailure().ifPresent((String route) -> enqueue(edge.machine(), edge.from(), route,
                    queue, visited));
            for (RecoveryModel recovery : edge.transition().recoveries()) {
                enqueue(edge.machine(), edge.from(), recovery.transition(), queue, visited);
            }
            String reached = edge.transition().to();
            if (edge.machine().terminals().contains(reached)) {
                enqueueExitsOn(edge.machine(), reached, queue, visited);
            }
            for (SubmachineModel held : edge.machine().submachines()) {
                if (held.state().equals(reached)) {
                    enqueueExits(edge.machine(), held, queue, visited);
                    enqueueInitialExits(edge.machine(), reached, queue, visited, initialized);
                }
            }
        }
        return false;
    }

    /**
     * The exit edges on {@code terminal} of every sub-machine of the set whose child is {@code child}, which its
     * parent takes once the child reaches that terminal state.
     */
    private void enqueueExitsOn(MachineModel child, String terminal, Deque<Edge> queue, Set<String> visited) {
        for (MachineAccessor accessor : machines) {
            for (SubmachineModel submachine : accessor.machine().submachines()) {
                if (isMachine(submachine.child(), child)) {
                    for (SubmachineModel.Exit exit : submachine.exits()) {
                        if (exit.on().equals(terminal)) {
                            enqueue(accessor.machine(), submachine.state(), exit.transition(), queue, visited);
                        }
                    }
                }
            }
        }
    }

    /** The selected exits of terminal initial descendants when an edge enters {@code state}, without a cascade. */
    private void enqueueInitialExits(MachineModel parent, String state, Deque<Edge> queue, Set<String> visited,
                                     Set<String> initialized) {
        for (SubmachineModel held : parent.submachines()) {
            if (!held.state().equals(state)) {
                continue;
            }
            Optional<MachineAccessor> accessor = accessorOf(held.child().facade());
            if (accessor.isEmpty()) {
                continue;
            }
            MachineModel child = accessor.get().machine();
            if (child.terminals().contains(child.initial())) {
                for (SubmachineModel.Exit exit : held.exits()) {
                    if (exit.on().equals(child.initial())) {
                        enqueue(parent, held.state(), exit.transition(), queue, visited);
                    }
                }
            }
            // Distinct bindings can map the same terminal initial to different exits, so map before pruning.
            if (initialized.add(child.types().facade().getQualifiedName().toString())) {
                enqueueInitialExits(child, child.initial(), queue, visited, initialized);
            }
        }
    }

    /** Every exit edge of {@code submachine}, which {@code parent} declares. */
    private static void enqueueExits(MachineModel parent, SubmachineModel submachine, Deque<Edge> queue,
            Set<String> visited) {
        for (SubmachineModel.Exit exit : submachine.exits()) {
            enqueue(parent, submachine.state(), exit.transition(), queue, visited);
        }
    }

    /** The edge {@code id} from {@code from} of {@code machine}, unless it has none or the walk already reached it. */
    private static void enqueue(MachineModel machine, String from, String id, Deque<Edge> queue, Set<String> visited) {
        for (TransitionModel transition : machine.allTransitions()) {
            if (transition.transitionId().equals(id) && transition.from().contains(from)) {
                Edge edge = new Edge(machine, from, transition);
                if (visited.add(edge.describe())) {
                    queue.addLast(edge);
                }
            }
        }
    }

    /** Whether {@code types} are the types of {@code machine}, compared by facade. */
    private static boolean isMachine(MachineTypes types, MachineModel machine) {
        return types.facade().getQualifiedName().contentEquals(machine.types().facade().getQualifiedName());
    }
}
