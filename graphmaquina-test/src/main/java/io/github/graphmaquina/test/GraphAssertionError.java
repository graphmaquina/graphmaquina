package io.github.graphmaquina.test;

import lombok.Getter;
import lombok.NonNull;

/**
 * Thrown by a typed assertion when its expectation is not met. Carries the context and property being
 * checked plus the expected and actual values, so a failure is inspectable without parsing its message.
 * When two distinct instances render the same text — the usual shape of an identity mismatch — the
 * message also names both instances, so it never ends at {@code expected <X> but was <X>}. A value whose
 * {@code toString()} throws anything but an {@link Error}, a checked exception included, as a lazily loaded
 * entity's does outside its session, or returns null is shown by its class and identity together with what
 * the call did: what it threw as that prints itself, or by its class when printing it throws too. So a failed
 * check over it still ends here, and an {@link InterruptedException} it threw interrupts the thread again. An
 * {@link Error} thrown by {@code toString()}, or by printing what it threw, is not caught: it propagates out
 * of the constructor, and so out of the check, instead. Advice passed to the five-argument constructor
 * travels in the message alone: no field stores it, so it never reaches {@link #actual()}, which holds the
 * value as the caller gave it.
 *
 * <p>An actual value that is a {@link Throwable} is the unexpected outcome itself, so it is also the cause
 * of this error: a test report then prints where it was thrown and not only what it was called.
 */
@Getter
public final class GraphAssertionError extends AssertionError {

    private final String context;
    private final String property;
    private final Object expected;
    private final Object actual;

    public GraphAssertionError(String context, String property, Object expected, Object actual) {
        this(context, property, expected, actual, null);
    }

    /** @param advice how to fix the failure, appended to the message on its own line and kept out of the
     *     inspectable fields; null when there is none */
    public GraphAssertionError(String context, String property, Object expected, Object actual,
            String advice) {
        super(render(context, property, expected, actual, advice));
        this.context = context;
        this.property = property;
        this.expected = expected;
        this.actual = actual;
        if (actual instanceof Throwable cause) {
            initCause(cause);
        }
    }

    private static String render(@NonNull String context, @NonNull String property, Object expected, Object actual,
            String advice) {
        String shownExpected = describe(expected);
        String shownActual = describe(actual);
        String message = context + ": " + property
                + " expected <" + shownExpected + "> but was <" + shownActual + ">";
        if (expected != actual && shownExpected.equals(shownActual)) {
            message = message + " (different instances: expected " + instanceOf(expected)
                    + ", actual " + instanceOf(actual) + ")";
        }
        return advice == null ? message : message + "\n" + advice;
    }

    /**
     * What a message shows of a value: its rendering, or its class and identity when its {@code toString()}
     * throws anything but an {@link Error} or returns null, which {@link String#valueOf(Object)} passes on as
     * null. An {@link InterruptedException} it threw interrupts the thread again. An {@link Error} is not
     * caught.
     */
    private static String describe(Object value) {
        String shown;
        try {
            shown = String.valueOf(value);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable failure) {
            keepInterrupt(failure);
            return instanceOf(value) + " (toString() threw " + printed(failure) + ")";
        }
        return shown == null ? instanceOf(value) + " (toString() returned null)" : shown;
    }

    /**
     * {@code failure} as it prints itself, or its class name when printing it throws anything but an
     * {@link Error} or answers null; an {@link InterruptedException} that printing it throws interrupts the
     * thread again. An {@link Error} is not caught.
     */
    private static String printed(Throwable failure) {
        try {
            String text = failure.toString();
            return text != null ? text : failure.getClass().getName();
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable unprintable) {
            keepInterrupt(unprintable);
            return failure.getClass().getName();
        }
    }

    /** Interrupts the thread again when {@code contained} is an {@link InterruptedException}. */
    private static void keepInterrupt(Throwable contained) {
        if (contained instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private static String instanceOf(Object value) {
        return value == null ? "null"
                : value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }
}
