package io.github.graphmaquina.test.probe;

import java.util.Objects;

/**
 * What a {@link GuardProbe} recorded about one invocation. A guard decides and does not cascade, so there is no
 * depth to record; as with {@link ActionCall}, the live {@code GuardContext} is not kept.
 *
 * @param entity the entity the guard was asked about
 * @param from the state the entity was in when the guard was entered
 * @param to the state the transition was heading for
 * @param <E> the entity type of the machine
 * @param <S> the state enum of the machine
 */
public record GuardCall<E, S extends Enum<S>>(E entity, S from, S to) {

    public GuardCall {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }
}
