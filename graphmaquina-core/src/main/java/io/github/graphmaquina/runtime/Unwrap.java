package io.github.graphmaquina.runtime;

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
