package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.graph.Graph;
import io.github.graphmaquina.graph.StrongComponents;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.NonNull;

/**
 * Structural checks over a {@link MachineOutline}: unique keys, reasons, terminals, reachability, cycles,
 * failure routes and watches. Shared by the DSL and the annotation processor, so both report the same codes.
 */
public final class MachineOutlineValidator {

    private MachineOutlineValidator() { }

    /**
     * The identity of an edge: the pair itself, never its printed form {@code FROM/ID}, which two distinct pairs
     * share as soon as a name holds a {@code /}.
     */
    private record EdgeKey(String from, String transition) { }

    /** Problems sorted by code, then by the index of the origin state, then by the index of the identifier. */
    public static List<InvalidGraphException.Problem> validate(@NonNull MachineOutline outline) {
        Problems problems = new Problems();
        validate(outline, problems);
        return problems.sorted();
    }

    static void validate(MachineOutline outline, Problems problems) {
        OutlineIndex index = new OutlineIndex(outline);
        Map<EdgeKey, TransitionOutline> byKey = uniqueKeys(outline, index, problems);
        policies(outline, index, byKey, problems);
        exits(outline, index, problems);
        reachability(outline, index, problems);
        cycles(outline, index, problems);
        failureRoutes(outline, index, byKey, problems);
        watches(outline, index, byKey, problems);
    }

    private static Map<EdgeKey, TransitionOutline> uniqueKeys(MachineOutline outline, OutlineIndex index,
            Problems problems) {
        Map<EdgeKey, TransitionOutline> byKey = new HashMap<>();
        for (TransitionOutline edge : outline.transitions()) {
            if (byKey.putIfAbsent(new EdgeKey(edge.from(), edge.transition()), edge) != null) {
                problems.add("SG102", index.stateIndex(edge.from()), index.transitionIndex(edge.transition()),
                        "duplicate transition " + edge.key());
            }
        }
        return byKey;
    }

    private static void policies(MachineOutline outline, OutlineIndex index,
            Map<EdgeKey, TransitionOutline> byKey, Problems problems) {
        Set<String> declared = new HashSet<>();
        for (PolicyOutline policy : outline.policies()) {
            if (!declared.add(policy.state())) {
                problems.add("SG102", index.stateIndex(policy.state()), -1,
                        "the state " + policy.state() + " declares two policies");
                continue;
            }
            if (policy.otherwise().isBlank()) {
                problems.add("SG103", index.stateIndex(policy.state()), -1,
                        "the policy of " + policy.state() + " has a blank reason");
            }
            for (Map.Entry<String, String> rejection : policy.rejections().entrySet()) {
                if (rejection.getValue().isBlank()) {
                    problems.add("SG103", index.stateIndex(policy.state()), index.transitionIndex(rejection.getKey()),
                            "the rejection of " + rejection.getKey() + " in " + policy.state() + " has a blank reason");
                }
                if (byKey.containsKey(new EdgeKey(policy.state(), rejection.getKey()))) {
                    problems.add("SG702", index.stateIndex(policy.state()), index.transitionIndex(rejection.getKey()),
                            policy.state() + " both declares and rejects " + rejection.getKey());
                }
            }
        }
        for (String state : outline.states()) {
            if (!declared.contains(state)) {
                problems.add("SG701", index.stateIndex(state), -1, state + " declares no rejection policy");
            }
        }
    }

    private static void exits(MachineOutline outline, OutlineIndex index, Problems problems) {
        for (String state : outline.states()) {
            List<String> exits = new ArrayList<>();
            for (TransitionOutline edge : index.outgoing(state)) {
                exits.add(edge.transition());
            }
            boolean terminal = outline.terminals().contains(state);
            if (exits.isEmpty() && !terminal) {
                problems.add("SG202", index.stateIndex(state), -1,
                        state + " declares no outgoing transition: declare it as a terminal state");
            }
            if (!exits.isEmpty() && terminal) {
                problems.add("SG202", index.stateIndex(state), -1, state + " is terminal but declares " + exits);
            }
        }
    }

