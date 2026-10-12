package io.github.graphmaquina.internal;

import java.util.Objects;

/**
 * How the library prints a value whose {@code toString()} the application wrote, such as a failure or a flow value,
 * in a log line or in the message of an exception it builds: printing it never fails for that reason. Not part of the
 * API.
 */
public final class Texts {

    private Texts() { }

    /**
     * {@code value.toString()}: {@code <toString returned null>} when that answers null, and
     * {@code <toString threw <class name>>} when it throws anything but an Error, a checked exception included. An
     * Error propagates.
     *
     * @param value the value to print
     * @return the text of {@code value}, never null
     * @throws NullPointerException if {@code value} is null
     */
    public static String shown(Object value) {
        Objects.requireNonNull(value, "value");
        try {
            String text = String.valueOf(value);
            return text != null ? text : "<toString returned null>";
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable unprintable) {
            return "<toString threw " + unprintable.getClass().getName() + ">";
        }
    }
}
