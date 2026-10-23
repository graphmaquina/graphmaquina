package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.test.GraphAssertionError;

/**
 * The check of the four fields by which a {@link GraphAssertionError} says what mismatched, and of the message they
 * render.
 */
final class GraphAssertionErrors {

    private GraphAssertionErrors() { }

    static void assertFailure(
            GraphAssertionError error, String context, String property, Object expected, Object actual) {
        assertEquals(context, error.context());
        assertEquals(property, error.property());
        assertEquals(expected, error.expected());
        assertEquals(actual, error.actual());
    }

    /** The fields of the error, then the message they render. */
    static void assertFailure(GraphAssertionError error, String context, String property,
            Object expected, Object actual, String message) {
        assertFailure(error, context, property, expected, actual);
        assertEquals(message, error.getMessage());
    }
}
