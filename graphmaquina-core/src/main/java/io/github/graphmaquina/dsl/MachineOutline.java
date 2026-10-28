package io.github.graphmaquina.dsl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.NonNull;

/**
 * Structural model of a machine by names only: its states, its catalogue of transition identifiers, the edges
 * that were actually declared, the policies, the watches, the sub-machines and the automatic orders. The DSL builds it from its drafts and
 * the annotation processor from the annotations, so the same validator runs in {@code build()} and in javac, each time
 * only once that producer has found no problem of its own. There is no expected number of pairs and no index by
 * endpoints. The constructor refuses, with {@link IllegalArgumentException}, an empty state list, a repeated state or
 * identifier and any name that is not one of them, the identifiers that an edge's recovery rules name, whether they
 * continue or not, the state a sub-machine is bound to, the identifiers its exits name, and the state and the
 * identifiers of an automatic order included. The states a sub-machine's exits are declared
 * on and its terminal states are names of the child, which this outline does not hold, so the constructor does not
 * check them. Everything else, invalid combinations included, is left for the validator to report.
 *
 * @param name the machine's display name
 * @param states state names, in declaration order
 * @param catalog transition identifier names, in declaration order
 * @param initial the initial state
 * @param terminals states that accept no outgoing edge
 * @param cycleAllowed states authorised to take part in a cycle
 * @param transitions the declared edges, in the order the producer lists them: the DSL by origin, then
 *     identifier, then destination
 * @param policies the rejection policies; both producers give one to each state that declares one or is terminal,
 *     and none to any other state
 * @param watches the declared watches
 * @param submachines the declared sub-machine bindings, in the order the producer lists them: both producers in
 *     declaration order; a state may appear more than once, which the validator reports
 * @param automaticOrders every {@code automaticOrder} declared, in declaration order; a state may appear more than
 *     once, which the validator reports
 */
