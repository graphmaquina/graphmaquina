package io.github.graphmaquina.test.probe;

import io.github.graphmaquina.test.internal.InputTypes;
import java.util.Optional;
import lombok.NonNull;

/**
 * What a {@link GuardProbe} or a {@link ControlledAsyncGuard} recorded about one invocation. A guard decides and
 * does not cascade, so there is no depth to record; as with {@link ActionCall}, the live {@code GuardContext} is
 * not kept and the identifier of the edge under evaluation is recorded alongside its endpoints. Input is the
 * reference read on entry, not a deep copy, and remains readable after the transaction ends.
 *
 * @param entity the entity the guard was asked about
 * @param from the state the entity was in when the guard was entered
 * @param transition the identifier that selected the edge being evaluated
 * @param to the state the transition was heading for
 * @param input the operation argument, or empty when none was provided
 * @param <E> the entity type of the machine
 * @param <S> the state enum of the machine
 * @param <T> the transition identifier enum of the machine
 */
public record GuardCall<E, S extends Enum<S>, T extends Enum<T>>(@NonNull E entity, @NonNull S from,
        @NonNull T transition, @NonNull S to, @NonNull Optional<?> input) {

    public GuardCall {
    }

    /** Creates a call with no operation input. */
    public GuardCall(E entity, S from, T transition, S to) {
        this(entity, from, transition, to, Optional.empty());
    }

    /**
     * Reads the recorded input as a reference type, including a supertype. A null token throws
     * {@link NullPointerException}, a primitive token throws {@link IllegalArgumentException} even for an
     * empty input, and an incompatible present value throws {@link ClassCastException}.
     */
    public <I> Optional<I> input(Class<I> type) {
        InputTypes.referenceType(type);
        return input.map(type::cast);
    }
}
