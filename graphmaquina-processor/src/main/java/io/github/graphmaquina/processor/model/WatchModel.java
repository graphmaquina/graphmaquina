package io.github.graphmaquina.processor.model;

import java.util.ArrayList;
import java.util.List;

/** A watch of a state over a peer machine. */
public record WatchModel(String state, MachineTypes peer, Property peers, Property owner, boolean ownerIsOptional, List<RuleModel> rules) {

    public WatchModel {
        rules = List.copyOf(rules);
    }

    public List<Binding> bindings() {
        List<Binding> all = new ArrayList<>();
        for (RuleModel rule : rules) {
            rule.condition().ifPresent(all::add);
        }
        return all;
    }
}
