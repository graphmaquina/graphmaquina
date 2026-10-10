package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.WatchSpec;
import java.util.Optional;

/** A machine that watches a peer machine, possibly itself, from a state. Indexed by the peer's key. */
record Watcher<E, S extends Enum<S>, T extends Enum<T>, P, Q extends Enum<Q>, U extends Enum<U>>(
        DefaultStateMachine<E, S, T> machine, WatchSpec<E, S, T, P, Q, U> spec) {

    static <E, S extends Enum<S>, T extends Enum<T>, P, Q extends Enum<Q>, U extends Enum<U>>
            Watcher<E, S, T, P, Q, U> of(DefaultStateMachine<E, S, T> machine, WatchSpec<E, S, T, P, Q, U> spec) {
        return new Watcher<>(machine, spec);
    }

    /**
     * Owner of the moved peer, if any, and exactly in the watched state. ownerOf, the state read and the identity
     * function run with the reentrancy marker set, so none of them can start a transition on this thread.
     */
    Optional<EntityRef> ownerToReevaluate(Object movedInstance) {
        P peer = spec.peer().entity().cast(movedInstance);
        Reentrancy.enter(spec.peer(), peer);
        try {
            Optional<E> owner = spec.ownerOf().apply(peer);
            if (owner.isEmpty()) {
                return Optional.empty();
            }
            E entity = owner.get();
            if (machine.readState(entity) != spec.state()) {
                return Optional.empty();
            }
            return Optional.of(machine.refOf(entity));
        } finally {
            Reentrancy.exit();
        }
    }

    /**
     * Re-evaluation of the owner: the edge its watch selects, if any, runs as a new root transaction. It does not
     * propagate the reevaluation's failure, because the commit that triggered it already happened: the failure, a
     * CallFailedException of the reevaluation, becomes {@link Reaction.Aborted}. An Error propagates unchanged.
     * {@code trigger} is the id of the call whose commit triggered the reaction, which the reaction's log names.
     */
    Reaction react(Object instance, int chain, Optional<Long> trigger) {
        E entity = machine.key().entity().cast(instance);
        try {
            return new Reaction.Done(machine.key(), entity, machine.reevaluate(entity, chain, trigger));
        } catch (RuntimeException e) {
            return new Reaction.Aborted(machine.key(), entity, e);
        }
    }
}
