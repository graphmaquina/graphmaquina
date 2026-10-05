package io.github.graphmaquina.api;

/**
 * Observer of the outcomes of a registry's calls: invoked once per {@code execute} and once per {@code reevaluate},
 * whether called directly or triggered by a watch reaction, after the reactions of that call. A call that throws
 * before reaching it is not notified, such as one that aborts, whose commit fails, whose hook throws or whose
 * reaction raises an Error. A cascade is part of its root's call. Nested reaction outcomes arrive in their own call, so
 * implementations never walk {@code reactions()}. A listener that throws an Exception, checked ones included, makes
 * that call throw {@code AfterCommitException}, which a reaction's call turns into an aborted reaction; an Error
 * propagates unchanged.
 */
@FunctionalInterface
public interface TransitionListener {

    void onOutcome(MachineKey<?, ?, ?> key, Outcome<?, ?, ?> outcome);
}
