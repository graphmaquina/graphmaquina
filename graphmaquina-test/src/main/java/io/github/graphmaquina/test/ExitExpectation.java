package io.github.graphmaquina.test;

import java.util.Objects;

/**
 * Expected exit of a sub-machine: a terminal state of the child and the identifier of the parent it selects. A list
 * of these is what {@code SubmachineAssert.hasExitsExactly} compares against, in declaration order.
 *
 * @param <Q> state enum of the child machine
 * @param <T> transition identifier enum of the parent machine
 * @param terminal a terminal state of the child
 * @param transition the identifier of the parent that the terminal state selects
 */
public record ExitExpectation<Q extends Enum<Q>, T extends Enum<T>>(Q terminal, T transition) {

    public ExitExpectation {
        Objects.requireNonNull(terminal, "terminal");
        Objects.requireNonNull(transition, "transition");
    }

    /** The exit through which {@code terminal} leaves the bound state: {@code transition}. */
    public static <Q extends Enum<Q>, T extends Enum<T>> ExitExpectation<Q, T> exit(Q terminal, T transition) {
        return new ExitExpectation<>(terminal, transition);
    }
}
