package io.github.graphmaquina.test;

import java.util.Objects;

/**
 * Thrown by a typed assertion when its expectation is not met. Carries the context and property being
 * checked plus the expected and actual values, so a failure is inspectable without parsing its message.
 * When two distinct instances render the same text — the usual shape of an identity mismatch — the
 * message also names both instances, so it never reads as {@code expected <X> but was <X>}.
 */
public final class GraphAssertionError extends AssertionError {

    private final String context;
    private final String property;
    private final Object expected;
    private final Object actual;

    public GraphAssertionError(String context, String property, Object expected, Object actual) {
        super(render(context, property, expected, actual));
        this.context = context;
        this.property = property;
        this.expected = expected;
        this.actual = actual;
    }

    public String context() { return context; }

    public String property() { return property; }

    public Object expected() { return expected; }

    public Object actual() { return actual; }

    private static String render(String context, String property, Object expected, Object actual) {
        String message = Objects.requireNonNull(context, "context") + ": "
                + Objects.requireNonNull(property, "property")
                + " expected <" + expected + "> but was <" + actual + ">";
        if (expected == actual || !String.valueOf(expected).equals(String.valueOf(actual))) {
            return message;
        }
        return message + " (different instances: expected " + instanceOf(expected)
                + ", actual " + instanceOf(actual) + ")";
    }

    private static String instanceOf(Object value) {
        return value == null ? "null"
                : value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }
}
