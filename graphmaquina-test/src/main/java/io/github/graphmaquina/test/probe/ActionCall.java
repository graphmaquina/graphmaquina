package io.github.graphmaquina.test.probe;

import io.github.graphmaquina.test.internal.InputTypes;
import java.util.Objects;
import java.util.Optional;

/**
 * What an {@link ActionProbe} recorded about one invocation. The live {@code TransitionContext} is deliberately
 * not kept: the engine invalidates it when the transaction ends, so holding it would hand a test an object that
 * throws when read afterwards. Only the values read on entry are stored. The entity is the live instance and
 * keeps moving after the call, so read it for identity rather than for the state it had then.
 * Input is also the reference read on entry, not a deep copy; mutations to that object remain visible.
 *
 * @param entity the entity the action was invoked for
 * @param from the state the entity was in when the action was entered
 * @param to the state the transition was heading for
 * @param depth 0 at the root transition, n at the n-th level of cascade
 * @param input the operation argument, or empty when none was provided
 * @param <E> the entity type of the machine
 * @param <S> the state enum of the machine
 */
public record ActionCall<E, S extends Enum<S>>(E entity, S from, S to, int depth, Optional<?> input) {

    public ActionCall {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(input, "input");
    }

    /** Creates a call with no operation input, preserving the original constructor. */
    public ActionCall(E entity, S from, S to, int depth) {
        this(entity, from, to, depth, Optional.empty());
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
