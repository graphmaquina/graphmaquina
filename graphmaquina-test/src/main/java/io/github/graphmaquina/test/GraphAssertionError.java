package io.github.graphmaquina.test;

import java.util.Objects;

/**
 * Thrown by a typed assertion when its expectation is not met. Carries the context and property being
 * checked plus the expected and actual values, so a failure is inspectable without parsing its message.
 * When two distinct instances render the same text — the usual shape of an identity mismatch — the
 * message also names both instances, so it never reads as {@code expected <X> but was <X>}. A value whose
 * {@code toString()} throws, as a lazily loaded entity's does outside its session, or returns null is shown
 * by its class and identity together with what the call did, so a failed check over it still ends here.
 * Advice on how to fix the failure travels in the message alone: it is not part of what was observed, so it
 * never reaches {@link #actual()}.
 *
 * <p>An actual value that is a {@link Throwable} is the unexpected outcome itself, so it is also the cause
 * of this error: a test report then prints where it was thrown and not only what it was called.
 */
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

    public String context() { return context; }

    public String property() { return property; }

    public Object expected() { return expected; }

    public Object actual() { return actual; }

    private static String render(String context, String property, Object expected, Object actual,
            String advice) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(property, "property");
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
     * throws or returns null, which {@link String#valueOf(Object)} passes on as null.
     */
    private static String describe(Object value) {
        String shown;
        try {
            shown = String.valueOf(value);
        } catch (RuntimeException failure) {
            return instanceOf(value) + " (toString() threw " + failure + ")";
        }
        return shown == null ? instanceOf(value) + " (toString() returned null)" : shown;
    }

    private static String instanceOf(Object value) {
        return value == null ? "null"
                : value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }
}
