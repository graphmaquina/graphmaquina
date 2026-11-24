package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The selector every companion implements: {@code execute(entity, id)} runs the operation without input that the
 * facade declares for {@code id}, and {@code eligibility(entity, id)} the check without input it declares for it. Any
 * other identifier, one whose operation takes an input, one with no operation or no check, a gate for
 * {@code execute} and an external identifier, is refused with {@code IllegalArgumentException} before anything runs; a
 * null argument with {@code NullPointerException}. A facade operation that would take the selector's place is
 * {@code SG504}.
 */
class SelectorGenerationTest {

    private static final String HEADER = """
            @DocsGraph.Definition(initial = DRAFT, terminals = RETIRED, allowsCycle = REVIEW,
                state = DocRefs.Status.class, id = DocRefs.Id.class)
            @DocsGraph.StatePolicy(state = DRAFT, otherwise = "a draft is submitted")
            @DocsGraph.StatePolicy(state = REVIEW, otherwise = "a review is published or returned")
            @DocsGraph.StatePolicy(state = PUBLISHED, otherwise = "a publication is retired by the archive")
            @DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT)
            @DocsGraph.Transition(id = PEEK, from = REVIEW, to = REVIEW, gate = true)
            @DocsGraph.Transition(id = RETIRE, from = PUBLISHED, to = RETIRED, external = true)
            """;

    private static final String MEMBERS = """
                @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED)
                Outcome<Doc, DocStatus, DocTransition> publish(Doc doc, String note);
                @DocsGraph.Check(id = PEEK)
                io.github.graphmaquina.api.Eligibility<Doc, DocStatus, DocTransition> canPeek(Doc doc);
            """;

    /** A consumer that runs the selector of a new doc and lists what each call answered or threw. */
    private static final String CONSUMER = """
            package fx;
            import java.util.ArrayList;
            import java.util.List;
            public final class Consumer {
                private Consumer() { }
                public static List<String> run() {
                    List<String> seen = new ArrayList<>();
                    try (Library library = LibraryRuntime.builder().build()) {
                        Docs docs = library.docs();
                        Doc doc = new Doc();
                        seen.add("execute SUBMIT " + docs.execute(doc, DocTransition.SUBMIT).status() + " "
                                + doc.status());
                        seen.add("eligibility PEEK " + docs.eligibility(doc, DocTransition.PEEK).isEligible());
                        seen.add(refused(() -> docs.execute(doc, DocTransition.PUBLISH)));
                        seen.add(refused(() -> docs.execute(doc, DocTransition.RETURN)));
                        seen.add(refused(() -> docs.execute(doc, DocTransition.PEEK)));
                        seen.add(refused(() -> docs.eligibility(doc, DocTransition.RETIRE)));
                        seen.add(refused(() -> docs.eligibility(doc, DocTransition.SUBMIT)));
                        seen.add("state " + doc.status());
                        try {
                            docs.execute(null, DocTransition.SUBMIT);
                        } catch (NullPointerException refusal) {
                            seen.add("NPE " + refusal.getMessage());
                        }
                        try {
                            docs.eligibility(doc, null);
                        } catch (NullPointerException refusal) {
                            seen.add("NPE " + refusal.getMessage());
                        }
                    }
                    return seen;
                }
                private static String refused(Runnable call) {
                    try {
                        call.run();
                        return "ran";
                    } catch (IllegalArgumentException refusal) {
                        return "IAE " + refusal.getMessage();
                    }
                }
            }
            """;

    private static Map<String, String> sources(String members) {
        Map<String, String> sources = TypedFixtures.graph(HEADER, members);
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
        sources.put("fx.Consumer", CONSUMER);
        return sources;
    }

    @Test
    void theSelectorDispatchesToTheDeclaredOperationsAndChecksOnly() throws Exception {
        Compilation.Result result = Compilation.compileTyped(sources(MEMBERS));

        assertTrue(result.success(), result.describe());
        assertEquals(List.of(
                "execute SUBMIT APPLIED REVIEW",
                "eligibility PEEK true",
                "IAE PUBLISH has no operation without input on Docs",
                "IAE RETURN has no operation without input on Docs",
                "IAE PEEK has no operation without input on Docs",
                "IAE RETIRE has no check without input on Docs",
                "IAE SUBMIT has no check without input on Docs",
                "state REVIEW",
                "NPE entity",
                "NPE transition"), result.load("fx.Consumer").getMethod("run").invoke(null));
    }

    @Test
    void aFacadeOperationThatTakesTheSelectorsPlaceIsSg504() {
        Compilation.Result result = Compilation.compileTyped(sources(MEMBERS + """
                @DocsGraph.Transition(id = RETURN, from = PUBLISHED, to = DRAFT)
                Outcome<Doc, DocStatus, DocTransition> execute(Doc doc, DocTransition transition);
            """));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError(DiagnosticCode.SG504.name()), result.describe());
        assertTrue(result.errors().stream().anyMatch((String error) -> error.contains("overrides Machine.execute")),
                result.describe());
    }
}
