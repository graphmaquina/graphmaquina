package io.stategraph.dsl;

import io.stategraph.api.GraphDefinition;

/** Top-level stage of the DSL: declares initial state, terminals, cycle allowances and rows, then builds. */
public interface GraphBuilder<E, S extends Enum<S>> {

    GraphBuilder<E, S> initial(S state);

    /** One per call: a generic varargs would trigger an unchecked warning at the call site. */
    GraphBuilder<E, S> terminal(S state);

    GraphBuilder<E, S> allowsCycle(S state);

    RowBuilder<E, S> from(S state);

    GraphDefinition<E, S> build();
}
