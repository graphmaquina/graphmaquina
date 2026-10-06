package io.github.graphmaquina.dsl;

/** Stage that pairs a pending watch condition with the transition it executes when the condition holds. */
public interface WatchRuleBuilder<E, S extends Enum<S>, T extends Enum<T>, P, Q extends Enum<Q>, U extends Enum<U>> {

    /** Pairs the pending condition with {@code transition}. With no condition pending, it is reported as SG404. */
    WatchBuilder<E, S, T, P, Q, U> execute(T transition);
}
