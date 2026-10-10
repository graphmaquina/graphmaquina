package io.github.graphmaquina.dsl;

/**
 * Stage that maps the terminal states of a bound sub-machine to identifiers of the parent, one exit at a time. Each
 * exit is kept as declared, repeats included, so that {@code build()} reports a terminal state with no exit or more
 * than one as SG801 rather than dropping or replacing one. Once the graph is built, {@code exit(...)} and
 * {@code endSubmachine()} throw {@link IllegalStateException}.
 */
public interface SubmachineBuilder<E, S extends Enum<S>, T extends Enum<T>, P, Q extends Enum<Q>, U extends Enum<U>> {

    /** The exit of the child's {@code terminal}: the identifier of this graph it maps to. */
    SubmachineBuilder<E, S, T, P, Q, U> exit(Q terminal, T transition);

    /** Returns to the graph. It closes nothing: until {@code build()}, this stage still adds exits. */
    GraphBuilder<E, S, T> endSubmachine();
}
