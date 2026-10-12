package io.github.graphmaquina.internal;

import java.util.Objects;

/**
 * How the library prints a value whose {@code toString()} the application wrote, such as a failure, a flow value or an
 * identity, in a log line or in the message of an exception it builds: printing it never fails for that reason. Not
 * part of the API.
 */
public final class Texts {

    private Texts() { }

    /**
     * {@code value.toString()}: {@code <toString returned null>} when that answers null, and
     * {@code <toString threw <class name>>} when it throws anything but an Error, a checked exception included, which
     * interrupts the thread again when it is an InterruptedException. An Error propagates.
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
            Interrupts.keep(unprintable);
            return "<toString threw " + unprintable.getClass().getName() + ">";
        }
    }

    /**
     * The text of an identity, printed after its machine key and {@code #}: {@code id.toString()}, {@code null} when
     * that answers null, and {@code <toString threw <class name>>} when it throws anything but an Error, a checked
     * exception included, which interrupts the thread again when it is an InterruptedException. An Error propagates.
     *
     * @param id the identity to print
     * @return the text of {@code id}, never null
     * @throws NullPointerException if {@code id} is null
     */
    public static String identity(Object id) {
        Objects.requireNonNull(id, "id");
        try {
            String text = id.toString();
            return text != null ? text : "null";
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable unprintable) {
            Interrupts.keep(unprintable);
            return "<toString threw " + unprintable.getClass().getName() + ">";
        }
    }
}
