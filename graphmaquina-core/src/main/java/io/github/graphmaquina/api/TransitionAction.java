package io.github.graphmaquina.api;

/** Synchronous action executed during a transition, returning the outcome of the domain-level work. */
@FunctionalInterface
public interface TransitionAction<E, S extends Enum<S>, T extends Enum<T>> {
    ActionOutcome process(TransitionContext<E, S, T> ctx);
}
