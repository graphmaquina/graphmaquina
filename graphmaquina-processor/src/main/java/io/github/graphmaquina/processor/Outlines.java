package io.github.graphmaquina.processor;

import io.github.graphmaquina.dsl.ExitOutline;
import io.github.graphmaquina.dsl.MachineOutline;
import io.github.graphmaquina.dsl.PolicyOutline;
import io.github.graphmaquina.dsl.SubmachineOutline;
import io.github.graphmaquina.dsl.TransitionOutline;
import io.github.graphmaquina.dsl.WatchOutline;
import io.github.graphmaquina.processor.model.MachineModel;
import io.github.graphmaquina.processor.model.RecoveryModel;
import io.github.graphmaquina.processor.model.RejectionModel;
import io.github.graphmaquina.processor.model.RuleModel;
import io.github.graphmaquina.processor.model.StatePolicyModel;
import io.github.graphmaquina.processor.model.SubmachineModel;
import io.github.graphmaquina.processor.model.TransitionModel;
import io.github.graphmaquina.processor.model.WatchModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a model into the structural outline the DSL builds too: only the edges that were declared, each with the
 * identifiers its fallback and its recovery rules recover through, one policy per state that declares one or is
 * terminal, the identifiers each watch executes, and each sub-machine with the terminal states of its child and its
 * exits as declared. No pair is materialized.
 */
final class Outlines {

    private Outlines() { }

    static MachineOutline of(MachineModel model) {
        List<TransitionOutline> edges = new ArrayList<>();
        for (TransitionModel transition : model.allTransitions()) {
            List<String> recoveries = new ArrayList<>();
            for (RecoveryModel recovery : transition.recoveries()) {
                recoveries.add(recovery.transition());
            }
            for (String from : transition.from()) {
                edges.add(new TransitionOutline(from, transition.transitionId(), transition.to(), transition.kind(),
                        transition.onFailure(), recoveries));
            }
        }
        List<WatchOutline> watches = new ArrayList<>();
        for (WatchModel watch : model.watches()) {
            List<String> executed = new ArrayList<>();
            for (RuleModel rule : watch.rules()) {
                executed.add(rule.transition());
            }
            watches.add(new WatchOutline(watch.state(), executed));
        }
        List<SubmachineOutline> submachines = new ArrayList<>();
        for (SubmachineModel submachine : model.submachines()) {
            List<ExitOutline> exits = new ArrayList<>();
            for (SubmachineModel.Exit exit : submachine.exits()) {
                exits.add(new ExitOutline(exit.on(), exit.transition()));
            }
            submachines.add(new SubmachineOutline(submachine.state(),
                    submachine.child().facade().getSimpleName().toString(), submachine.childTerminals(), exits));
        }
        return new MachineOutline(model.facadeName(), model.types().stateNames(), model.types().transitionNames(),
                model.initial(), Set.copyOf(model.terminals()), Set.copyOf(model.cycleAllowed()), edges,
                policies(model), watches, submachines);
    }

    /**
     * One policy per state that declares one and per terminal state, which inherits the stable generated text.
     * A state that declares neither carries no policy, which is what {@code SG701} reports.
     */
    private static List<PolicyOutline> policies(MachineModel model) {
        Map<String, String> declared = new LinkedHashMap<>();
        for (StatePolicyModel policy : model.policies()) {
            declared.put(policy.state(), policy.otherwise());
        }
        Map<String, Map<String, String>> rejections = new LinkedHashMap<>();
        for (RejectionModel rejection : model.rejections()) {
            rejections.computeIfAbsent(rejection.from(), (String state) -> new LinkedHashMap<>())
                    .put(rejection.transition(), rejection.reason());
        }
        List<PolicyOutline> policies = new ArrayList<>();
        for (String state : model.types().stateNames()) {
            String otherwise = declared.get(state);
            if (otherwise == null && !model.terminals().contains(state)) {
                continue;
            }
            policies.add(new PolicyOutline(state,
                    otherwise != null ? otherwise : "terminal state " + state + " accepts no transitions",
                    rejections.getOrDefault(state, Map.of())));
        }
        return policies;
    }
}
