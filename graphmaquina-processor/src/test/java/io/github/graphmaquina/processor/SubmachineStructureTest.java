package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * A binding read from the annotations joins the outline of its machine, so {@code SG801} to {@code SG804} come from the
 * validator the DSL runs, reported at the machine's {@code Definition} like every other problem of a graph as a whole.
 * Two rules belong to the processor alone: an operation of the facade that executes an exit, reported at that exit,
 * and a machine that its sub-machines contain again across the compilation, reported at the binding that starts the
 * path.
 */
class SubmachineStructureTest {

    /** The line of {@code @DocsGraph.Definition} in the facade of {@link TypedSubmachineTest#bound(String)}. */
    private static final long DEFINITION_LINE = 10;

    /** The line of the first annotation the header of that facade declares. */
    private static final long HEADER_LINE = 14;

    @Test
    void sg801TheExitsMatchTheTerminalStatesOfTheChild() {
        Compilation.Result result = Compilation.compileTyped(TypedSubmachineTest.bound("""
                @DocsPages.Submachine(state = REVIEW, child = DocRefs.Current.class, parent = PageRefs.Doc.class,
                    exits = {@DocsPages.Exit(on = PageStatus.DONE, transition = COMPLETE),
                             @DocsPages.Exit(on = PageStatus.DONE, transition = COMPLETE),
                             @DocsPages.Exit(on = PageStatus.OPEN, transition = COMPLETE)})
                """));

        assertEquals(List.of("[SG801] Docs.REVIEW: the terminal DONE of Pages has more than one exit",
                "[SG801] Docs.REVIEW: the exit on OPEN names no terminal of Pages"), result.errors(),
                result.describe());
        assertEquals(DEFINITION_LINE, result.lineOf("SG801"), result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
    }

    @Test
    void sg801EveryTerminalStateOfTheChildHasAnExit() {
        Compilation.Result result = Compilation.compileTyped(TypedSubmachineTest.bound("""
                @DocsPages.Submachine(state = REVIEW, child = DocRefs.Current.class, parent = PageRefs.Doc.class,
                    exits = {})
                """));

        assertEquals(List.of("[SG801] Docs.REVIEW: the terminal DONE of Pages has no exit"), result.errors(),
                result.describe());
    }

    @Test
    void sg802AnExitHasAnEdgeFromTheBoundState() {
        Compilation.Result result = Compilation.compileTyped(TypedSubmachineTest.bound("""
                @DocsPages.Submachine(state = DRAFT, child = DocRefs.Current.class, parent = PageRefs.Doc.class,
                    exits = @DocsPages.Exit(on = PageStatus.DONE, transition = COMPLETE))
                """));

        assertEquals(List.of("[SG802] Docs.DRAFT: the exit on DONE names COMPLETE, which has no edge from DRAFT"),
                result.errors(), result.describe());
        assertEquals(DEFINITION_LINE, result.lineOf("SG802"), result.describe());
    }

    @Test
    void sg803ATerminalStateHoldsNoSubmachine() {
        Compilation.Result result = Compilation.compileTyped(TypedSubmachineTest.bound("""
                @DocsPages.Submachine(state = PUBLISHED, child = DocRefs.Current.class, parent = PageRefs.Doc.class,
                    exits = @DocsPages.Exit(on = PageStatus.DONE, transition = COMPLETE))
                """));

        assertEquals(List.of(
                "[SG802] Docs.PUBLISHED: the exit on DONE names COMPLETE, which has no edge from PUBLISHED",
                "[SG803] Docs.PUBLISHED is terminal and cannot hold a sub-machine"), result.errors(),
                result.describe());
    }

    @Test
    void sg803AStateHoldsOneSubmachineAtMost() {
        Compilation.Result result = Compilation.compileTyped(TypedSubmachineTest.bound(
                TypedSubmachineTest.BOUND + TypedSubmachineTest.BOUND));

        assertEquals(List.of("[SG803] Docs.REVIEW holds more than one sub-machine"), result.errors(),
                result.describe());
        assertEquals(DEFINITION_LINE, result.lineOf("SG803"), result.describe());
    }

    @Test
    void sg804NoFailureRouteNorWatchRuleOfTheBoundStateNamesAnExit() {
        Map<String, String> sources = TypedSubmachineTest.bound(TypedSubmachineTest.BOUND + """
                @DocsPages.Watch(state = REVIEW, peers = DocRefs.Pages.class, owner = PageRefs.Doc.class,
                    rules = @DocsPages.When(allIn = PageStatus.DONE, transition = COMPLETE))
                """);
        sources.put("fx.Docs", sources.get("fx.Docs").replace("action = DocActionsRefs.Publish.class)",
                "action = DocActionsRefs.Publish.class, onFailure = COMPLETE,\n"
                        + "        recoveries = @DocsGraph.Recovery(kind = Lost.class, transition = COMPLETE))"));
        sources.put("fx.Lost", """
                package fx;
                import io.github.graphmaquina.api.FailureKind;
                /** A failure kind of the document fixture. */
                public record Lost() implements FailureKind { }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(List.of(
                "[SG804] Docs.REVIEW: COMPLETE is an exit of its sub-machine and cannot be a recovery of PUBLISH",
                "[SG804] Docs.REVIEW: COMPLETE is an exit of its sub-machine and cannot be the onFailure of PUBLISH",
                "[SG804] Docs.REVIEW: COMPLETE is an exit of its sub-machine and cannot be executed by a watch rule"),
                result.errors(), result.describe());
        assertEquals(DEFINITION_LINE, result.lineOf("SG804"), result.describe());
    }

    @Test
    void sg804NoOperationExecutesAnExit() {
        Compilation.Result result = Compilation.compileTyped(TypedSubmachineTest.bound("""
                @DocsPages.Submachine(state = REVIEW, child = DocRefs.Current.class, parent = PageRefs.Doc.class,
                    exits = @DocsPages.Exit(on = PageStatus.DONE, transition = PUBLISH))
                """));

        assertEquals(List.of("[SG804] Docs.REVIEW: PUBLISH is an exit of its sub-machine and cannot be the operation"
                + " publish"), result.errors(), result.describe());
        assertEquals(HEADER_LINE + 1, result.lineOf("SG804"), result.describe());
    }

    @Test
    void sg805AMachineIsNotASubmachineOfItsOwnSubmachines() {
        Map<String, String> sources = TypedSubmachineTest.bound(TypedSubmachineTest.BOUND);
        sources.put("fx.PageTransition", """
                package fx;
                /** Identifiers of the page fixture. */
                public enum PageTransition { FINISH, CLOSE }
                """);
        sources.put("fx.Pages", sources.get("fx.Pages")
                .replace("@GenerateGraphApi",
                        "/** The page machine in the composition cycle fixture. */ @GenerateGraphApi")
                .replace("import io.github.graphmaquina.annotation.GenerateGraphApi;\n",
                        "import io.github.graphmaquina.annotation.GenerateGraphApi;\n"
                                + "import io.github.graphmaquina.annotation.GraphPeers;\n")
                .replace("@GenerateGraphApi\n", """
                        @GenerateGraphApi
                        @GraphPeers(Docs.class)
                        @PagesDocs.Submachine(state = OPEN, child = PageRefs.Doc.class, parent = DocRefs.Current.class,
                            exits = @PagesDocs.Exit(on = DocStatus.PUBLISHED, transition = CLOSE))
                        @PagesGraph.Transition(id = CLOSE, from = OPEN, to = DONE)
                        """));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(List.of(
                "[SG805] the sub-machines of Docs contain Docs again: Docs.REVIEW -> Pages.OPEN -> Docs",
                "[SG805] the sub-machines of Pages contain Pages again: Pages.OPEN -> Docs.REVIEW -> Pages"),
                result.errors().stream().sorted().toList(), result.describe());
        assertEquals(HEADER_LINE, lineOf(result, "[SG805] the sub-machines of Docs contain Docs again: Docs.REVIEW"
                + " -> Pages.OPEN -> Docs"), result.describe());
        assertEquals(10, lineOf(result, "[SG805] the sub-machines of Pages contain Pages again: Pages.OPEN"
                + " -> Docs.REVIEW -> Pages"), result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
        assertFalse(result.hasGenerated("fx.PagesRuntime"), result.describe());
    }

    /** The line of the error whose message is exactly {@code message}, or -1. */
    private static long lineOf(Compilation.Result result, String message) {
        for (Diagnostic<? extends JavaFileObject> diagnostic : result.diagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR && diagnostic.getMessage(Locale.ROOT).equals(message)) {
                return diagnostic.getLineNumber();
            }
        }
        return -1;
    }
}
