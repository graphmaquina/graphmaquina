package io.github.graphmaquina.dsl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.NonNull;

/**
 * Structural model of a machine by names only: its states, its catalogue of transition identifiers, the edges
 * that were actually declared, the policies of every state and the watches. The DSL builds it from its drafts
 * and the annotation processor from the annotations, so the same validator runs in {@code build()} and in javac.
 * There is no expected number of pairs and no index by endpoints.
 *
 * @param name the machine's display name
 * @param states state names, in declaration order
 * @param catalog transition identifier names, in declaration order
 * @param initial the initial state
 * @param terminals states that accept no outgoing edge
 * @param cycleAllowed states authorised to take part in a cycle
 * @param transitions the declared edges, in public order
 * @param policies one policy per state
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
        if (states.isEmpty() || new HashSet<>(states).size() != states.size()) {
            throw new IllegalArgumentException(name + ": states must be non-empty and distinct");
        }
        if (new HashSet<>(catalog).size() != catalog.size()) {
            throw new IllegalArgumentException(name + ": transition identifiers must be distinct");
        }
        requireState(name, states, initial, "initial");
        for (String terminal : terminals) {
            requireState(name, states, terminal, "terminal");
        }
        for (String state : cycleAllowed) {
            requireState(name, states, state, "allowsCycle");
        }
        for (TransitionOutline edge : transitions) {
            requireState(name, states, edge.from(), "transition.from");
            requireState(name, states, edge.to(), "transition.to");
            requireTransition(name, catalog, edge.transition(), "transition.id");
            if (edge.onFailure().isPresent()) {
                requireTransition(name, catalog, edge.onFailure().get(), "transition.onFailure");
            }
        }
        for (PolicyOutline policy : policies) {
            requireState(name, states, policy.state(), "policy.state");
            for (String rejected : policy.rejections().keySet()) {
                requireTransition(name, catalog, rejected, "policy.reject");
            }
        }
        for (WatchOutline watch : watches) {
            requireState(name, states, watch.state(), "watch.state");
            for (String transition : watch.transitions()) {
                requireTransition(name, catalog, transition, "watch.transition");
            }
        }
    }

    private static void requireState(String name, List<String> states, String state, String role) {
        if (!states.contains(state)) {
            throw new IllegalArgumentException(name + ": " + role + " " + state + " is not one of " + states);
        }
    }

    private static void requireTransition(String name, List<String> catalog, String transition, String role) {
        if (!catalog.contains(transition)) {
            throw new IllegalArgumentException(name + ": " + role + " " + transition + " is not one of " + catalog);
        }
    }

    public int indexOf(String state) {
        return states.indexOf(state);
    }

    public int indexOfTransition(String transition) {
        return catalog.indexOf(transition);
    }

    /** The edges leaving {@code state}, in declaration order. */
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
