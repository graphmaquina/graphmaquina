package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A timeout is declared as seconds and a nanosecond adjustment, the two fields {@link java.time.Duration} is
 * built from, so nothing is rounded on the way in. The pair is checked before the duration is built: a zero or
 * negative timeout, and a nanosecond adjustment outside 0 to 999,999,999, are reported as {@code SG511}, a pair
 * {@code Duration.ofSeconds} overflows on among them, and the longest pair a {@code Duration} holds is kept.
 */
class TypedTimeoutTest {

    private static final String HEADER = """
            @DocsGraph.Definition(initial = DRAFT, terminals = PUBLISHED,
                state = DocRefs.Status.class, id = DocRefs.Id.class)
            @DocsGraph.StatePolicy(state = DRAFT, otherwise = "a draft is sent to review")
            @DocsGraph.StatePolicy(state = REVIEW, otherwise = "a review is published")
            """;

    @Test
    void keepsANanosecondOfTimeout() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER,
                archive(", timeout = @Timeout(nanos = 1)")));

        assertTrue(result.success(), result.describe());
        assertTrue(result.generated("fx.DocsRuntime").contains(".timeout(Duration.parse(\"PT0.000000001S\"))"),
                result.generated("fx.DocsRuntime"));
    }

    @Test
    void keepsTheLongestTimeoutTheDurationHolds() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER,
                archive(", timeout = @Timeout(seconds = Long.MAX_VALUE, nanos = 999999999)")));

        assertTrue(result.success(), result.describe());
        assertTrue(result.generated("fx.DocsRuntime")
                .contains(".timeout(Duration.parse(\"PT2562047788015215H30M7.999999999S\"))"),
                result.generated("fx.DocsRuntime"));
    }

    @Test
    void refusesATimeoutOfZero() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER,
                archive(", timeout = @Timeout")));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG511"), result.describe());
    }

    @Test
    void refusesANegativeTimeout() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER,
                archive(", timeout = @Timeout(seconds = -1)")));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG511"), result.describe());
    }

    @Test
    void refusesANanosecondAdjustmentOutOfRange() {
        Compilation.Result above = Compilation.compileTyped(TypedFixtures.graph(HEADER,
                archive(", timeout = @Timeout(seconds = 1, nanos = 1000000000)")));

        assertFalse(above.success(), above.describe());
        assertTrue(above.hasError("SG511"), above.describe());

        Compilation.Result below = Compilation.compileTyped(TypedFixtures.graph(HEADER,
                archive(", timeout = @Timeout(seconds = 1, nanos = -1)")));

        assertFalse(below.success(), below.describe());
        assertTrue(below.hasError("SG511"), below.describe());
    }

    /**
     * {@code Duration.ofSeconds} overflows only when the nanosecond adjustment moves the seconds out of the range of
     * a {@code long}, which an adjustment from 0 to 999,999,999 never does, so a pair it overflows on is refused as
     * {@code SG511} like any other adjustment out of range, before a duration is built, and nothing else is reported.
     */
    @Test
    void refusesAPairTheDurationWouldOverflowOn() {
        assertThrows(ArithmeticException.class, () -> Duration.ofSeconds(Long.MAX_VALUE, 1_000_000_000));

        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER,
                archive(", timeout = @Timeout(seconds = Long.MAX_VALUE, nanos = 1000000000)")));

        assertFalse(result.success(), result.describe());
        assertEquals(List.of("[SG511] timeout must be a positive duration: seconds 9223372036854775807 and nanos"
                + " 1000000000 must not be negative, nanos must be at most 999999999, and the two must not both be"
                + " zero"), result.errors(), result.describe());
    }

    @Test
    void refusesATimeoutOnASynchronousAction() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER, """
                    @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                    Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                    @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED, \
                action = DocActionsRefs.Publish.class,
                        timeout = @Timeout(seconds = 1))
                    Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
                """));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG507"), result.describe());
    }

    /** The operations of the fixture, publishing with the asynchronous action and {@code extra} declared on it. */
    private static String archive(String extra) {
        return """
                    @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                    Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                    @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED, \
                action = DocActionsRefs.Archive.class%s)
                    Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
                """.formatted(extra);
    }
}
