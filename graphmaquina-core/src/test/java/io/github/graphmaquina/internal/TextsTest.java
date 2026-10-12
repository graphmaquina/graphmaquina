package io.github.graphmaquina.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** How the library prints a value whose {@code toString()} the application wrote: printing never fails for it. */
class TextsTest {

    /** A value that prints whatever its supplier answers or throws. */
    private record Printed(Supplier<String> text) {
        @Override
        public String toString() {
            return text.get();
        }
    }

    @SuppressWarnings("unchecked")
    private static <X extends Throwable> void sneaky(Throwable thrown) throws X {
        throw (X) thrown;
    }

    @Test
    void aValuePrintsAsItsToString() {
        assertEquals("disk full", Texts.shown(new Printed(() -> "disk full")));
    }

    @Test
    void aNullValueIsRefused() {
        assertEquals("value", assertThrows(NullPointerException.class, () -> Texts.shown(null)).getMessage());
    }

    @Test
    void aToStringThatAnswersNullPrintsAMarker() {
        assertEquals("<toString returned null>", Texts.shown(new Printed(() -> null)));
    }

    @Test
    void aToStringThatThrowsPrintsTheClassOfWhatItThrew() {
        assertEquals("<toString threw java.lang.IllegalStateException>", Texts.shown(new Printed(() -> {
            throw new IllegalStateException("unprintable");
        })));
        assertEquals("<toString threw java.io.IOException>", Texts.shown(new Printed(() -> {
            TextsTest.<RuntimeException>sneaky(new IOException("unprintable"));
            return "never";
        })));
    }

    @Test
    void anErrorFromToStringPropagates() {
        AssertionError fatal = new AssertionError("broken toString");
        assertSame(fatal, assertThrows(AssertionError.class, () -> Texts.shown(new Printed(() -> {
            throw fatal;
        }))));
    }
}
