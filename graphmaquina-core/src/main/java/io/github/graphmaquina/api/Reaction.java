package io.github.graphmaquina.api;

import lombok.NonNull;

/**
 * Result of a watch re-evaluation triggered after a commit. A throwable that is not an Error, thrown while looking the
 * owner up or while re-evaluating it, never propagates, because the commit that triggered the reaction already
 * happened: it becomes {@link Aborted}. An Error is not caught: it propagates unchanged to the caller whose commit
 * triggered the reaction.
 */
public sealed interface Reaction permits Reaction.Done, Reaction.Aborted {

    MachineKey<?, ?, ?> key();

    Object entity();

    /** A watch re-evaluation that ran to completion, carrying its outcome. */
    record Done(@NonNull MachineKey<?, ?, ?> key, @NonNull Object entity,
                @NonNull Outcome<?, ?, ?> outcome) implements Reaction {
    }

    /**
     * A re-evaluation that threw, naming the owner it re-evaluated, or an owner lookup that threw, naming the peer that
     * moved. {@code cause} is RuntimeException, not StateMachineException, so that api does not depend on error. A
     * re-evaluation's cause is its {@code CallFailedException}, which says how far the reaction got in writing states;
     * a checked exception an owner lookup throws without declaring it, or any other throwable that is not a
     * RuntimeException, arrives wrapped in {@link java.lang.reflect.UndeclaredThrowableException}.
     */
    record Aborted(@NonNull MachineKey<?, ?, ?> key, @NonNull Object entity,
                   @NonNull RuntimeException cause) implements Reaction {
    }
}
