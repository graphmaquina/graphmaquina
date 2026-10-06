package io.github.graphmaquina.fixtures;

/** What Kotlin, Groovy and Lombok's {@code @SneakyThrows} compile to: a checked exception thrown without a throws clause. */
public final class Sneaky {

    private Sneaky() { }

    /** Throws {@code error} whatever its type. It is declared to return, so that a caller writes {@code throw Sneaky.raise(e)}. */
    @SuppressWarnings("unchecked")
    public static <X extends Throwable> RuntimeException raise(Throwable error) throws X {
        throw (X) error;
    }
}
