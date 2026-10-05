package io.github.graphmaquina.runtime;

import io.github.graphmaquina.error.StateMachineException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Unwraps CompletionException and ExecutionException by exact class, with a bounded loop. */
final class Unwrap {

    private static final int MAX_HOPS = 16;

    private Unwrap() { }

    /**
     * The cause of what a callback threw or completed its stage with, once unwrapped. A StateMachineException found
     * there is thrown as is instead of being returned, so an engine exception reaches the caller unchanged whether it
     * came bare or wrapped, thrown by the callback or through its stage, and whichever root it comes from: one from
     * another root keeps details that describe that root.
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
        int hops = 0;
        while (hops < MAX_HOPS && visited.add(current) && isWrapper(current) && current.getCause() != null) {
            current = current.getCause();
            hops++;
        }
        return current;
    }

    private static boolean isWrapper(Throwable error) {
        return error.getClass() == CompletionException.class || error.getClass() == ExecutionException.class;
    }
}
