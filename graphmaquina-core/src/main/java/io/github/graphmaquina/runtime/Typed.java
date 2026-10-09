package io.github.graphmaquina.runtime;

import io.github.graphmaquina.api.ActionRef;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.SubmachineStart;
import io.github.graphmaquina.api.TransitionRecord;
import io.github.graphmaquina.api.TransitionSpec;

/**
 * The one place where a heterogeneous log entry is brought back to a machine's own generics. Every cast is
 * checked against the {@link MachineKey} that owns the entry, so the code that reads the log carries no suppression.
 */
final class Typed {

    private Typed() { }

    static <E, S extends Enum<S>, T extends Enum<T>> TransitionSpec<E, S, T> spec(MachineKey<E, S, T> key,
                                                                                  LogEntry.Hop entry) {
        key.states().cast(entry.spec().from());
        key.transitions().cast(entry.spec().transition());
        @SuppressWarnings("unchecked") // the enums just matched, and E is fixed by the machine this key identifies
        TransitionSpec<E, S, T> typed = (TransitionSpec<E, S, T>) entry.spec();
        return typed;
    }

    static <E, S extends Enum<S>, T extends Enum<T>> TransitionRecord<E, S, T> record(MachineKey<E, S, T> key,
                                                                                      LogEntry.Hop entry) {
        TransitionSpec<E, S, T> spec = spec(key, entry);
        return new TransitionRecord<>(key, key.entity().cast(entry.ref().instance()), spec.from(), spec.transition(),
                spec.to(), spec.kind(), spec.action().map(ActionRef::name), entry.depth());
    }

    /** The start of a child of the machine {@code key}: its entity and both states are checked against the key. */
    static <P, Q extends Enum<Q>, U extends Enum<U>> SubmachineStart<P, Q, U> start(MachineKey<P, Q, U> key,
                                                                                    LogEntry.Start entry) {
        return new SubmachineStart<>(key, key.entity().cast(entry.ref().instance()),
                key.states().cast(entry.previous()), key.states().cast(entry.initial()), entry.parent().key(),
                entry.parent().instance(), entry.node(), entry.depth());
    }
}
