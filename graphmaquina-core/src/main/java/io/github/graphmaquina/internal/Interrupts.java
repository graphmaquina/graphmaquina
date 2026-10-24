package io.github.graphmaquina.internal;

import lombok.NonNull;

/**
 * How the library keeps a thread's interrupt when it contains what an application callback or backend threw instead
 * of letting it through: a blocking call that throws an {@link InterruptedException} clears the thread's interrupt
 * status first, so containing it would lose the interrupt, and the library interrupts the thread again. Not part of
 * the API.
 */
public final class Interrupts {

    private Interrupts() { }

    /**
     * Interrupts the current thread again when {@code contained}, which the library contains, is an
     * {@link InterruptedException}; does nothing for anything else.
     *
     * @param contained what the library contains instead of letting it through
     * @throws NullPointerException if {@code contained} is null
     */
    public static void keep(@NonNull Throwable contained) {
        if (contained instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
