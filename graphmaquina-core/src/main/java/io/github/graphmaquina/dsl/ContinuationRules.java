package io.github.graphmaquina.dsl;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.graph.Graph;
import io.github.graphmaquina.graph.GraphEdge;
import io.github.graphmaquina.graph.StrongComponents;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The structural rules about the failure routes that continue a chain, over a {@link MachineOutline}: a continuing
 * route leads to a state with an automatic edge (SG305), and the automatic edges together with the jumps of their
 * continuing routes hold no cycle (SG206). A route is the identifier a continuing recovery rule or a continuing
 * {@code onFailure} names, resolved from the origin of the edge that declares it, as the runtime resolves it. A route
 * that resolves to no edge is SG301's to report and is skipped here.
 */
final class ContinuationRules {

    private ContinuationRules() { }

    /** A continuing route of {@code edge} and the edge it resolves to. */
    private record Route(TransitionOutline edge, TransitionOutline recovery) { }

    /** Runs both rules; the caller has already found the outline free of the other structural problems. */
    static void check(MachineOutline outline, OutlineIndex index, Problems problems) {
        List<GraphEdge<String>> jumps = new ArrayList<>();
        for (TransitionOutline edge : outline.transitions()) {
            if (edge.automatic()) {
                jumps.add(new GraphEdge<>(edge.from(), edge.to()));
            }
            for (Route route : routes(edge, index)) {
                continuesIntoAutomatic(route, index, problems);
                if (edge.automatic()) {
                    jumps.add(new GraphEdge<>(edge.from(), route.recovery().to()));
                }
            }
        }
        cycles(Graph.of(outline.states(), jumps), problems);
    }

    /** The continuing routes of {@code edge}: its continuing recoveries in declaration order, then its onFailure. */
    private static List<Route> routes(TransitionOutline edge, OutlineIndex index) {
        Set<String> identifiers = new LinkedHashSet<>(edge.continuingRecoveries());
        if (edge.onFailureContinues() && edge.onFailure().isPresent()) {
            identifiers.add(edge.onFailure().get());
        }
        List<Route> routes = new ArrayList<>();
        for (String identifier : identifiers) {
            for (TransitionOutline candidate : index.outgoing(edge.from())) {
                if (candidate.transition().equals(identifier) && candidate != edge) {
                    routes.add(new Route(edge, candidate));
                    break;
                }
            }
        }
        return routes;
    }

    /** SG305 unless the recovery edge of {@code route} leads to a state with an automatic edge. */
    private static void continuesIntoAutomatic(Route route, OutlineIndex index, Problems problems) {
        TransitionOutline edge = route.edge();
        String destination = route.recovery().to();
        if (AutomaticRules.automaticEdges(index, destination).isEmpty()) {
            problems.add(DiagnosticCode.SG305, index.stateIndex(edge.from()), index.transitionIndex(edge.transition()),
                    "the failure route " + route.recovery().transition() + " of " + edge.describe()
                            + " continues the chain, but " + destination + " has no automatic transition");
        }
    }

    /** SG206 with one witness per cyclic component of {@code continuation}, whatever {@code allowsCycle} says. */
    private static void cycles(Graph<String> continuation, Problems problems) {
        for (Set<String> component : StrongComponents.find(continuation)) {
            if (MachineOutlineValidator.cyclic(component, continuation)) {
                problems.add(DiagnosticCode.SG206, "the automatic transitions form a cycle: "
                        + StrongComponents.cycleWitness(continuation, component));
            }
        }
    }
}
