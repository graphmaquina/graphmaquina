package io.github.graphmaquina.processor.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A watch of a state over a peer machine: {@code peers} reads the peers of the machine's entity, and {@code owner}
 * finds the owner of a peer, through a property of the peer or an exported lookup.
 */
public record WatchModel(String state, MachineTypes peer, Property peers, WatchOwner owner, List<RuleModel> rules) {

    public WatchModel {
        rules = List.copyOf(rules);
    }

    /** The bindings the watch calls: the conditions of its rules, in order, then its owner lookup, if any. */
    public List<Binding> bindings() {
        List<Binding> all = new ArrayList<>();
        for (RuleModel rule : rules) {
            rule.condition().ifPresent(all::add);
        }
        if (owner instanceof WatchOwner.Lookup lookup) {
            all.add(lookup.binding());
        }
        return all;
    }
}
