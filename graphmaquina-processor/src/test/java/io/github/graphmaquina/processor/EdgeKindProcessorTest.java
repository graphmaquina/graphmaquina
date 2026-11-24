package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code gate = true} and {@code external = true} on a generated {@code Transition} reach the edge the companion
 * declares; a gate takes a {@code Check} without any operation, and the facade cannot reach either kind otherwise:
 * an operation of a gate or an external identifier, a check of an external identifier, a gate among the candidates of
 * a choice that runs and an external identifier among those of any choice are {@code SG517}. The edge rules of the
 * kinds are the validator's, so the processor reports them with the DSL's codes.
 */
class EdgeKindProcessorTest {

    private static final String HEADER = """
            @DocsGraph.Definition(initial = DRAFT, terminals = RETIRED, allowsCycle = REVIEW,
                state = DocRefs.Status.class, id = DocRefs.Id.class)
            @DocsGraph.StatePolicy(state = DRAFT, otherwise = "a draft is submitted")
            @DocsGraph.StatePolicy(state = REVIEW, otherwise = "a review is published or returned")
            @DocsGraph.StatePolicy(state = PUBLISHED, otherwise = "a publication is retired by the archive")
            @DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT)
            @DocsGraph.Transition(id = RETIRE, from = PUBLISHED, to = RETIRED, external = true)
            """;

    private static final String GATE = """
            @DocsGraph.Transition(id = PEEK, from = REVIEW, to = REVIEW, gate = true)
            """;

    private static final String OPERATIONS = """
                @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED)
                Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
            """;

    private static final String ELIGIBILITY = "io.github.graphmaquina.api.Eligibility<Doc, DocStatus, DocTransition>";

    private static final String CHOICE = "io.github.graphmaquina.api.ChoiceEligibility<Doc, DocStatus, DocTransition>";

    private static Map<String, String> sources(String header, String members) {
        Map<String, String> sources = TypedFixtures.graph(header, members);
        sources.put("fx.DocStatus", """
                package fx;
                public enum DocStatus { DRAFT, REVIEW, PUBLISHED, RETIRED }
                """);
        sources.put("fx.DocTransition", """
                package fx;
                public enum DocTransition { SUBMIT, PUBLISH, RETURN, PEEK, RETIRE }
                """);
        sources.put("fx.Library", """
                package fx;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                @StateGraphs public interface Library extends Machines { Docs docs(); }
                """);
        sources.put("fx.Consumer", """
                package fx;
                import java.util.List;
                public final class Consumer {
                    private Consumer() { }
                    public static List<Boolean> run() {
                        return List.of(DocsRuntime.structure().findTransition(DocStatus.REVIEW, DocTransition.PEEK)
                                        .orElseThrow().gate(),
                                DocsRuntime.structure().findTransition(DocStatus.PUBLISHED, DocTransition.RETIRE)
                                        .orElseThrow().external(),
                                DocsRuntime.structure().findTransition(DocStatus.DRAFT, DocTransition.SUBMIT)
                                        .orElseThrow().gate());
                    }
                }
                """);
        return sources;
    }

    private static void assertSg517(Compilation.Result result, String fragment) {
        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError(DiagnosticCode.SG517.name()), result.describe());
        assertTrue(result.errors().stream().anyMatch((String error) -> error.contains(fragment)), result.describe());
    }

    @Test
    void theFlagsReachTheEdgesAndAGateTakesACheckWithoutOperation() throws Exception {
        Compilation.Result result = Compilation.compileTyped(sources(HEADER + GATE, OPERATIONS
                + "    @DocsGraph.Check(id = PEEK)\n    " + ELIGIBILITY + " canPeek(Doc doc, String reader);\n"));

        assertTrue(result.success(), result.describe());
        assertEquals(List.of(true, true, false), result.load("fx.Consumer").getMethod("run").invoke(null));
    }

    @Test
    void anOperationOfAGateIsSg517() {
        assertSg517(Compilation.compileTyped(sources(HEADER, OPERATIONS + """
                    @DocsGraph.Transition(id = PEEK, from = REVIEW, to = REVIEW, gate = true)
                    Outcome<Doc, DocStatus, DocTransition> peek(Doc doc);
                """)), "peek");
    }

    @Test
    void anOperationOfAnExternalIdentifierIsSg517() {
        assertSg517(Compilation.compileTyped(sources(HEADER.replace(
                "@DocsGraph.Transition(id = RETIRE, from = PUBLISHED, to = RETIRED, external = true)\n", ""),
                OPERATIONS + """
                    @DocsGraph.Transition(id = RETIRE, from = PUBLISHED, to = RETIRED, external = true)
                    Outcome<Doc, DocStatus, DocTransition> retire(Doc doc);
                """)), "retire");
    }

    @Test
    void aCheckOfAnExternalIdentifierIsSg517() {
        assertSg517(Compilation.compileTyped(sources(HEADER + GATE, OPERATIONS
                + "    @DocsGraph.Check(id = RETIRE)\n    " + ELIGIBILITY + " canRetire(Doc doc);\n")), "RETIRE");
    }

    @Test
    void aGateAmongTheCandidatesOfARunningChoiceIsSg517() {
        assertSg517(Compilation.compileTyped(sources(HEADER + GATE, OPERATIONS
                + "    @DocsGraph.Choice({PEEK, PUBLISH})\n"
                + "    Outcome<Doc, DocStatus, DocTransition> move(Doc doc);\n")), "PEEK");
    }

    @Test
    void anExternalIdentifierAmongTheCandidatesOfAChoiceCheckIsSg517() {
        assertSg517(Compilation.compileTyped(sources(HEADER + GATE, OPERATIONS
                + "    @DocsGraph.Choice({RETIRE, PUBLISH})\n    " + CHOICE + " canMove(Doc doc);\n")), "RETIRE");
    }

    @Test
    void aGateAmongTheCandidatesOfAChoiceCheckCompiles() {
        Compilation.Result result = Compilation.compileTyped(sources(HEADER + GATE, OPERATIONS
                + "    @DocsGraph.Choice({PEEK, PUBLISH})\n    " + CHOICE + " canMove(Doc doc);\n"));

        assertTrue(result.success(), result.describe());
    }

    @Test
    void aGateWithAnActionIsSg111() {
        Compilation.Result result = Compilation.compileTyped(sources(HEADER
                + "@DocsGraph.Transition(id = PEEK, from = REVIEW, to = REVIEW, gate = true,"
                + " action = DocActionsRefs.Publish.class)\n", OPERATIONS));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError(DiagnosticCode.SG111.name()), result.describe());
    }
}
