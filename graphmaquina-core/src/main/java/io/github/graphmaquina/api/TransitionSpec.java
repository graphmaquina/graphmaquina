package io.github.graphmaquina.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/**
 * One declared edge of the graph: where it starts, which identifier selects it, where it ends, and everything
 * the runtime needs to execute it. The constructor checks what one edge can check alone: onFailure and failsOn need
 * an action, and a timeout needs an asynchronous action and must be positive. That onFailure names an edge leaving
 * {@code from} is checked by the DSL when it builds the graph.
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
 * @param failsOn throwable types that turn an action's throwable into a domain failure, routed like a failed outcome;
 *     the throwable is unwrapped from CompletionException and ExecutionException first, and the engine never matches an
 *     Error, a CancellationException or a StateMachineException, whatever this list holds; the DSL also refuses those
 *     three and their subtypes, the supertypes Throwable, Exception and RuntimeException, and the wrappers
 *     CompletionException and ExecutionException (SG302). A StateMachineException from another call aborts the call
 *     with it as cause, and a failure of the call itself ends it as itself, or as the failure that poisoned the
 *     transaction
 * @param timeout longest wait for the asynchronous action's stage, counted from when the action returns it; it only
 *     shortens the root's deadline, and {@code Duration.ofNanos(Long.MAX_VALUE)} or more sets no limit of its own
 * @param afterCommit hook run once this edge is committed
 */
public record TransitionSpec<E, S extends Enum<S>, T extends Enum<T>>(
        @NonNull S from, @NonNull T transition, @NonNull S to,
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
