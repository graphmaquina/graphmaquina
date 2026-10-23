package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.TransitionKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The large circulant outlines of the validator tests: {@code vertices} states in a ring, each leaving through
 * {@code degree} identifiers towards the next states, so that they hold far more cycles than could be listed.
 * {@link MachineOutlineValidatorTest} asserts on them and {@link OutlineValidationDifferentialTest} compares the
 * validator with its frozen oracle on the same ones.
 */
final class CirculantOutlines {

    private CirculantOutlines() { }

    /**
     * The outline of {@code vertices} states {@code s0}, {@code s1}, ..., each leaving through the identifiers
     * {@code step1} to {@code step<degree>} towards the next states; every state has a cycle authorisation when
     * {@code allowed}, none otherwise.
     */
    static MachineOutline of(int vertices, int degree, boolean allowed) {
        List<String> states = stateNames(vertices);
        List<String> catalog = stepNames(degree);
        List<TransitionOutline> transitions = new ArrayList<>();
        List<PolicyOutline> policies = new ArrayList<>();
        for (int from = 0; from < vertices; from++) {
            for (int offset = 1; offset <= degree; offset++) {
                transitions.add(new TransitionOutline("s" + from, "step" + offset, "s" + ((from + offset) % vertices),
                        TransitionKind.MOVE, Optional.empty()));
            }
            policies.add(new PolicyOutline("s" + from, "no edge with that identifier leaves s" + from, Map.of()));
        }
        Set<String> cycleAllowed = allowed ? Set.copyOf(states) : Set.of();
        return new MachineOutline("circulant", states, catalog, "s0", Set.of(), cycleAllowed, transitions, policies,
                List.of());
    }

    private static List<String> stateNames(int vertices) {
        List<String> states = new ArrayList<>();
        for (int i = 0; i < vertices; i++) {
            states.add("s" + i);
        }
        return states;
    }

    private static List<String> stepNames(int degree) {
        List<String> catalog = new ArrayList<>();
        for (int offset = 1; offset <= degree; offset++) {
            catalog.add("step" + offset);
        }
        return catalog;
    }
}
