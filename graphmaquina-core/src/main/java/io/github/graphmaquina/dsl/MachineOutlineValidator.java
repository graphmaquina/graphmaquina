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
 * Structural checks over a {@link MachineOutline}: unique keys, policies and their reasons, the kinds of the edges,
 * terminals, reachability, cycles, failure routes, watches and sub-machines, and, when none of those found a problem,
 * the automatic orders, the cycles of the automatic edges and the continuing routes ({@link AutomaticRules} and
 * {@link ContinuationRules}). The failure routes of an edge are the identifiers
 * its recovery rules name and the one its {@code onFailure} names. A gate is neither an exit of a state that is not
 * terminal nor a cycle; an external edge counts for both, and for reachability, as an ordinary edge does. Shared by the
 * DSL and the annotation processor, so both report the same codes for these checks.
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
        kinds(outline, index, problems);
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

    /**
     * SG111 for each gate that is no self-loop or declares more than guards, SG112 for each external edge, not also a
     * gate, that is a self-loop or declares anything, and SG113 once for each identifier whose edges are of more than
     * one kind: ordinary, gate or external, an edge marked as both counting as a gate.
     */
    private static void kinds(MachineOutline outline, OutlineIndex index, Problems problems) {
        Map<String, Map<String, List<String>>> kinds = new LinkedHashMap<>();
        for (TransitionOutline edge : outline.transitions()) {
            faults(edge, index, problems);
            kinds.computeIfAbsent(edge.transition(), (String id) -> new LinkedHashMap<>())
                    .computeIfAbsent(kindOf(edge), (String kind) -> new ArrayList<>()).add(edge.from());
        }
        for (Map.Entry<String, Map<String, List<String>>> identifier : kinds.entrySet()) {
            if (identifier.getValue().size() > 1) {
                List<String> parts = new ArrayList<>();
                for (Map.Entry<String, List<String>> kind : identifier.getValue().entrySet()) {
                    parts.add(kind.getKey() + " from " + String.join(", ", kind.getValue()));
                }
                problems.add(DiagnosticCode.SG113, -1, index.transitionIndex(identifier.getKey()),
                        identifier.getKey() + " is " + String.join(" and ", parts)
                                + "; all edges of one identifier are of one kind");
            }
        }
    }

    /** The kind of {@code edge} as SG113 names it: a gate, which an edge also marked external still is, or not. */
    private static String kindOf(TransitionOutline edge) {
        if (edge.gate()) {
            return "a gate";
        }
        return edge.external() ? "external" : "ordinary";
    }

    /** SG111 when {@code edge} is a gate with faults, SG112 when it is an external edge, not a gate, with faults. */
    private static void faults(TransitionOutline edge, OutlineIndex index, Problems problems) {
        if (edge.gate()) {
            List<String> faults = new ArrayList<>();
            if (!edge.from().equals(edge.to())) {
                faults.add("is no self-loop");
            }
            faults.addAll(declarations(edge));
            if (edge.external()) {
                faults.add("is external");
            }
            if (!faults.isEmpty()) {
                problems.add(DiagnosticCode.SG111, index.stateIndex(edge.from()),
                        index.transitionIndex(edge.transition()), edge.describe() + " is a gate but "
                                + String.join(", ", faults) + "; a gate is a self-loop that declares guards at most");
            }
        } else if (edge.external()) {
            List<String> faults = new ArrayList<>();
            if (edge.from().equals(edge.to())) {
                faults.add("is a self-loop");
            }
            if (edge.guarded()) {
                faults.add("declares a guard");
            }
            faults.addAll(declarations(edge));
            if (!faults.isEmpty()) {
                problems.add(DiagnosticCode.SG112, index.stateIndex(edge.from()),
                        index.transitionIndex(edge.transition()), edge.describe() + " is external but "
                                + String.join(", ", faults) + "; an external edge leads to another state and declares"
                                + " nothing else");
            }
        }
    }

    /**
     * What {@code edge} declares beyond its states and its guards, in this order: an action, onFailure, a recovery
     * rule, a hook of its own and the automatic mark; none of them fits a gate or an external edge.
     */
    private static List<String> declarations(TransitionOutline edge) {
        List<String> declared = new ArrayList<>();
        if (edge.kind() == TransitionKind.ACTION) {
            declared.add("declares an action");
        }
        if (edge.onFailure().isPresent()) {
            declared.add("declares onFailure");
        }
        if (!edge.recoveryTransitions().isEmpty()) {
            declared.add("declares a recovery rule");
        }
        if (edge.hooked()) {
            declared.add("declares a hook");
        }
        if (edge.automatic()) {
            declared.add("is automatic");
        }
        return declared;
    }

    /**
     * SG202 for a state that is not terminal and declares no edge but gates, and for a terminal state that declares
     * any edge, a gate included.
     */
    private static void exits(MachineOutline outline, OutlineIndex index, Problems problems) {
        for (String state : outline.states()) {
            List<String> exits = new ArrayList<>();
            boolean leaves = false;
            for (TransitionOutline edge : index.outgoing(state)) {
                exits.add(edge.transition());
                leaves |= !edge.gate();
            }
            boolean terminal = outline.terminals().contains(state);
            if (exits.isEmpty() && !terminal) {
                problems.add(DiagnosticCode.SG202, index.stateIndex(state), -1,
                        state + " declares no outgoing transition: declare it as a terminal state");
            } else if (!leaves && !terminal) {
                problems.add(DiagnosticCode.SG202, index.stateIndex(state), -1,
                        state + " declares only the gates " + exits + ", which leave nothing: declare an edge that"
                                + " leaves it");
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
     * authorisations in a set whose iteration order is not guaranteed. With no permission at all, the graph with the
     * allowed states removed is the graph itself, so its components are the ones already found.
     */
    private static void cycles(MachineOutline outline, OutlineIndex index, Problems problems) {
        Graph<String> full = index.topology();
        List<Set<String>> components = StrongComponents.find(full);
        Set<String> participating = new HashSet<>();
        for (Set<String> component : components)
            if (cyclic(component, full)) participating.addAll(component);
        for (String allowed : outline.cycleAllowed())
            if (!participating.contains(allowed))
                problems.add(DiagnosticCode.SG205, index.stateIndex(allowed), -1, "unused cycle permission " + allowed);
        if (outline.cycleAllowed().isEmpty()) {
            unauthorized(full, components, problems);
            return;
        }
        Graph<String> restricted = full.inducedSubgraph(
            full.nodes().stream().filter(n -> !outline.cycleAllowed().contains(n)).toList());
        unauthorized(restricted, StrongComponents.find(restricted), problems);
    }

    /** SG204 with one witness for each cyclic component of {@code components}, the components of {@code graph}. */
    private static void unauthorized(Graph<String> graph, List<Set<String>> components, Problems problems) {
        for (Set<String> component : components)
            if (cyclic(component, graph))
                problems.add(DiagnosticCode.SG204,
                        "unauthorized cycle " + StrongComponents.cycleWitness(graph, component));
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
     * must name another edge that leaves the same origin (SG301), neither a gate nor external (SG114). Recovery rules
     * are checked once per distinct identifier; onFailure is checked separately even when it names the same
     * identifier. The routes a gate or an external edge declares are SG111 or SG112 instead, so they are skipped here.
     */
    private static void failureRoutes(MachineOutline outline, OutlineIndex index,
            Map<EdgeKey, TransitionOutline> byKey, Problems problems) {
        for (TransitionOutline edge : outline.transitions()) {
            if (edge.gate() || edge.external()) {
                continue;
            }
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
     * {@code edge}; SG114 when that edge is a gate or external. {@code declared} is how the message quotes the
     * declaration that names it.
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
        } else {
            untakeable(byKey.get(new EdgeKey(edge.from(), recovery)), index, problems,
                    edge.describe() + " declares " + declared);
        }
    }

    /**
     * SG114 when {@code named}, the edge a failure route, an exit or a watch rule names, is a gate or external, which
     * this machine never takes; {@code who} is how the message begins.
     */
    private static void untakeable(TransitionOutline named, OutlineIndex index, Problems problems, String who) {
        if (named.gate() || named.external()) {
            problems.add(DiagnosticCode.SG114,
                    index.stateIndex(named.from()), index.transitionIndex(named.transition()),
                    who + ", but " + named.describe() + " is " + (named.gate() ? "a gate" : "external")
                            + ", which this machine never takes");
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
                } else {
                    untakeable(byKey.get(new EdgeKey(watch.state(), transition)), index, problems,
                            "the watch of " + watch.state() + " executes " + transition);
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
     * one; SG802 for each exit whose identifier has no edge from the bound state, and SG114 for each whose edge is a
     * gate or external.
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
            } else {
                untakeable(byKey.get(new EdgeKey(submachine.state(), exit.transition())), index, problems,
                        where + "the exit on " + exit.terminal() + " names " + exit.transition());
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
