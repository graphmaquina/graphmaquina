package io.stategraph.runtime;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Unwraps CompletionException and ExecutionException by exact class, with a bounded loop. */
final class Unwrap {

    private static final int MAX_HOPS = 16;

    private Unwrap() { }

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
