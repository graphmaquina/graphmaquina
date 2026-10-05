package io.github.graphmaquina.api;

import lombok.NonNull;

/**
 * Result of a watch re-evaluation triggered after a commit. An exception thrown while looking the owner up or while
 * re-evaluating it never propagates, because the commit that triggered the reaction already happened: it becomes
 * {@link Aborted}. An Error is not caught: it propagates unchanged to the caller whose commit triggered the reaction.
 */
public sealed interface Reaction permits Reaction.Done, Reaction.Aborted {

    MachineKey<?, ?, ?> key();

    Object entity();

    /** A watch re-evaluation that ran to completion, carrying its outcome. */
    record Done(@NonNull MachineKey<?, ?, ?> key, @NonNull Object entity, @NonNull Outcome<?, ?, ?> outcome) implements Reaction {
    }

    /**
     * A re-evaluation that threw, naming the owner it re-evaluated, or an owner lookup that threw, naming the peer
     * that moved. {@code cause} is RuntimeException, not StateMachineException, so that api does not depend on error.
     * A checked exception, which a watch condition or an owner lookup can throw without declaring it, arrives wrapped
     * in {@link java.lang.reflect.UndeclaredThrowableException}.
     */
    record Aborted(@NonNull MachineKey<?, ?, ?> key, @NonNull Object entity, @NonNull RuntimeException cause) implements Reaction {
    }
}
