package io.github.graphmaquina.test.probe;

import io.github.graphmaquina.test.internal.InputTypes;
import java.util.Objects;
import java.util.Optional;

/**
 * What an {@link ActionProbe} or a {@link ControlledAsyncAction} recorded about one invocation. The live
 * {@code TransitionContext} is deliberately not kept: the engine invalidates it when the transaction ends, and
 * from then on its peer, cascade and flow operations throw {@code StaleContextException}, so holding it would hand
 * a test an object that fails when used afterwards. Only the values read on entry are stored. The entity is the
 * live instance and keeps moving after the call, so read it for identity rather than for the state it had then.
 * Input is also the reference read on entry, not a deep copy; mutations to that object remain visible.
 *
 * <p>The identifier is recorded alongside the endpoints, because it is what selected this edge: two edges
 * that connect the same pair of states are distinguishable here and nowhere else.
 *
 * @param entity the entity the action was invoked for
 * @param from the state the entity was in when the action was entered
 * @param transition the identifier that selected the edge being executed
 * @param to the state the transition was heading for
 * @param depth 0 at the root transition, n at the n-th level of cascade; the exit of a sub-machine runs one level
 *     below the step that ended the child, unless the child stood in a terminal state while its parent was still
 *     running the step that entered the bound state: that exit runs once that step is over, one level below it
 * @param input the operation argument, or empty when none was provided
 * @param <E> the entity type of the machine
 * @param <S> the state enum of the machine
 * @param <T> the transition identifier enum of the machine
 */
public record ActionCall<E, S extends Enum<S>, T extends Enum<T>>(E entity, S from, T transition, S to, int depth,
        Optional<?> input) {

    public ActionCall {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(input, "input");
    }

    /** Creates a call with no operation input. */
    public ActionCall(E entity, S from, T transition, S to, int depth) {
        this(entity, from, transition, to, depth, Optional.empty());
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
