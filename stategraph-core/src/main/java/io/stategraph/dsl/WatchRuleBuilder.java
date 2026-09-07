package io.stategraph.dsl;

/** Stage that pairs a pending watch condition with the state it moves to when the condition holds. */
public interface WatchRuleBuilder<E, S extends Enum<S>, P, Q extends Enum<Q>> {

    WatchBuilder<E, S, P, Q> goTo(S target);
}
