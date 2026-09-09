package io.github.graphmaquina.processor.model;

import io.github.graphmaquina.api.EdgeKind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.lang.model.type.TypeMirror;

/**
 * An ACTION or MOVE cell, possibly spanning several from states. {@code name} is the action name for ACTION (the
 * operation's name, or @Cell.action) and empty for MOVE.
 */
public record CellModel(List<String> from, String to, EdgeKind kind, String name, Optional<Binding> action, List<Binding> guards,
                        Optional<String> onFailure, List<TypeMirror> failsOn, Optional<Duration> timeout,
                        Optional<Binding> afterCommit) {

    public CellModel {
        from = List.copyOf(from);
        guards = List.copyOf(guards);
        failsOn = List.copyOf(failsOn);
    }

    public boolean async() {
        if (action.isPresent() && action.get().async()) {
            return true;
        }
        for (Binding guard : guards) {
            if (guard.async()) {
                return true;
            }
        }
        return false;
    }

    public List<Binding> bindings() {
        List<Binding> all = new ArrayList<>();
        action.ifPresent(all::add);
        all.addAll(guards);
        afterCommit.ifPresent(all::add);
        return all;
    }
}
