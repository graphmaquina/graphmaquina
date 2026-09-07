package io.stategraph.api;

import java.util.concurrent.CompletionStage;

/** Asynchronous counterpart of {@link TransitionAction}, returning its outcome as a {@link CompletionStage}. */
@FunctionalInterface
public interface AsyncTransitionAction<E, S extends Enum<S>> {
    CompletionStage<ActionOutcome> process(TransitionContext<E, S> ctx);
}
