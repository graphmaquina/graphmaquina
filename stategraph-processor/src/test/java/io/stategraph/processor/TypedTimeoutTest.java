package io.stategraph.processor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A timeout is declared as seconds and a nanosecond adjustment, the two fields {@link java.time.Duration} is
 * built from, so nothing is rounded on the way in. The representation is checked before the duration is built:
 * every combination the class refuses, and every one that would overflow, is reported as {@code SG511} instead.
 */
class TypedTimeoutTest {

    private static final String HEADER = """
            @DocsGraph.Definition(initial = DRAFT, terminals = PUBLISHED,
                state = DocRefs.Status.class, id = DocRefs.Id.class)
            @DocsGraph.Row(state = DRAFT, otherwise = "a draft is sent to review")
            @DocsGraph.Row(state = REVIEW, otherwise = "a review is published")
            """;

    @Test
    void keepsANanosecondOfTimeout() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER,
                archive(", timeout = @Timeout(nanos = 1)")));

        assertTrue(result.success(), result.describe());
        assertTrue(result.generated("fx.Docs_").contains(".timeout(Duration.parse(\"PT0.000000001S\"))"),
                result.generated("fx.Docs_"));
    }

    @Test
    void keepsTheLongestTimeoutTheDurationHolds() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER,
                archive(", timeout = @Timeout(seconds = Long.MAX_VALUE, nanos = 999999999)")));

        assertTrue(result.success(), result.describe());
        assertTrue(result.generated("fx.Docs_").contains(".timeout(Duration.parse(\"PT2562047788015215H30M7.999999999S\"))"),
                result.generated("fx.Docs_"));
    }

    @Test
    void refusesATimeoutOfZero() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER, archive(", timeout = @Timeout")));

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

    @Test
    void refusesATimeoutOnASynchronousAction() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER, """
                    @DocsGraph.Move(from = DRAFT, to = REVIEW)
                    Outcome<Doc, DocStatus> submit(Doc doc);
                    @DocsGraph.Action(from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class,
                        timeout = @Timeout(seconds = 1))
                    Outcome<Doc, DocStatus> publish(Doc doc);
                """));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG507"), result.describe());
    }

    /** The operations of the fixture, publishing with the asynchronous action and {@code extra} declared on it. */
    private static String archive(String extra) {
        return """
                    @DocsGraph.Move(from = DRAFT, to = REVIEW)
                    Outcome<Doc, DocStatus> submit(Doc doc);
                    @DocsGraph.Action(from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Archive.class%s)
                    Outcome<Doc, DocStatus> publish(Doc doc);
                """.formatted(extra);
    }
}
