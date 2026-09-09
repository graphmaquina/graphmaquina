package io.github.graphmaquina.api;

import java.util.concurrent.CompletionStage;

/** Asynchronous counterpart of {@link TransitionGuard}, returning its verdict as a {@link CompletionStage}. */
@FunctionalInterface
public interface AsyncTransitionGuard<E, S extends Enum<S>> {
    CompletionStage<GuardResult> test(GuardContext<E, S> ctx);
}
