package io.github.graphmaquina.runtime;

import io.github.graphmaquina.error.StateMachineException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Unwraps CompletionException and ExecutionException by exact class, however deeply they are nested. A chain that
 * loops back on itself stops the walk at the first wrapper it reaches a second time.
 */
final class Unwrap {

    private Unwrap() { }

    /**
     * The cause of what a callback threw or completed its stage with, once unwrapped. A StateMachineException found
     * there is thrown as is instead of being returned, so an engine exception reaches the caller unchanged whether it
     * came bare or wrapped, thrown by the callback or through its stage, and whichever root it comes from: a
     * TransitionAbortedException from another root keeps details that describe that root.
     */
    static Throwable foreignCause(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof StateMachineException engine) {
            throw engine;
        }
        return cause;
    }

    static Throwable unwrap(Throwable error) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        while (visited.add(current) && isWrapper(current) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean isWrapper(Throwable error) {
        return error.getClass() == CompletionException.class || error.getClass() == ExecutionException.class;
    }
}
