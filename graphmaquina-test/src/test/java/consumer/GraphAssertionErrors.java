package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.test.GraphAssertionError;

/** The check of the four fields by which a {@link GraphAssertionError} says what mismatched. */
final class GraphAssertionErrors {

    private GraphAssertionErrors() { }

    static void assertFailure(
            GraphAssertionError error, String context, String property, Object expected, Object actual) {
        assertEquals(context, error.context());
        assertEquals(property, error.property());
        assertEquals(expected, error.expected());
        assertEquals(actual, error.actual());
    }
}
