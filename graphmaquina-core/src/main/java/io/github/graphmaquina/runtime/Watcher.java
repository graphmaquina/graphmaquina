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
     * What the owner lookup of the moved peer found: no owner, an owner outside the watched state, or an owner exactly
     * in it, the one to reevaluate. ownerOf, the state read and the identity function run with the reentrancy marker
     * set, so none of them can start a transition on this thread; the identity function runs only for an owner in the
     * watched state. An ownerOf that returns null instead of an Optional is refused with a NullPointerException that
     * says so.
     */
    Lookup lookUpOwner(Object movedInstance) {
        P peer = spec.peer().entity().cast(movedInstance);
        boolean[] marker = Reentrancy.enter(spec.peer(), peer);
        try {
            Optional<E> owner = spec.ownerOf().apply(peer);
            if (owner == null) {
                throw new NullPointerException(
                        "the ownerOf of " + machine.key() + "." + spec.state().name() + " returned null");
            }
            if (owner.isEmpty()) {
                return NoOwner.INSTANCE;
            }
            E entity = owner.get();
            S state = machine.readState(entity);
            if (state != spec.state()) {
                return new Unwatched(state);
            }
            return new Watched(machine.refOf(entity));
        } finally {
            marker[0] = false;
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

    /** What an owner lookup found: {@link NoOwner}, {@link Unwatched} or {@link Watched}. */
    sealed interface Lookup permits NoOwner, Unwatched, Watched { }

    /** The lookup found no owner. */
    record NoOwner() implements Lookup {

        /** The only instance, so that a lookup that finds no owner allocates nothing. */
        static final NoOwner INSTANCE = new NoOwner();
    }

    /** The lookup found an owner in {@code state}, which is not the watched state, so no reaction reevaluates it. */
    record Unwatched(Enum<?> state) implements Lookup { }

    /** The lookup found {@code owner} in the watched state, which a reaction reevaluates. */
    record Watched(EntityRef owner) implements Lookup { }
}
