package io.github.graphmaquina.test;

import lombok.NonNull;

/**
 * Expected shape of one guard of an edge: the guard's public runtime name and whether it is asynchronous.
 * A list of these is what {@code TransitionAssert.hasGuardsExactly} compares against, so declaration order
 * and repeated names are both significant.
 */
public record GuardExpectation(@NonNull String name, boolean async) {

    public GuardExpectation {
    }
}
