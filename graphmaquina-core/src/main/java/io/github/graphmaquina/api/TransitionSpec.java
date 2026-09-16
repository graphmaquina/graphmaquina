package io.github.graphmaquina.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/**
 * One declared edge of the graph: where it starts, which identifier selects it, where it ends, and everything
 * the runtime needs to execute it. The constructor validates what the runtime takes for granted.
 *
 * @param <E> entity type
 * @param <S> state enum
 * @param <T> transition identifier enum
 * @param from origin state
 * @param transition identifier that selects this edge from {@code from}
 * @param to destination state
 * @param action the action to run, absent for a plain move
 * @param guards guards evaluated in declaration order
 * @param onFailure identifier of the recovery edge, resolved from {@code from}
 * @param failsOn exception types treated as domain failures
 * @param timeout deadline of an asynchronous action
 * @param afterCommit hook run once this edge is committed
 */
public record TransitionSpec<E, S extends Enum<S>, T extends Enum<T>>(@NonNull S from, @NonNull T transition, @NonNull S to,
                                                                       @NonNull Optional<ActionRef<E, S, T>> action,
                                                                       @NonNull List<GuardRef<E, S, T>> guards,
                                                                       @NonNull Optional<T> onFailure,
                                                                       @NonNull List<Class<? extends Throwable>> failsOn,
                                                                       @NonNull Optional<Duration> timeout,
                                                                       @NonNull Optional<HookRef<E, S, T>> afterCommit) {

    public TransitionSpec {
        guards = List.copyOf(guards);
        failsOn = List.copyOf(failsOn);
        String where = from + " -" + transition + "-> " + to;
        if (action.isEmpty() && (onFailure.isPresent() || !failsOn.isEmpty())) {
            throw new IllegalArgumentException(where + ": onFailure and failsOn are only allowed with an action");
        }
        if (onFailure.isPresent() && onFailure.get() == transition) {
            throw new IllegalArgumentException(where + ": onFailure cannot be this very transition");
        }
        if (timeout.isPresent() && !(action.isPresent() && action.get().isAsync())) {
            throw new IllegalArgumentException(where + ": timeout is only allowed on an asynchronous action");
        }
        if (timeout.isPresent() && (timeout.get().isZero() || timeout.get().isNegative())) {
            throw new IllegalArgumentException(where + ": timeout must be positive");
        }
    }

    public TransitionKind kind() {
        return action.isPresent() ? TransitionKind.ACTION : TransitionKind.MOVE;
    }

    public TransitionKey<S, T> key() {
        return new TransitionKey<>(from, transition);
    }

    /** The action is asynchronous, or some guard is. */
    public boolean isAsync() {
        if (action.isPresent() && action.get().isAsync()) {
            return true;
        }
        for (GuardRef<E, S, T> guard : guards) {
            if (guard.isAsync()) {
                return true;
            }
        }
        return false;
    }
}
