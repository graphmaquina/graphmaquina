package io.stategraph.api;

import java.util.concurrent.CompletableFuture;

/** Asynchronous counterpart of {@link StateMachine}, returning outcomes as futures instead of blocking. */
public interface AsyncStateMachine<E, S extends Enum<S>> {

    CompletableFuture<Outcome<E, S>> transitionTo(E entity, S target);

    CompletableFuture<Outcome<E, S>> reevaluate(E entity);
}
