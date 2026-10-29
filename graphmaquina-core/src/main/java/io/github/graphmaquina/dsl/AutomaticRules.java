package io.github.graphmaquina.dsl;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The structural rules about the automatic edges of a state, over a {@link MachineOutline}: its
 * {@code automaticOrder} (SG108), the guards inside that order (SG109) and the sub-machine a state may hold (SG807).
 * Each rule reports at most one problem per state, positioned at that state, but the one that an order names exactly
 * the automatic edges of its state, which reports up to three: the first identifier it names that is not one of them,
 * the first of them it leaves out, and the first identifier it names twice. The problems of one code come in the
 * order of the states, then of the cases of the rule. The rules of the continuing failure routes are in
 * {@link ContinuationRules}.
 */
final class AutomaticRules {

    private AutomaticRules() { }

    /** Runs the three rules; the caller has already found the outline free of the other structural problems. */
    static void check(MachineOutline outline, OutlineIndex index, Problems problems) {
        Map<String, List<AutomaticOrderOutline>> orders = new LinkedHashMap<>();
        for (AutomaticOrderOutline order : outline.automaticOrders()) {
            orders.computeIfAbsent(order.state(), (String state) -> new ArrayList<>()).add(order);
        }
        for (String state : outline.states()) {
            List<TransitionOutline> automatic = automaticEdges(index, state);
            List<AutomaticOrderOutline> declared = orders.getOrDefault(state, List.of());
            if (!orderIsOnlyDeclared(state, automatic, declared, index, problems)) {
                continue;
            }
            if (!namesExactly(state, automatic, declared.get(0), index, problems)) {
                continue;
            }
            unguardedBeforeAnother(state, automatic, declared.get(0), index, problems);
        }
        boundStates(outline, index, problems);
    }

    /** The edges leaving {@code state} that are automatic, in the order of the outline. */
    static List<TransitionOutline> automaticEdges(OutlineIndex index, String state) {
        List<TransitionOutline> automatic = new ArrayList<>();
        for (TransitionOutline edge : index.outgoing(state)) {
            if (edge.automatic()) {
                automatic.add(edge);
            }
        }
        return automatic;
    }

    /**
     * SG108 for a state that needs an order and has none, for one that has an order it cannot use, and for one that
     * has more than one. Answers whether the state has exactly one order and at least two automatic edges, which is
     * what the other rules need.
     */
    private static boolean orderIsOnlyDeclared(String state, List<TransitionOutline> automatic,
            List<AutomaticOrderOutline> declared, OutlineIndex index, Problems problems) {
        int node = index.stateIndex(state);
        if (declared.size() > 1) {
            problems.add(DiagnosticCode.SG108, node, -1, "state " + state + " declares " + declared.size()
                    + " automaticOrders, but a state has at most one");
        } else if (declared.isEmpty() && automatic.size() > 1) {
            problems.add(DiagnosticCode.SG108, node, -1, "state " + state + " has " + automatic.size()
                    + " automatic transitions, " + automatic.get(0).transition() + " and "
                    + automatic.get(1).transition() + ", and declares no automaticOrder");
        } else if (declared.size() == 1 && automatic.size() < 2) {
            problems.add(DiagnosticCode.SG108, node, -1,
                    "state " + state + " declares an automaticOrder but has fewer than two automatic transitions");
        }
        return declared.size() == 1 && automatic.size() > 1;
    }

    /**
     * SG108 for the first identifier {@code order} names that is not an automatic edge of the state, for the first
     * automatic edge it leaves out, and for the first identifier it names twice. Answers whether none was found.
     */
    private static boolean namesExactly(String state, List<TransitionOutline> automatic, AutomaticOrderOutline order,
            OutlineIndex index, Problems problems) {
        int node = index.stateIndex(state);
        Set<String> automaticIds = new LinkedHashSet<>();
        for (TransitionOutline edge : automatic) {
            automaticIds.add(edge.transition());
        }
        Set<String> seen = new HashSet<>();
        Optional<String> repeated = Optional.empty();
        boolean exact = true;
        for (String named : order.transitions()) {
            if (!automaticIds.contains(named) && exact) {
                problems.add(DiagnosticCode.SG108, node, -1, "the automaticOrder of " + state + " names " + named
                        + ", which is not an automatic transition from " + state);
                exact = false;
            }
            if (!seen.add(named) && repeated.isEmpty()) {
                repeated = Optional.of(named);
            }
        }
        exact &= reportMissing(state, automaticIds, seen, node, problems);
        if (repeated.isPresent()) {
            problems.add(DiagnosticCode.SG108, node, -1,
                    "the automaticOrder of " + state + " names " + repeated.get() + " twice");
        }
        return exact && repeated.isEmpty();
    }

    /** SG108 for the first automatic identifier of {@code automaticIds} that {@code named} lacks. */
    private static boolean reportMissing(String state, Set<String> automaticIds, Set<String> named, int node,
            Problems problems) {
        for (String automatic : automaticIds) {
            if (!named.contains(automatic)) {
                problems.add(DiagnosticCode.SG108, node, -1,
                        "the automaticOrder of " + state + " does not name " + automatic);
                return false;
            }
        }
        return true;
    }

    /**
     * SG109 for the first edge of the order that has no guard and is not the last: it is always chosen, so the one
     * after it never is.
     */
    private static void unguardedBeforeAnother(String state, List<TransitionOutline> automatic,
            AutomaticOrderOutline order, OutlineIndex index, Problems problems) {
        Map<String, TransitionOutline> byId = new LinkedHashMap<>();
        for (TransitionOutline edge : automatic) {
            byId.put(edge.transition(), edge);
        }
        List<String> names = order.transitions();
        for (int i = 0; i < names.size() - 1; i++) {
            if (!byId.get(names.get(i)).guarded()) {
                problems.add(DiagnosticCode.SG109, index.stateIndex(state), index.transitionIndex(names.get(i)),
                        "in the automaticOrder of " + state + ", " + names.get(i) + " has no guard and comes before "
                                + names.get(i + 1) + ", which could never be chosen");
                return;
            }
        }
    }

    /**
     * SG807 once per state that holds a sub-machine and has an automatic edge, for the first one in the outline's
     * order, whether or not it is an exit.
     */
    private static void boundStates(MachineOutline outline, OutlineIndex index, Problems problems) {
        Set<String> bound = new LinkedHashSet<>();
        for (SubmachineOutline submachine : outline.submachines()) {
            bound.add(submachine.state());
        }
        for (String state : bound) {
            List<TransitionOutline> automatic = automaticEdges(index, state);
            if (!automatic.isEmpty()) {
                String transition = automatic.get(0).transition();
                problems.add(DiagnosticCode.SG807, index.stateIndex(state), index.transitionIndex(transition),
                        "the automatic transition " + transition + " leaves " + state + ", which holds a sub-machine");
            }
        }
    }
}
