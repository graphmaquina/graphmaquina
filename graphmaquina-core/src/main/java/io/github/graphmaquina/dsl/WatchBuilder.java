package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.NamedCondition;
import io.github.graphmaquina.api.WatchCondition;

/** Stage that declares the rules of a watch, one condition at a time. */
public interface WatchBuilder<E, S extends Enum<S>, T extends Enum<T>, P, Q extends Enum<Q>, U extends Enum<U>> {

    WatchRuleBuilder<E, S, T, P, Q, U> when(NamedCondition<E, P, Q, U> condition);

    WatchRuleBuilder<E, S, T, P, Q, U> when(String name, WatchCondition<E, P, Q, U> condition);

    /** Closes the watch and returns to the graph. */
    GraphBuilder<E, S, T> endWatch();
}
