package io.github.graphmaquina.api;

/** Synchronous guard that decides whether a transition may proceed. */
@FunctionalInterface
public interface TransitionGuard<E, S extends Enum<S>> {
    GuardResult test(GuardContext<E, S> ctx);
}
