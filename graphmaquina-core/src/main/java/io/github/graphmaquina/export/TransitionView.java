package io.github.graphmaquina.export;

import io.github.graphmaquina.api.TransitionKind;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/**
 * One declared edge seen only through names, so the renderer never depends on the entity or the enums. There is
 * no view of an absent edge: what was not declared is simply not here.
 *
 * @param from origin state name
 * @param transition identifier name
 * @param to destination state name
 * @param kind whether the edge runs an action
 * @param action the action's name, when it has one
 * @param guards the guards' names, in declaration order
 * @param async the edge's action or some guard is asynchronous
 * @param failureTransition the edge's fallback recovery identifier, its onFailure, when it declares one
 * @param watch the watch rules of {@code from} that execute this identifier, rendered
 * @param recoveries the edge's recovery rules, in declaration order; empty when it declares none
 * @param automatic the edge is automatic
 * @param automaticPosition the 1-based place of an automatic edge in the declared order of its state, which a
 *     state has only when it holds two or more automatic edges; empty for a manual edge and for the only automatic
 *     edge of a state
 * @param failureContinues the edge's {@code onFailure} route is a continuing one
 */
public record TransitionView(@NonNull String from, @NonNull String transition, @NonNull String to,
                             @NonNull TransitionKind kind, @NonNull Optional<String> action,
                             @NonNull List<String> guards, boolean async,
                             @NonNull Optional<String> failureTransition, @NonNull Optional<String> watch,
                             @NonNull List<RecoveryView> recoveries, boolean automatic,
                             @NonNull Optional<Integer> automaticPosition, boolean failureContinues) {

    /** Copies guard names and recovery rules while keeping their declaration order. */
    public TransitionView {
        guards = List.copyOf(guards);
        recoveries = List.copyOf(recoveries);
    }

    /** The view of a manual edge that declares no recovery rule. */
    public TransitionView(String from, String transition, String to, TransitionKind kind, Optional<String> action,
                          List<String> guards, boolean async, Optional<String> failureTransition,
                          Optional<String> watch) {
        this(from, transition, to, kind, action, guards, async, failureTransition, watch, List.of(), false,
                Optional.empty(), false);
    }
}
