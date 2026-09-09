package io.github.graphmaquina.dsl;

import io.github.graphmaquina.api.NamedCondition;
import io.github.graphmaquina.api.WatchCondition;

/** Stage that declares the rules of a watch, one condition at a time. */
public interface WatchBuilder<E, S extends Enum<S>, P, Q extends Enum<Q>> extends RowBuilder<E, S> {

    WatchRuleBuilder<E, S, P, Q> when(NamedCondition<E, P, Q> condition);

    WatchRuleBuilder<E, S, P, Q> when(String name, WatchCondition<E, P, Q> condition);
}
