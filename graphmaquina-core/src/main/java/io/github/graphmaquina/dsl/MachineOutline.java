package io.github.graphmaquina.dsl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.NonNull;

/**
 * Structural model of a machine by names only: its states, its catalogue of transition identifiers, the edges
 * that were actually declared, the policies and the watches. The DSL builds it from its drafts and the annotation
 * processor from the annotations, so the same validator runs in {@code build()} and in javac, each time only once
 * that producer has found no problem of its own. There is no expected number of pairs and no index by endpoints.
 * The constructor refuses, with {@link IllegalArgumentException}, an empty state list, a repeated state or
 * identifier and any name that is not one of them; everything else, invalid combinations included, is left for the
 * validator to report.
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
 */
public record MachineOutline(@NonNull String name, @NonNull List<String> states, @NonNull List<String> catalog,
                             @NonNull String initial, @NonNull Set<String> terminals, @NonNull Set<String> cycleAllowed,
                             @NonNull List<TransitionOutline> transitions, @NonNull List<PolicyOutline> policies,
                             @NonNull List<WatchOutline> watches) {

    public MachineOutline {
        states = List.copyOf(states);
        catalog = List.copyOf(catalog);
        terminals = Set.copyOf(terminals);
        cycleAllowed = Set.copyOf(cycleAllowed);
        transitions = List.copyOf(transitions);
        policies = List.copyOf(policies);
        watches = List.copyOf(watches);
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
        for (TransitionOutline edge : transitions) {
            requireState(name, states, stateNames, edge.from(), "transition.from");
            requireState(name, states, stateNames, edge.to(), "transition.to");
            requireTransition(name, catalog, transitionNames, edge.transition(), "transition.id");
            if (edge.onFailure().isPresent()) {
                requireTransition(name, catalog, transitionNames, edge.onFailure().get(), "transition.onFailure");
            }
        }
        for (PolicyOutline policy : policies) {
            requireState(name, states, stateNames, policy.state(), "policy.state");
            for (String rejected : policy.rejections().keySet()) {
                requireTransition(name, catalog, transitionNames, rejected, "policy.reject");
            }
        }
        for (WatchOutline watch : watches) {
            requireState(name, states, stateNames, watch.state(), "watch.state");
            for (String transition : watch.transitions()) {
                requireTransition(name, catalog, transitionNames, transition, "watch.transition");
            }
        }
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
    public List<TransitionOutline> outgoing(String state) {
        List<TransitionOutline> leaving = new ArrayList<>();
        for (TransitionOutline edge : transitions) {
            if (edge.from().equals(state)) {
                leaving.add(edge);
            }
        }
        return List.copyOf(leaving);
    }
}
