package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.fixtures.Doc;
import java.io.IOException;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * How an {@link EntityRef} prints: {@code key#id}, the machine key kept whatever the id's {@code toString()} does, so
 * the log lines and the cascade path of a call still name the machine of an entity whose identity cannot be printed.
 */
class EntityRefTextTest {

    /** An identity that prints whatever its supplier answers or throws. */
    private record Id(Supplier<String> text) {
        @Override
        public String toString() {
            return text.get();
        }
    }

    @SuppressWarnings("unchecked")
    private static <X extends Throwable> void sneaky(Throwable thrown) throws X {
        throw (X) thrown;
    }

    private static EntityRef refOf(Supplier<String> text) {
        return new EntityRef(DOCS, new Id(text), new Doc("1", DRAFT));
    }

    @Test
    void anIdWhoseToStringThrowsPrintsAMarkerAfterTheMachineKey() {
        EntityRef ref = refOf(() -> {
            throw new IllegalStateException("unprintable");
        });
        assertEquals("Doc/DocStatus#<toString threw java.lang.IllegalStateException>", ref.toString());
    }

    @Test
    void anIdWhoseToStringThrowsACheckedExceptionPrintsAMarkerToo() {
        EntityRef ref = refOf(() -> {
            EntityRefTextTest.<RuntimeException>sneaky(new IOException("unprintable"));
            return "never";
        });
        assertEquals("Doc/DocStatus#<toString threw java.io.IOException>", ref.toString());
    }

    @Test
    void anIdWhoseToStringAnswersNullPrintsNullAfterTheMachineKey() {
        assertEquals("Doc/DocStatus#null", refOf(() -> null).toString());
    }

    @Test
    void anErrorFromTheIdsToStringPropagates() {
        AssertionError fatal = new AssertionError("broken toString");
        EntityRef ref = refOf(() -> {
            throw fatal;
        });
        assertSame(fatal, assertThrows(AssertionError.class, ref::toString));
    }
}
