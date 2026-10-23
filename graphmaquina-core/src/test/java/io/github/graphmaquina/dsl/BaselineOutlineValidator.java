package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.graph.Graph;
import io.github.graphmaquina.graph.GraphEdge;
import io.github.graphmaquina.graph.StrongComponents;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The frozen structural validation of commit {@code 278640e}, kept only so that tests can compare against it.
 * It is a verbatim copy of {@code MachineOutlineValidator} as that commit published it, with five edits and no
 * others: the type is package-private and renamed, its private constructor follows the new name, the Lombok
 * {@code @NonNull} on the public entry point is dropped with its import because Lombok is not on the test
 * compile classpath, this comment replaces the original's own, and {@code failureRoutes} first runs
 * {@code recoveryRoutes}, which checks the recovery transitions {@link TransitionOutline} gained after that
 * commit the way this copy checks onFailure, once per distinct identifier. The one change since is structural: the
 * longer methods are cut into helpers that make the same calls in the same order, to meet the size checks of the
 * test sources. Nothing else was touched, nothing here may be copied back into production, and nothing here may be
 * optimised: its whole value is that it computes the problems the way the base did, and for an outline without
 * recovery transitions it answers exactly what the base answered. The graph code it calls is not frozen: it builds
 * and queries the live {@code Graph} and runs the live {@code StrongComponents.find} and {@code cycleWitness}, so
 * the differential compares validator logic and not graph algorithms; the cycle search and the cycle witness have
 * their own frozen-copy differentials ({@code CycleEnumerationDifferentialTest},
 * {@code CycleWitnessDifferentialTest}).
 *
 * @see MachineOutlineValidator the live validator this oracle is compared against
 */
final class BaselineOutlineValidator {

    private BaselineOutlineValidator() { }

    /** Problems sorted by code, then by the index of the origin state, then by the index of the identifier. */
    public static List<InvalidGraphException.Problem> validate(MachineOutline outline) {
        Problems problems = new Problems();
        validate(outline, problems);
        return problems.sorted();
    }

    static void validate(MachineOutline outline, Problems problems) {
        Map<String, TransitionOutline> byKey = uniqueKeys(outline, problems);
        policies(outline, byKey, problems);
        exits(outline, problems);
        reachability(outline, problems);
        cycles(outline, problems);
        failureRoutes(outline, byKey, problems);
        watches(outline, byKey, problems);
    }

    private static Map<String, TransitionOutline> uniqueKeys(MachineOutline outline, Problems problems) {
        Map<String, TransitionOutline> byKey = new HashMap<>();
        for (TransitionOutline edge : outline.transitions()) {
            if (byKey.putIfAbsent(edge.key(), edge) != null) {
                problems.add("SG102", outline.indexOf(edge.from()), outline.indexOfTransition(edge.transition()),
                        "duplicate transition " + edge.key());
            }
        }
        return byKey;
    }

    private static void policies(MachineOutline outline, Map<String, TransitionOutline> byKey, Problems problems) {
        Set<String> declared = new HashSet<>();
        for (PolicyOutline policy : outline.policies()) {
            if (!declared.add(policy.state())) {
                problems.add("SG102", outline.indexOf(policy.state()), -1,
                        "the state " + policy.state() + " declares two policies");
                continue;
            }
            policyReasons(outline, byKey, policy, problems);
        }
        for (String state : outline.states()) {
            if (!declared.contains(state)) {
                problems.add("SG701", outline.indexOf(state), -1, state + " declares no rejection policy");
            }
        }
    }

    private static void policyReasons(MachineOutline outline, Map<String, TransitionOutline> byKey,
            PolicyOutline policy, Problems problems) {
        if (policy.otherwise().isBlank()) {
            problems.add("SG103", outline.indexOf(policy.state()), -1,
                    "the policy of " + policy.state() + " has a blank reason");
        }
        for (Map.Entry<String, String> rejection : policy.rejections().entrySet()) {
            rejectionFindings(outline, byKey, policy, rejection, problems);
        }
    }

    private static void rejectionFindings(MachineOutline outline, Map<String, TransitionOutline> byKey,
            PolicyOutline policy, Map.Entry<String, String> rejection, Problems problems) {
        if (rejection.getValue().isBlank()) {
            problems.add("SG103", outline.indexOf(policy.state()), outline.indexOfTransition(rejection.getKey()),
                    "the rejection of " + rejection.getKey() + " in " + policy.state()
                            + " has a blank reason");
        }
        if (byKey.containsKey(policy.state() + "/" + rejection.getKey())) {
            problems.add("SG702", outline.indexOf(policy.state()), outline.indexOfTransition(rejection.getKey()),
                    policy.state() + " both declares and rejects " + rejection.getKey());
        }
    }

    private static void exits(MachineOutline outline, Problems problems) {
        for (String state : outline.states()) {
            terminalityAgainstExits(outline, state, problems);
        }
    }

    private static List<String> transitionsLeaving(MachineOutline outline, String state) {
        List<String> exits = new ArrayList<>();
        for (TransitionOutline edge : outline.outgoing(state)) {
            exits.add(edge.transition());
        }
        return exits;
    }

    private static void terminalityAgainstExits(MachineOutline outline, String state, Problems problems) {
        List<String> exits = transitionsLeaving(outline, state);
        boolean terminal = outline.terminals().contains(state);
        if (exits.isEmpty() && !terminal) {
            problems.add("SG202", outline.indexOf(state), -1,
                    state + " declares no outgoing transition: declare it as a terminal state");
        }
        if (!exits.isEmpty() && terminal) {
            problems.add("SG202", outline.indexOf(state), -1, state + " is terminal but declares " + exits);
        }
    }

