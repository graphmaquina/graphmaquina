package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What the processor reads of the {@code automaticChain} element of the {@code Definition} and what it generates for
 * it: the setting on the graph of an {@code ON_ADVANCE} machine, nothing for the default, the outline rules that
 * judge it on the {@code Definition}, and the generated set that takes its automatic edges only on {@code advance}.
 */
class TypedAutomaticChainTest {

    /** The element written as an author does, by the qualified name, since the facade imports no such type. */
    private static final String ON_ADVANCE = "automaticChain = io.github.graphmaquina.api.AutomaticChain.ON_ADVANCE, ";

    /** A graph from the draft, the initial state, to the published state, the terminal one. */
    private static final String HEADER = """
            @DocsGraph.Definition(%sinitial = DRAFT, terminals = PUBLISHED,
                state = DocRefs.Status.class, id = DocRefs.Id.class)
            @DocsGraph.StatePolicy(state = DRAFT, otherwise = "a draft is sent to review")
            @DocsGraph.StatePolicy(state = REVIEW, otherwise = "a review is published or sent back")
            """;

    /** The submit operation, and a publish that is automatic when {@code automatic} is true. */
    private static String operations(boolean automatic) {
        return """
                @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED, automatic = %b,
                    action = DocActionsRefs.Publish.class)
                Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
                """.formatted(automatic);
    }

    /** The machine with the chain {@code element} written, and an automatic publish. */
    private static Compilation.Result compile(String element) {
        return Compilation.compileTyped(TypedFixtures.graph(HEADER.formatted(element), operations(true)));
    }

    @Test
    void onAdvanceGeneratesTheSetting() {
        Compilation.Result result = compile(ON_ADVANCE);

        assertTrue(result.success(), result.describe());
        String generated = result.generated("fx.DocsRuntime");
        int structure = generated.indexOf(" structure() {");
        String call = ".automaticChain(AutomaticChain.ON_ADVANCE)";
        assertTrue(generated.substring(0, structure).contains(call), generated);
        assertTrue(generated.substring(structure).contains(call), generated);
        assertTrue(generated.contains("import io.github.graphmaquina.api.AutomaticChain;"), generated);
    }

    @Test
    void theDefaultGeneratesNothing() {
        Compilation.Result implicit = compile("");
        Compilation.Result explicit = compile("automaticChain = io.github.graphmaquina.api.AutomaticChain"
                + ".AFTER_COMMIT, ");

        assertTrue(implicit.success(), implicit.describe());
        assertTrue(explicit.success(), explicit.describe());
        assertFalse(implicit.generated("fx.DocsRuntime").contains("automaticChain"));
        assertFalse(explicit.generated("fx.DocsRuntime").contains("automaticChain"));
    }

    @Test
    void onAdvanceWithoutAutomaticEdgesIsReportedOnTheDefinition() {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withDocs(
                Fixtures.DOCS_HEADER.replace("@DocsGraph.Definition(", "@DocsGraph.Definition(" + ON_ADVANCE),
                Fixtures.DOCS_BODY));

        assertTrue(result.hasError(DiagnosticCode.SG110.name()), result.describe());
        assertEquals(Fixtures.HEADER_LINE, result.lineOf(DiagnosticCode.SG110.name()), result.describe());
    }

    /** A manual publish whose failure route continues into the draft, whose submit is the automatic edge. */
    @Test
    void aContinuingRouteOnAManualEdgeIsReported() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER.formatted(ON_ADVANCE)
                .replace("terminals = PUBLISHED,", "terminals = PUBLISHED, allowsCycle = REVIEW,") + """
                @DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT)
                @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW, automatic = true)
                """, """
                @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED,
                    action = DocActionsRefs.Publish.class, onFailure = RETURN, onFailureContinues = true)
                Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
                """));

        assertTrue(result.hasError(DiagnosticCode.SG306.name()), result.describe());
    }

    /** The commit of an operation runs no chain, and {@code advance} runs the whole ladder to its end. */
    @Test
    void theGeneratedSetChainsOnlyOnAdvance() throws Exception {
        Compilation.Result result = Compilation.compileTyped(ladder());

        assertTrue(result.success(), result.describe());
        assertEquals("REVIEW ARCHIVED", result.load("fx.Scenario").getMethod("run").invoke(null));
    }

    /** A set over a ladder of two automatic edges after the manual submit, and the scenario that runs it. */
    private static Map<String, String> ladder() {
        Map<String, String> sources = TypedFixtures.graph(HEADER.formatted(ON_ADVANCE)
                .replace("terminals = PUBLISHED", "terminals = ARCHIVED") + """
                @DocsGraph.StatePolicy(state = PUBLISHED, otherwise = "a published document is archived")
                @DocsGraph.Transition(id = SLOW, from = PUBLISHED, to = ARCHIVED, automatic = true)
                """, operations(true));
        sources.put("fx.DocTransition", """
                package fx;
                /** Identifiers of the ladder. */
                public enum DocTransition { SUBMIT, PUBLISH, RETURN, SLOW }
                """);
        sources.put("fx.DocStatus", """
                package fx;
                /** States of the ladder. */
                public enum DocStatus { DRAFT, REVIEW, PUBLISHED, ARCHIVED }
                """);
        sources.put("fx.Library", """
                package fx;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                @StateGraphs public interface Library extends Machines { Docs docs(); }
                """);
        sources.put("fx.Scenario", """
                package fx;
                public final class Scenario {
                    public static String run() {
                        try (Library library = LibraryRuntime.builder(new DocActions()).build()) {
                            Doc doc = new Doc();
                            library.docs().submit(doc);
                            String committed = doc.status().name();
                            library.docs().advance(doc);
                            return committed + " " + doc.status().name();
                        }
                    }
                }
                """);
        return sources;
    }
}
