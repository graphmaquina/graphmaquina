package io.stategraph.api;

import java.util.Objects;

/** Result of a watch re-evaluation triggered after a commit. Never propagates: the commit already happened. */
public sealed interface Reaction permits Reaction.Done, Reaction.Aborted {

    MachineKey<?, ?> key();

    Object entity();

    /** A watch re-evaluation that ran to completion, carrying its outcome. */
    record Done(MachineKey<?, ?> key, Object entity, Outcome<?, ?> outcome) implements Reaction {
        public Done {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /** {@code cause} is RuntimeException, not StateMachineException, so that api does not depend on error. */
    record Aborted(MachineKey<?, ?> key, Object entity, RuntimeException cause) implements Reaction {
        public Aborted {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(cause, "cause");
        }
    }
}