    private static void reachability(MachineOutline outline, Problems problems) {
        Set<String> reachable = topology(outline).reachableFrom(outline.initial());
        for (String state : outline.states()) {
            if (!state.equals(outline.initial()) && !reachable.contains(state)) {
                problems.add("SG203", outline.indexOf(state), -1, state + " is unreachable from " + outline.initial());
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
    private static void cycles(MachineOutline outline, Problems problems) {
        Graph<String> full = topology(outline);
        Set<String> participating = new HashSet<>();
        for (Set<String> component : StrongComponents.find(full))
            if (cyclic(component, full)) participating.addAll(component);
        unusedCyclePermissions(outline, participating, problems);
        unauthorizedCycles(outline, full, problems);
    }

    private static void unusedCyclePermissions(MachineOutline outline, Set<String> participating,
            Problems problems) {
        for (String allowed : outline.cycleAllowed())
            if (!participating.contains(allowed))
                problems.add("SG205", outline.indexOf(allowed), -1, "unused cycle permission " + allowed);
    }

    private static void unauthorizedCycles(MachineOutline outline, Graph<String> full, Problems problems) {
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

    private static void failureRoutes(MachineOutline outline, Map<String, TransitionOutline> byKey, Problems problems) {
        for (TransitionOutline edge : outline.transitions()) {
            recoveryRoutes(outline, edge, byKey, problems);
            onFailureRoute(outline, edge, byKey, problems);
        }
    }

    private static void onFailureRoute(MachineOutline outline, TransitionOutline edge,
            Map<String, TransitionOutline> byKey, Problems problems) {
        if (edge.onFailure().isEmpty()) {
            return;
        }
        String recovery = edge.onFailure().get();
        String where = edge.describe();
        if (edge.kind() != TransitionKind.ACTION) {
            problems.add("SG507", outline.indexOf(edge.from()), outline.indexOfTransition(edge.transition()),
                    where + " declares onFailure without an action");
            return;
        }
        if (recovery.equals(edge.transition())) {
            problems.add("SG301", outline.indexOf(edge.from()), outline.indexOfTransition(edge.transition()),
                    where + " declares onFailure = " + recovery + ", which is its own key");
            return;
        }
        if (!byKey.containsKey(edge.from() + "/" + recovery)) {
            problems.add("SG301", outline.indexOf(edge.from()), outline.indexOfTransition(edge.transition()),
                    where + " declares onFailure = " + recovery + ", but no transition " + recovery
                            + " leaves " + edge.from());
        }
    }

    /**
     * Not part of the frozen copy: the recovery transitions of {@code edge}, checked the way {@code failureRoutes}
     * checks onFailure, once per distinct identifier.
     */
    private static void recoveryRoutes(MachineOutline outline, TransitionOutline edge,
            Map<String, TransitionOutline> byKey, Problems problems) {
        if (edge.recoveryTransitions().isEmpty()) {
            return;
        }
        String where = edge.describe();
        if (edge.kind() != TransitionKind.ACTION) {
            problems.add("SG507", outline.indexOf(edge.from()), outline.indexOfTransition(edge.transition()),
                    where + " declares recover without an action");
            return;
        }
        for (String recovery : new LinkedHashSet<>(edge.recoveryTransitions())) {
            recoveryRoute(outline, edge, recovery, where, byKey, problems);
        }
    }

    private static void recoveryRoute(MachineOutline outline, TransitionOutline edge, String recovery, String where,
            Map<String, TransitionOutline> byKey, Problems problems) {
        if (recovery.equals(edge.transition())) {
            problems.add("SG301", outline.indexOf(edge.from()), outline.indexOfTransition(edge.transition()),
                    where + " declares recovery " + recovery + ", which is its own key");
            return;
        }
        if (!byKey.containsKey(edge.from() + "/" + recovery)) {
            problems.add("SG301", outline.indexOf(edge.from()), outline.indexOfTransition(edge.transition()),
                    where + " declares recovery " + recovery + ", but no transition " + recovery
                            + " leaves " + edge.from());
        }
    }

    private static void watches(MachineOutline outline, Map<String, TransitionOutline> byKey, Problems problems) {
        for (WatchOutline watch : outline.watches()) {
            for (String transition : watch.transitions()) {
                if (!byKey.containsKey(watch.state() + "/" + transition)) {
                    problems.add("SG401", outline.indexOf(watch.state()), outline.indexOfTransition(transition),
                            "the watch of " + watch.state() + " executes " + transition
                                    + ", but no such transition leaves " + watch.state());
                }
            }
            if (outline.terminals().contains(watch.state())) {
                problems.add("SG402", outline.indexOf(watch.state()), -1,
                        watch.state() + " is terminal and cannot watch anything");
            }
        }
    }

    /** Node topology of the outline: parallel edges collapse, which is exactly what reachability needs. */
    static Graph<String> topology(MachineOutline outline) {
        List<GraphEdge<String>> arcs = new ArrayList<>();
        for (TransitionOutline edge : outline.transitions()) {
            arcs.add(new GraphEdge<>(edge.from(), edge.to()));
        }
        return Graph.of(outline.states(), arcs);
    }
}
