package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The frontend builds the model the generators read from the vocabulary generated for the machine. Every
 * structural rule holds with the code the catalogue gives it, and what the language itself refuses is left to
 * javac.
 */
class TypedMachineProcessorTest {

    /** The header of the fixture every negative case varies: three states, two policies, one terminal. */
    private static final String HEADER = """
            @DocsGraph.Definition(initial = DRAFT, terminals = PUBLISHED,
                state = DocRefs.Status.class, id = DocRefs.Id.class)
            @DocsGraph.StatePolicy(state = DRAFT, otherwise = "a draft is sent to review")
            @DocsGraph.StatePolicy(state = REVIEW, otherwise = "a review is published")
            """;

    /** The operations of that fixture: a move into the review and the action that publishes it. */
    private static final String OPERATIONS = """
                @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class)
                Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
            """;

    /**
     * The companion keeps the operations of the facade and exposes no generic selector of its own, which is
     * what a facade preserves; the graph it builds calls the very method the action symbol names. The check is
     * written on the declaration, not on any mention of the name, because an operation is implemented by
     * delegating to the state machine's own {@code execute}.
     */
    @Test
    void compilesMachineAndPreservesFacade() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.minimal());
        assertTrue(result.success(), result.describe());
        assertTrue(result.hasGenerated("fx.DocsRuntime"));
        String companion = result.generated("fx.DocsRuntime");
        assertTrue(companion.contains("public Outcome<Doc, DocStatus, DocTransition> publish(Doc doc)"), companion);
        assertFalse(companion.contains("public Outcome<Doc, DocStatus, DocTransition> execute("), companion);
        assertTrue(companion.contains(".action(\"publish\", (context) -> docActions.publish(context))"), companion);
    }

    @Test
    void reportsMoreThanOneFailureTargetAsInvalidCardinality() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER, """
                    @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                    Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                    @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class,
                        onFailure = {SUBMIT, RETURN})
                    Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
                """));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG607"), result.describe());
    }

    @Test
    void leavesAStateOfAnotherMachineToJavac() {
        Map<String, String> sources = TypedFixtures.graph(HEADER.replace("initial = DRAFT", "initial = PageStatus.OPEN"),
                OPERATIONS);
        sources.put("fx.PageStatus", """
                package fx;
                public enum PageStatus { OPEN, DONE }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertFalse(result.success(), result.describe());
        assertFalse(result.errors().stream().anyMatch(error -> error.contains("[SG")), result.describe());
        assertTrue(result.errors().stream().anyMatch(error ->
                error.contains("incompatible types: fx.PageStatus cannot be converted to fx.DocStatus")), result.describe());
    }

    @Test
    void refusesTheVocabularyOfAnotherMachine() {
        Map<String, String> sources = TypedFixtures.graph(HEADER
                + "@DraftsGraph.StatePolicy(state = PUBLISHED, otherwise = \"borrowed from another machine\")\n",
                OPERATIONS);
        sources.put("fx.Drafts", """
                package fx;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import static fx.DocStatus.*;
                import static fx.DocTransition.*;
                @GenerateGraphApi
                @DraftsGraph.Definition(initial = DRAFT, terminals = PUBLISHED,
                    state = DocRefs.Status.class, id = DocRefs.Id.class)
                @DraftsGraph.StatePolicy(state = DRAFT, otherwise = "a draft is published")
                @DraftsGraph.StatePolicy(state = REVIEW, otherwise = "a review is published")
                public interface Drafts extends Machine<Doc, DocStatus, DocTransition> {
                    @DraftsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                    Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                    @DraftsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class)
                    Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
                }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG606"), result.describe());
    }

    /**
     * An element the vocabulary requires is javac's own error, reported at the declaration that omits it. The
     * identifier is that element now: an absent action means the edge runs none.
     */
    @Test
    void leavesATransitionWithoutItsIdentifierToJavac() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER, """
                    @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                    Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                    @DocsGraph.Transition(from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class)
                    Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
                """));

        assertFalse(result.success(), result.describe());
        assertFalse(result.errors().stream().anyMatch(error -> error.contains("[SG")), result.describe());
        assertTrue(result.errors().stream().anyMatch(error ->
                error.contains("is missing a default value for the element 'id'")), result.describe());
    }

    @Test
    void readsATransitionWithoutAnActionAsAMove() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph("""
                @DocsGraph.Definition(initial = DRAFT, terminals = PUBLISHED, allowsCycle = REVIEW,
                    state = DocRefs.Status.class, id = DocRefs.Id.class)
                @DocsGraph.StatePolicy(state = DRAFT, otherwise = "a draft is sent to review")
                @DocsGraph.StatePolicy(state = REVIEW, otherwise = "a review is published or sent back")
                @DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT)
                """, OPERATIONS));

        assertTrue(result.success(), result.describe());
        assertTrue(result.generated("fx.DocsRuntime")
                .contains(".transition(DocTransition.RETURN, DocStatus.REVIEW, DocStatus.DRAFT).endTransition()"),
                result.generated("fx.DocsRuntime"));
    }

    @Test
    void keepsTheOriginalNamesOfTheMembersItCalls() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER, """
                    @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                    Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                    @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class,
                        guards = DocActionsRefs.Approved.class, afterCommit = DocActionsRefs.Notified.class)
                    Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
                """));

        assertTrue(result.success(), result.describe());
        String companion = result.generated("fx.DocsRuntime");
        assertTrue(companion.contains(".guard(\"approved\", (context) -> docActions.approved(context))"), companion);
        assertTrue(companion.contains(".afterCommit(\"notified\", (doc) -> docActions.notified(doc))"), companion);
    }

    @Test
    void reportsAGuardAskedTwice() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER, """
                    @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                    Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                    @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class,
                        guards = {DocActionsRefs.Approved.class, DocActionsRefs.Approved.class})
                    Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
                """));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG105"), result.describe());
    }

    @Test
    void keepsTheFailsOnRestrictions() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER, """
                    @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                    Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                    @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class,
                        failsOn = RuntimeException.class)
                    Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
                """));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG302"), result.describe());
    }

    @Test
    void reportsAStateWithoutAPolicy() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph("""
                @DocsGraph.Definition(initial = DRAFT, terminals = PUBLISHED,
                    state = DocRefs.Status.class, id = DocRefs.Id.class)
                @DocsGraph.StatePolicy(state = DRAFT, otherwise = "a draft is sent to review")
                """, OPERATIONS));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG701"), result.describe());
    }

    @Test
    void reportsABlankReason() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(
                HEADER.replace("\"a review is published\"", "\"\""), OPERATIONS));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG103"), result.describe());
    }

    /** A self-loop is a real edge now; only the cycle it takes part in has to be authorised. */
    @Test
    void reportsAnUnauthorisedSelfLoop() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER,
                OPERATIONS.replace("from = REVIEW, to = PUBLISHED", "from = REVIEW, to = REVIEW")));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG204"), result.describe());
    }

    @Test
    void reportsATerminalStateThatStillExits() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(
                HEADER.replace("terminals = PUBLISHED", "terminals = {REVIEW, PUBLISHED}"), OPERATIONS));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG202"), result.describe());
    }

    @Test
    void reportsARejectionOfAnIdentifierThatIsAlsoDeclared() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(
                HEADER + "@DocsGraph.Reject(from = DRAFT, transition = SUBMIT, reason = \"already a move\")\n", OPERATIONS));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG702"), result.describe());
    }

    @Test
    void reportsAnUnreachableState() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER, """
                    @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class)
                    Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
                """));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG203"), result.describe());
    }

    @Test
    void reportsAnUndeclaredCycle() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER, OPERATIONS + """
                    @DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT)
                    Outcome<Doc, DocStatus, DocTransition> reject(Doc doc);
                """));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG204"), result.describe());
    }
}
