package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.TransitionAction;

/** Stage that picks the kind of a freshly declared cell: action, move or forbid. */
public interface EdgeStart<E, S extends Enum<S>> {

    ActionEdge<E, S> action(TransitionAction<E, S> action);

    ActionEdge<E, S> action(String name, TransitionAction<E, S> action);

    AsyncActionEdge<E, S> asyncAction(AsyncTransitionAction<E, S> action);

    AsyncActionEdge<E, S> asyncAction(String name, AsyncTransitionAction<E, S> action);

    MoveEdge<E, S> move();

    RowBuilder<E, S> forbid(String reason);
}
