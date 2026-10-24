package io.github.graphmaquina.test.probe;

import java.util.Optional;
import lombok.NonNull;

/**
 * One call to a wrapped state setter, as the {@link StateWriteProbe} recorded it. The values are the ones the
 * probe was handed on entry; the entity is the live instance and keeps moving afterwards, so read it for
 * identity rather than for the state it had then.
 *
 * @param entity the entity whose state the engine was writing
 * @param requested the state the engine asked the setter to write
 * @param status whether the call is still running, returned or threw, which says nothing about what the
 *        setter stored
 * @param cause the exception the call threw, empty while it is still running, empty when it returned and
 *        also empty when what came out was not a {@link RuntimeException}, an {@link Error} for example
 * @param <E> the entity type of the machine the setter belongs to
 * @param <S> the state enum of that machine
 */
public record StateWrite<E, S extends Enum<S>>(@NonNull E entity, @NonNull S requested, @NonNull WriteStatus status,
        @NonNull Optional<RuntimeException> cause) {

    public StateWrite {
    }
}