    private static void reachability(MachineOutline outline, OutlineIndex index, Problems problems) {
        Set<String> reachable = index.topology().reachableFrom(outline.initial());
        for (String state : outline.states()) {
            if (!state.equals(outline.initial()) && !reachable.contains(state)) {
                problems.add("SG203", index.stateIndex(state), -1, state + " is unreachable from " + outline.initial());
            }
        }
    }

    /**
     * Authorises cycles without enumerating them: every allowed state must take part in one, and the graph with
     * the allowed states removed must hold none. One witness per remaining cyclic component is enough.
     *
     * <p>An unused permission is filed under its state's declaration index, because the outline holds the
     * authorisations in a set whose iteration order is not guaranteed.
     */
    private static void cycles(MachineOutline outline, OutlineIndex index, Problems problems) {
        Graph<String> full = index.topology();
        Set<String> participating = new HashSet<>();
        for (Set<String> component : StrongComponents.find(full))
            if (cyclic(component, full)) participating.addAll(component);
        for (String allowed : outline.cycleAllowed())
            if (!participating.contains(allowed))
                problems.add("SG205", index.stateIndex(allowed), -1, "unused cycle permission " + allowed);
        Graph<String> restricted = full.inducedSubgraph(
            full.nodes().stream().filter(n -> !outline.cycleAllowed().contains(n)).toList());
        for (Set<String> component : StrongComponents.find(restricted))
            if (cyclic(component, restricted))
                problems.add("SG204", "unauthorized cycle " + StrongComponents.cycleWitness(restricted, component));
    }

    /** More than one node, or a single node that reaches itself. */
    private static boolean cyclic(Set<String> component, Graph<String> graph) {
        if (component.size() > 1) {
            return true;
        }
        String only = component.iterator().next();
        return graph.successors(only).contains(only);
    }

    private static void failureRoutes(MachineOutline outline, OutlineIndex index,
            Map<EdgeKey, TransitionOutline> byKey, Problems problems) {
        for (TransitionOutline edge : outline.transitions()) {
            if (edge.onFailure().isEmpty()) {
                continue;
            }
            String recovery = edge.onFailure().get();
            String where = edge.describe();
            if (edge.kind() != TransitionKind.ACTION) {
                problems.add("SG507", index.stateIndex(edge.from()), index.transitionIndex(edge.transition()),
                        where + " declares onFailure without an action");
                continue;
            }
            if (recovery.equals(edge.transition())) {
                problems.add("SG301", index.stateIndex(edge.from()), index.transitionIndex(edge.transition()),
                        where + " declares onFailure = " + recovery + ", which is its own key");
                continue;
            }
            if (!byKey.containsKey(new EdgeKey(edge.from(), recovery))) {
                problems.add("SG301", index.stateIndex(edge.from()), index.transitionIndex(edge.transition()),
                        where + " declares onFailure = " + recovery + ", but no transition " + recovery
                                + " leaves " + edge.from());
            }
        }
    }

    private static void watches(MachineOutline outline, OutlineIndex index,
            Map<EdgeKey, TransitionOutline> byKey, Problems problems) {
        for (WatchOutline watch : outline.watches()) {
            for (String transition : watch.transitions()) {
                if (!byKey.containsKey(new EdgeKey(watch.state(), transition))) {
                    problems.add("SG401", index.stateIndex(watch.state()), index.transitionIndex(transition),
                            "the watch of " + watch.state() + " executes " + transition
                                    + ", but no such transition leaves " + watch.state());
                }
            }
            if (outline.terminals().contains(watch.state())) {
                problems.add("SG402", index.stateIndex(watch.state()), -1,
                        watch.state() + " is terminal and cannot watch anything");
            }
        }
    }
}
