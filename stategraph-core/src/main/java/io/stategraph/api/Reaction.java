package io.stategraph.api;

import lombok.NonNull;

/** Result of a watch re-evaluation triggered after a commit. Never propagates: the commit already happened. */
public sealed interface Reaction permits Reaction.Done, Reaction.Aborted {

    MachineKey<?, ?> key();

    Object entity();

    /** A watch re-evaluation that ran to completion, carrying its outcome. */
    record Done(@NonNull MachineKey<?, ?> key, @NonNull Object entity, @NonNull Outcome<?, ?> outcome) implements Reaction {
    }

    /** {@code cause} is RuntimeException, not StateMachineException, so that api does not depend on error. */
    record Aborted(@NonNull MachineKey<?, ?> key, @NonNull Object entity, @NonNull RuntimeException cause) implements Reaction {
    }
}
