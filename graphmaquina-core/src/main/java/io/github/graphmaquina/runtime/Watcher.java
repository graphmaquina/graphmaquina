package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.WatchSpec;
import java.util.Optional;

/** A machine that watches another from a state. Indexed by the peer's key. */
record Watcher<E, S extends Enum<S>, P, Q extends Enum<Q>>(DefaultStateMachine<E, S> machine, WatchSpec<E, S, P, Q> spec) {

    static <E, S extends Enum<S>, P, Q extends Enum<Q>> Watcher<E, S, P, Q> of(DefaultStateMachine<E, S> machine,
                                                                                WatchSpec<E, S, P, Q> spec) {
        return new Watcher<>(machine, spec);
    }

    /** Owner of the moved peer, if any, and exactly in the watched state. */
    Optional<EntityRef> ownerToReevaluate(Object movedInstance) {
        P peer = spec.peer().entity().cast(movedInstance);
        Optional<E> owner = spec.ownerOf().apply(peer);
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        E entity = owner.get();
        if (machine.readState(entity) != spec.state()) {
            return Optional.empty();
        }
        return Optional.of(machine.refOf(entity));
    }

    /** Re-evaluation as a new root transaction. Never propagates: the commit that triggered it already happened. */
    Reaction react(Object instance, int chain) {
        E entity = machine.key().entity().cast(instance);
        try {
            return new Reaction.Done(machine.key(), entity, machine.reevaluate(entity, chain));
        } catch (RuntimeException e) {
            return new Reaction.Aborted(machine.key(), entity, e);
        }
    }
}
