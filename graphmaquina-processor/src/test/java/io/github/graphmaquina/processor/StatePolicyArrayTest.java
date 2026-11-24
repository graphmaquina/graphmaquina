package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code state} of a generated {@code StatePolicy} is an array: one policy may word the refusals of several states,
 * and a single state still compiles with Java's array shorthand. A state named twice, in one policy or in two, is
 * {@code SG102}.
 */
class StatePolicyArrayTest {

    private static final String DEFINITION = """
            @DocsGraph.Definition(initial = DRAFT, terminals = RETIRED,
                state = DocRefs.Status.class, id = DocRefs.Id.class)
            @DocsGraph.Transition(id = RETIRE, from = PUBLISHED, to = RETIRED)
            """;

    private static final String MEMBERS = """
                @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED)
                Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
            """;

    private static Map<String, String> sources(String policies) {
        Map<String, String> sources = TypedFixtures.graph(DEFINITION + policies, MEMBERS);
        sources.put("fx.DocStatus", """
                package fx;
                public enum DocStatus { DRAFT, REVIEW, PUBLISHED, RETIRED }
                """);
        sources.put("fx.DocTransition", """
                package fx;
                public enum DocTransition { SUBMIT, PUBLISH, RETIRE }
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
                    public static List<String> run() {
                        return List.of(
                                DocsRuntime.structure().policy(DocStatus.DRAFT).reasonFor(DocTransition.RETIRE),
                                DocsRuntime.structure().policy(DocStatus.REVIEW).reasonFor(DocTransition.RETIRE),
                                DocsRuntime.structure().policy(DocStatus.PUBLISHED).reasonFor(DocTransition.SUBMIT));
                    }
                }
                """);
        return sources;
    }

    @Test
    void oneStatePolicyCoversSeveralStates() throws Exception {
        Compilation.Result result = Compilation.compileTyped(sources("""
                @DocsGraph.StatePolicy(state = {DRAFT, REVIEW}, otherwise = "a doc in work moves forward")
                @DocsGraph.StatePolicy(state = PUBLISHED, otherwise = "a publication is retired")
                """));

        assertTrue(result.success(), result.describe());
        assertEquals(List.of("a doc in work moves forward", "a doc in work moves forward", "a publication is retired"),
                result.load("fx.Consumer").getMethod("run").invoke(null));
    }

    @Test
    void aSingleStateStillCompiles() {
        Compilation.Result result = Compilation.compileTyped(sources("""
                @DocsGraph.StatePolicy(state = DRAFT, otherwise = "a draft is submitted")
                @DocsGraph.StatePolicy(state = REVIEW, otherwise = "a review is published")
                @DocsGraph.StatePolicy(state = PUBLISHED, otherwise = "a publication is retired")
                """));

        assertTrue(result.success(), result.describe());
    }

    @Test
    void aStateTwiceInOnePolicyIsSg102() {
        Compilation.Result result = Compilation.compileTyped(sources("""
                @DocsGraph.StatePolicy(state = {DRAFT, DRAFT, REVIEW}, otherwise = "a doc in work moves forward")
                @DocsGraph.StatePolicy(state = PUBLISHED, otherwise = "a publication is retired")
                """));

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError(DiagnosticCode.SG102.name()), result.describe());
        assertTrue(result.errors().stream().anyMatch(
                (String error) -> error.contains("the state DRAFT declares two policies")), result.describe());
    }

    @Test
    void aStateInTwoPoliciesIsSg102() {
        Compilation.Result result = Compilation.compileTyped(sources("""
                @DocsGraph.StatePolicy(state = {DRAFT, REVIEW}, otherwise = "a doc in work moves forward")
                @DocsGraph.StatePolicy(state = {REVIEW, PUBLISHED}, otherwise = "a doc moves on")
                """));

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError(DiagnosticCode.SG102.name()), result.describe());
        assertTrue(result.errors().stream().anyMatch(
                (String error) -> error.contains("the state REVIEW declares two policies")), result.describe());
    }
}
