package io.github.graphmaquina.processor.model;

import io.github.graphmaquina.api.TransitionKind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.lang.model.type.TypeMirror;

/**
 * One declared edge of a graph, identified by {@code transitionId} and possibly leaving several origins.
 *
 * <p>{@code name} is the action's label for an ACTION edge — the operation's own name, or the selected action's
 * when the interface declares the edge itself — and empty for a MOVE. {@code onFailure} is the identifier of the
 * edge the fallback recovers through, and {@code recoveries} are the recovery rules the edge declares, in
 * declaration order, each naming the identifier of the edge it recovers through. Every one of those identifiers is
 * resolved from this edge's origin, and none is a destination state.</p>
 *
 * <p>{@code automatic} marks an edge the engine attempts by itself, and {@code onFailureContinues} an
 * {@code onFailure} route that continues the chain of automatic attempts; it is only read together with an
 * {@code onFailure}.</p>
 */
public record TransitionModel(String transitionId, List<String> from, String to, TransitionKind kind, String name,
                              Optional<Binding> action, List<Binding> guards, Optional<String> onFailure,
                              List<TypeMirror> failsOn, Optional<Duration> timeout, Optional<Binding> afterCommit,
                              List<RecoveryModel> recoveries, boolean automatic, boolean onFailureContinues) {

    public TransitionModel {
        from = List.copyOf(from);
        guards = List.copyOf(guards);
        failsOn = List.copyOf(failsOn);
        recoveries = List.copyOf(recoveries);
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
