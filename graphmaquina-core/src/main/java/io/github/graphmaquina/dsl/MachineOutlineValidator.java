package io.github.graphmaquina.dsl;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.graph.Graph;
import io.github.graphmaquina.graph.StrongComponents;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.NonNull;

/**
 * Structural checks over a {@link MachineOutline}: unique keys, policies and their reasons, terminals,
 * reachability, cycles, failure routes, watches and sub-machines, and, when none of those found a problem, the
 * automatic orders, the cycles of the automatic edges and the continuing routes ({@link AutomaticRules} and
 * {@link ContinuationRules}). The failure routes of an edge are the identifiers
 * its recovery rules name and the one its {@code onFailure} names. Shared by the DSL and the annotation processor, so
 * both report the same codes for these checks.
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
        submachines(outline, index, byKey, problems);
        // The automatic rules read routes and bindings that the checks above have just vouched for.
        if (problems.isEmpty()) {
            AutomaticRules.check(outline, index, problems);
            ContinuationRules.check(outline, index, problems);
        }
    }

    private static Map<EdgeKey, TransitionOutline> uniqueKeys(MachineOutline outline, OutlineIndex index,
            Problems problems) {
        Map<EdgeKey, TransitionOutline> byKey = new HashMap<>();
        for (TransitionOutline edge : outline.transitions()) {
            if (byKey.putIfAbsent(new EdgeKey(edge.from(), edge.transition()), edge) != null) {
                problems.add(DiagnosticCode.SG102,
                        index.stateIndex(edge.from()), index.transitionIndex(edge.transition()),
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
                problems.add(DiagnosticCode.SG102, index.stateIndex(policy.state()), -1,
                        "the state " + policy.state() + " declares two policies");
                continue;
            }
            if (policy.otherwise().isBlank()) {
                problems.add(DiagnosticCode.SG103, index.stateIndex(policy.state()), -1,
                        "the policy of " + policy.state() + " has a blank reason");
            }
            for (Map.Entry<String, String> rejection : policy.rejections().entrySet()) {
                if (rejection.getValue().isBlank()) {
                    problems.add(DiagnosticCode.SG103,
                            index.stateIndex(policy.state()), index.transitionIndex(rejection.getKey()),
                            "the rejection of " + rejection.getKey() + " in " + policy.state() + " has a blank reason");
                }
                if (byKey.containsKey(new EdgeKey(policy.state(), rejection.getKey()))) {
                    problems.add(DiagnosticCode.SG702,
                            index.stateIndex(policy.state()), index.transitionIndex(rejection.getKey()),
                            policy.state() + " both declares and rejects " + rejection.getKey());
                }
            }
        }
        for (String state : outline.states()) {
            if (!declared.contains(state)) {
                problems.add(DiagnosticCode.SG701,
                        index.stateIndex(state), -1, state + " declares no rejection policy");
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
                problems.add(DiagnosticCode.SG202, index.stateIndex(state), -1,
                        state + " declares no outgoing transition: declare it as a terminal state");
            }
            if (!exits.isEmpty() && terminal) {
                problems.add(DiagnosticCode.SG202,
                        index.stateIndex(state), -1, state + " is terminal but declares " + exits);
            }
        }
    }

    private static void reachability(MachineOutline outline, OutlineIndex index, Problems problems) {
        Set<String> reachable = index.topology().reachableFrom(outline.initial());
        for (String state : outline.states()) {
            if (!state.equals(outline.initial()) && !reachable.contains(state)) {
                problems.add(DiagnosticCode.SG203,
                        index.stateIndex(state), -1, state + " is unreachable from " + outline.initial());
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
                problems.add(DiagnosticCode.SG205, index.stateIndex(allowed), -1, "unused cycle permission " + allowed);
        Graph<String> restricted = full.inducedSubgraph(
            full.nodes().stream().filter(n -> !outline.cycleAllowed().contains(n)).toList());
        for (Set<String> component : StrongComponents.find(restricted))
            if (cyclic(component, restricted))
                problems.add(DiagnosticCode.SG204,
                        "unauthorized cycle " + StrongComponents.cycleWitness(restricted, component));
    }

    /** More than one node, or a single node that reaches itself. */
    static boolean cyclic(Set<String> component, Graph<String> graph) {
        if (component.size() > 1) {
            return true;
        }
        String only = component.iterator().next();
        return graph.successors(only).contains(only);
    }

    /**
     * Every edge's failure routes: first each distinct identifier its recovery rules name, in declaration order, then
     * the one its onFailure names. An edge without an action may declare neither (SG507); otherwise each identifier
     * must name another edge that leaves the same origin (SG301). Recovery rules are checked once per distinct
     * identifier; onFailure is checked separately even when it names the same identifier.
     */
    private static void failureRoutes(MachineOutline outline, OutlineIndex index,
            Map<EdgeKey, TransitionOutline> byKey, Problems problems) {
        for (TransitionOutline edge : outline.transitions()) {
            boolean action = edge.kind() == TransitionKind.ACTION;
            if (!edge.recoveryTransitions().isEmpty()) {
                if (!action) {
                    problems.add(DiagnosticCode.SG507,
                            index.stateIndex(edge.from()), index.transitionIndex(edge.transition()),
                            edge.describe() + " declares recover without an action");
                } else {
                    for (String recovery : new LinkedHashSet<>(edge.recoveryTransitions())) {
                        requireRoute(edge, recovery, "recovery " + recovery, index, byKey, problems);
                    }
                }
            }
            if (edge.onFailure().isPresent()) {
                String recovery = edge.onFailure().get();
                if (!action) {
                    problems.add(DiagnosticCode.SG507,
                            index.stateIndex(edge.from()), index.transitionIndex(edge.transition()),
                            edge.describe() + " declares onFailure without an action");
                } else {
                    requireRoute(edge, recovery, "onFailure = " + recovery, index, byKey, problems);
                }
            }
        }
    }

    /**
     * SG301 unless {@code recovery} names an edge, other than {@code edge} itself, that leaves the origin of
     * {@code edge}. {@code declared} is how the message quotes the declaration that names it.
     */
    private static void requireRoute(TransitionOutline edge, String recovery, String declared, OutlineIndex index,
            Map<EdgeKey, TransitionOutline> byKey, Problems problems) {
        if (recovery.equals(edge.transition())) {
            problems.add(DiagnosticCode.SG301, index.stateIndex(edge.from()), index.transitionIndex(edge.transition()),
                    edge.describe() + " declares " + declared + ", which is its own key");
        } else if (!byKey.containsKey(new EdgeKey(edge.from(), recovery))) {
            problems.add(DiagnosticCode.SG301, index.stateIndex(edge.from()), index.transitionIndex(edge.transition()),
                    edge.describe() + " declares " + declared + ", but no transition " + recovery + " leaves "
                            + edge.from());
        }
    }

    private static void watches(MachineOutline outline, OutlineIndex index,
            Map<EdgeKey, TransitionOutline> byKey, Problems problems) {
        for (WatchOutline watch : outline.watches()) {
            for (String transition : watch.transitions()) {
                if (!byKey.containsKey(new EdgeKey(watch.state(), transition))) {
                    problems.add(DiagnosticCode.SG401,
                            index.stateIndex(watch.state()), index.transitionIndex(transition),
                            "the watch of " + watch.state() + " executes " + transition
                                    + ", but no such transition leaves " + watch.state());
                }
            }
            if (outline.terminals().contains(watch.state())) {
                problems.add(DiagnosticCode.SG402, index.stateIndex(watch.state()), -1,
                        watch.state() + " is terminal and cannot watch anything");
            }
        }
    }

    /**
     * SG803 once per bound state that is terminal or holds more than one binding, then SG801, SG802 and SG804 for
     * every binding, a second one on the same state included. Each problem is filed under the bound state, and under
     * the identifier it concerns when it concerns one: an exit's identifier or a claimed one. Problems that share
     * their code, state and identifier keep the order they are found in: bindings in outline order, then within one
     * binding the child's terminal states in its declaration order, its exits in declaration order, and its claims in
     * the order {@code claims} finds them.
     */
    private static void submachines(MachineOutline outline, OutlineIndex index,
            Map<EdgeKey, TransitionOutline> byKey, Problems problems) {
        Map<String, Integer> bindings = new LinkedHashMap<>();
        for (SubmachineOutline submachine : outline.submachines()) {
            bindings.merge(submachine.state(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> bound : bindings.entrySet()) {
            String where = outline.name() + "." + bound.getKey();
            if (outline.terminals().contains(bound.getKey())) {
                problems.add(DiagnosticCode.SG803, index.stateIndex(bound.getKey()), -1,
                        where + " is terminal and cannot hold a sub-machine");
            }
            if (bound.getValue() > 1) {
                problems.add(DiagnosticCode.SG803,
                        index.stateIndex(bound.getKey()), -1, where + " holds more than one sub-machine");
            }
        }
        for (SubmachineOutline submachine : outline.submachines()) {
            exits(outline, submachine, index, byKey, problems);
            claims(outline, submachine, index, problems);
        }
    }

    /**
     * SG801 for each terminal state of the child without exactly one exit and for each exit on a state that is not
     * one; SG802 for each exit whose identifier has no edge from the bound state.
     */
    private static void exits(MachineOutline outline, SubmachineOutline submachine, OutlineIndex index,
            Map<EdgeKey, TransitionOutline> byKey, Problems problems) {
        String where = outline.name() + "." + submachine.state() + ": ";
        int node = index.stateIndex(submachine.state());
        Map<String, Integer> declared = new HashMap<>();
        for (ExitOutline exit : submachine.exits()) {
            declared.merge(exit.terminal(), 1, Integer::sum);
        }
        for (String terminal : submachine.childTerminals()) {
            int count = declared.getOrDefault(terminal, 0);
            if (count == 0) {
                problems.add(DiagnosticCode.SG801, node, -1,
                        where + "the terminal " + terminal + " of " + submachine.child() + " has no exit");
            } else if (count > 1) {
                problems.add(DiagnosticCode.SG801, node, -1,
                        where + "the terminal " + terminal + " of " + submachine.child() + " has more than one exit");
            }
        }
        Set<String> terminals = new HashSet<>(submachine.childTerminals());
        for (ExitOutline exit : submachine.exits()) {
            int identifier = index.transitionIndex(exit.transition());
            if (!terminals.contains(exit.terminal())) {
                problems.add(DiagnosticCode.SG801, node, identifier, where + "the exit on " + exit.terminal()
                        + " names no terminal of " + submachine.child());
            }
            if (!byKey.containsKey(new EdgeKey(submachine.state(), exit.transition()))) {
                problems.add(DiagnosticCode.SG802,
                        node, identifier, where + "the exit on " + exit.terminal() + " names "
                        + exit.transition() + ", which has no edge from " + submachine.state());
            }
        }
    }

    /**
     * SG804 for each identifier an exit names that is also claimed from the bound state: by each edge leaving it, in
     * outline order, first through its recovery rules and then through its onFailure, and last by its watch. A
     * claim repeated within one edge's recovery rules or within one watch is reported once.
     */
    private static void claims(MachineOutline outline, SubmachineOutline submachine, OutlineIndex index,
            Problems problems) {
        String state = submachine.state();
        String where = outline.name() + "." + state + ": ";
        int node = index.stateIndex(state);
        Set<String> reserved = new HashSet<>();
        for (ExitOutline exit : submachine.exits()) {
            reserved.add(exit.transition());
        }
        for (TransitionOutline edge : index.outgoing(state)) {
            for (String recovery : new LinkedHashSet<>(edge.recoveryTransitions())) {
                if (reserved.contains(recovery)) {
                    problems.add(DiagnosticCode.SG804, node, index.transitionIndex(recovery), where + recovery
                            + " is an exit of its sub-machine and cannot be a recovery of " + edge.transition());
                }
            }
            if (edge.onFailure().isPresent() && reserved.contains(edge.onFailure().get())) {
                String claimed = edge.onFailure().get();
                problems.add(DiagnosticCode.SG804, node, index.transitionIndex(claimed), where + claimed
                        + " is an exit of its sub-machine and cannot be the onFailure of " + edge.transition());
            }
        }
        for (WatchOutline watch : outline.watches()) {
            if (!watch.state().equals(state)) {
                continue;
            }
            for (String executed : new LinkedHashSet<>(watch.transitions())) {
                if (reserved.contains(executed)) {
                    problems.add(DiagnosticCode.SG804, node, index.transitionIndex(executed), where + executed
                            + " is an exit of its sub-machine and cannot be executed by a watch rule");
                }
            }
        }
    }
}
