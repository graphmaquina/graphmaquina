package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.AsyncTransitionGuard;
import io.github.graphmaquina.api.TransitionGuard;

/** Stage of a STAY cell: only guards apply. */
public interface StayEdge<E, S extends Enum<S>> extends RowBuilder<E, S> {

    StayEdge<E, S> guard(TransitionGuard<E, S> guard);

    StayEdge<E, S> guard(String name, TransitionGuard<E, S> guard);

    StayEdge<E, S> asyncGuard(AsyncTransitionGuard<E, S> guard);

    StayEdge<E, S> asyncGuard(String name, AsyncTransitionGuard<E, S> guard);
}
