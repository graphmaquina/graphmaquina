package io.github.graphmaquina.test;

import java.util.Objects;

/**
 * Expected shape of one guard of an edge: the guard's public runtime name and whether it is asynchronous.
 * A list of these is what {@code EdgeAssert.hasGuardsExactly} compares against, so declaration order and
 * repeated names are both significant.
 */
public record GuardExpectation(String name, boolean async) {

    public GuardExpectation {
        Objects.requireNonNull(name, "name");
    }
}