public record MachineOutline(@NonNull String name, @NonNull List<String> states, @NonNull List<String> catalog,
                             @NonNull String initial, @NonNull Set<String> terminals, @NonNull Set<String> cycleAllowed,
                             @NonNull List<TransitionOutline> transitions, @NonNull List<PolicyOutline> policies,
                             @NonNull List<WatchOutline> watches, @NonNull List<SubmachineOutline> submachines,
                             @NonNull List<AutomaticOrderOutline> automaticOrders) {

    public MachineOutline {
        states = List.copyOf(states);
        catalog = List.copyOf(catalog);
        terminals = Set.copyOf(terminals);
        cycleAllowed = Set.copyOf(cycleAllowed);
        transitions = List.copyOf(transitions);
        policies = List.copyOf(policies);
        watches = List.copyOf(watches);
        submachines = List.copyOf(submachines);
        automaticOrders = List.copyOf(automaticOrders);
        Set<String> stateNames = new HashSet<>(states);
        Set<String> transitionNames = new HashSet<>(catalog);
        if (states.isEmpty() || stateNames.size() != states.size()) {
            throw new IllegalArgumentException(name + ": states must be non-empty and distinct");
        }
        if (transitionNames.size() != catalog.size()) {
            throw new IllegalArgumentException(name + ": transition identifiers must be distinct");
        }
        requireState(name, states, stateNames, initial, "initial");
        for (String terminal : terminals) {
            requireState(name, states, stateNames, terminal, "terminal");
        }
        for (String state : cycleAllowed) {
            requireState(name, states, stateNames, state, "allowsCycle");
        }
        requireEdgeNames(name, states, stateNames, catalog, transitionNames, transitions);
        requirePolicyNames(name, states, stateNames, catalog, transitionNames, policies);
        requireWatchNames(name, states, stateNames, catalog, transitionNames, watches);
        requireSubmachineNames(name, states, stateNames, catalog, transitionNames, submachines);
        requireOrderNames(name, states, stateNames, catalog, transitionNames, automaticOrders);
    }

    /** Refuses an edge that names a state or an identifier the outline lacks, onFailure and recoveries included. */
    private static void requireEdgeNames(String name, List<String> states, Set<String> stateNames,
            List<String> catalog, Set<String> transitionNames, List<TransitionOutline> transitions) {
        for (TransitionOutline edge : transitions) {
            requireState(name, states, stateNames, edge.from(), "transition.from");
            requireState(name, states, stateNames, edge.to(), "transition.to");
            requireTransition(name, catalog, transitionNames, edge.transition(), "transition.id");
            if (edge.onFailure().isPresent()) {
                requireTransition(name, catalog, transitionNames, edge.onFailure().get(), "transition.onFailure");
            }
            for (String recovery : edge.recoveryTransitions()) {
                requireTransition(name, catalog, transitionNames, recovery, "transition.recovery");
            }
            for (String continuing : edge.continuingRecoveries()) {
                requireTransition(name, catalog, transitionNames, continuing, "transition.continuingRecovery");
            }
        }
    }

    /** Refuses a policy whose state, or an identifier it rejects, the outline lacks. */
    private static void requirePolicyNames(String name, List<String> states, Set<String> stateNames,
            List<String> catalog, Set<String> transitionNames, List<PolicyOutline> policies) {
        for (PolicyOutline policy : policies) {
            requireState(name, states, stateNames, policy.state(), "policy.state");
            for (String rejected : policy.rejections().keySet()) {
                requireTransition(name, catalog, transitionNames, rejected, "policy.reject");
            }
        }
    }

    /** Refuses a watch whose state, or an identifier its rules select, the outline lacks. */
    private static void requireWatchNames(String name, List<String> states, Set<String> stateNames,
            List<String> catalog, Set<String> transitionNames, List<WatchOutline> watches) {
        for (WatchOutline watch : watches) {
            requireState(name, states, stateNames, watch.state(), "watch.state");
            for (String transition : watch.transitions()) {
                requireTransition(name, catalog, transitionNames, transition, "watch.transition");
            }
        }
    }

    /** Refuses a sub-machine whose bound state, or an identifier its exits name, the outline lacks. */
    private static void requireSubmachineNames(String name, List<String> states, Set<String> stateNames,
            List<String> catalog, Set<String> transitionNames, List<SubmachineOutline> submachines) {
        for (SubmachineOutline submachine : submachines) {
            requireState(name, states, stateNames, submachine.state(), "submachine.state");
            for (ExitOutline exit : submachine.exits()) {
                requireTransition(name, catalog, transitionNames, exit.transition(), "submachine.exit");
            }
        }
    }

    /** Refuses an automatic order whose state, or an identifier it names, the outline lacks. */
    private static void requireOrderNames(String name, List<String> states, Set<String> stateNames,
            List<String> catalog, Set<String> transitionNames, List<AutomaticOrderOutline> automaticOrders) {
        for (AutomaticOrderOutline order : automaticOrders) {
            requireState(name, states, stateNames, order.state(), "automaticOrder.state");
            for (String transition : order.transitions()) {
                requireTransition(name, catalog, transitionNames, transition, "automaticOrder.transition");
            }
        }
    }

    /** An outline that declares no sub-machine and no automatic order. */
    public MachineOutline(String name, List<String> states, List<String> catalog, String initial, Set<String> terminals,
                          Set<String> cycleAllowed, List<TransitionOutline> transitions, List<PolicyOutline> policies,
                          List<WatchOutline> watches) {
        this(name, states, catalog, initial, terminals, cycleAllowed, transitions, policies, watches, List.of(),
                List.of());
    }

    private static void requireState(String name, List<String> states, Set<String> stateNames, String state,
            String role) {
        if (!stateNames.contains(state)) {
            throw new IllegalArgumentException(name + ": " + role + " " + state + " is not one of " + states);
        }
    }

    private static void requireTransition(String name, List<String> catalog, Set<String> transitionNames,
            String transition, String role) {
        if (!transitionNames.contains(transition)) {
            throw new IllegalArgumentException(name + ": " + role + " " + transition + " is not one of " + catalog);
        }
    }

    public int indexOf(String state) {
        return states.indexOf(state);
    }

    public int indexOfTransition(String transition) {
        return catalog.indexOf(transition);
    }

    /** The edges leaving {@code state}, in the order of {@link #transitions()}. */
    public List<TransitionOutline> outgoing(@NonNull String state) {
        List<TransitionOutline> leaving = new ArrayList<>();
        for (TransitionOutline edge : transitions) {
            if (edge.from().equals(state)) {
                leaving.add(edge);
            }
        }
        return List.copyOf(leaving);
    }
}
