package io.github.graphmaquina.test.probe;

import java.util.Objects;

/**
 * What an {@link ActionProbe} recorded about one invocation. The live {@code TransitionContext} is deliberately
 * not kept: the engine invalidates it when the transaction ends, so holding it would hand a test an object that
 * throws when read afterwards. Only the values read on entry are stored. The entity is the live instance and
 * keeps moving after the call, so read it for identity rather than for the state it had then.
 *
 * @param entity the entity the action was invoked for
 * @param from the state the entity was in when the action was entered
 * @param to the state the transition was heading for
 * @param depth 0 at the root transition, n at the n-th level of cascade
 * @param <E> the entity type of the machine
 * @param <S> the state enum of the machine
 */
public record ActionCall<E, S extends Enum<S>>(E entity, S from, S to, int depth) {

    public ActionCall {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }
}
