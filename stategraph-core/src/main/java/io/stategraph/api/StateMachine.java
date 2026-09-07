package io.stategraph.api;

import java.util.Set;

/**
 * Governs the state S of entity E. Does not protect an entity against concurrent transitions:
 * that is the caller's responsibility.
 */
public interface StateMachine<E, S extends Enum<S>> {

    MachineKey<E, S> key();

    GraphDefinition<E, S> definition();

    /** Domain-level results as a value. Only a technical failure throws. Blocks on an asynchronous edge. */
    Outcome<E, S> transitionTo(E entity, S target);

    /** Evaluates the watch of the current state. Ignored if there is no watch or no rule holds. */
    Outcome<E, S> reevaluate(E entity);

    Set<S> allowedFrom(S state);

    /** The same machine, running on its executor. Never runs anything on the calling thread. */
    AsyncStateMachine<E, S> async();
}
