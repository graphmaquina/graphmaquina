package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The typed frontend builds the same model the legacy one does, from the vocabulary generated for the machine
 * instead of from names written as strings. Every structural rule the legacy frontend enforces is still
 * enforced here, with the code it already had, and what the language itself refuses is left to javac.
 */
class TypedMachineProcessorTest {

    /** The header of the fixture every negative case varies: three states, two closed rows, one terminal. */
    private static final String HEADER = """
            @DocsGraph.Definition(initial = DRAFT, terminals = PUBLISHED,
                state = DocRefs.Status.class, id = DocRefs.Id.class)
            @DocsGraph.Row(state = DRAFT, otherwise = "a draft is sent to review")
            @DocsGraph.Row(state = REVIEW, otherwise = "a review is published")
            """;

    /** The operations of that fixture: a move into the review and the action that publishes it. */
    private static final String OPERATIONS = """
                @DocsGraph.Move(from = DRAFT, to = REVIEW)
                Outcome<Doc, DocStatus> submit(Doc doc);
                @DocsGraph.Action(from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class)
                Outcome<Doc, DocStatus> publish(Doc doc);
            """;

    /**
     * The companion keeps the operations of the facade and exposes no {@code transitionTo} of its own, which is
     * what a facade preserves; the graph it builds calls the very method the action symbol names. The check is
     * written on the declaration, not on any mention of the name, because an operation is still implemented by
     * delegating to the state machine's own {@code transitionTo}, as it has always been.
     */
    @Test
    void compilesTypedMachineAndPreservesFacade() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.minimal());
        assertTrue(result.success(), result.describe());
        assertTrue(result.hasGenerated("fx.DocsRuntime"));
        String companion = result.generated("fx.DocsRuntime");
        assertTrue(companion.contains("public Outcome<Doc, DocStatus> publish(Doc doc)"), companion);
        assertFalse(companion.contains("public Outcome<Doc, DocStatus> transitionTo("), companion);
        assertTrue(companion.contains(".action(\"publish\", (context) -> docActions.publish(context))"), companion);
    }

    @Test
    void reportsMoreThanOneFailureTargetAsInvalidCardinality() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER, """
                    @DocsGraph.Move(from = DRAFT, to = REVIEW)
                    Outcome<Doc, DocStatus> submit(Doc doc);
                    @DocsGraph.Action(from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class,
                        onFailure = {DRAFT, REVIEW})
                    Outcome<Doc, DocStatus> publish(Doc doc);
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
                + "@DraftsGraph.Row(state = PUBLISHED, otherwise = \"borrowed from another machine\")\n", OPERATIONS);
        sources.put("fx.Drafts", """
                package fx;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import static fx.DocStatus.*;
                @GenerateGraphApi
                @DraftsGraph.Definition(initial = DRAFT, terminals = PUBLISHED,
                    state = DocRefs.Status.class, id = DocRefs.Id.class)
                @DraftsGraph.Row(state = DRAFT, otherwise = "a draft is published")
                @DraftsGraph.Row(state = REVIEW, otherwise = "a review is published")
                public interface Drafts extends Machine<Doc, DocStatus> {
                    @DraftsGraph.Move(from = DRAFT, to = REVIEW)
                    Outcome<Doc, DocStatus> submit(Doc doc);
                    @DraftsGraph.Action(from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class)
                    Outcome<Doc, DocStatus> publish(Doc doc);
                }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG606"), result.describe());
    }

    @Test
    void leavesAnActionWithoutItsActionToJavac() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER, """
                    @DocsGraph.Move(from = DRAFT, to = REVIEW)
                    Outcome<Doc, DocStatus> submit(Doc doc);
                    @DocsGraph.Action(from = REVIEW, to = PUBLISHED)
                    Outcome<Doc, DocStatus> publish(Doc doc);
                """));

        assertFalse(result.success(), result.describe());
        assertFalse(result.errors().stream().anyMatch(error -> error.contains("[SG")), result.describe());
        assertTrue(result.errors().stream().anyMatch(error ->
                error.contains("is missing a default value for the element 'action'")), result.describe());
    }

    @Test
    void readsACellWithoutAnActionAsAMove() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph("""
                @DocsGraph.Definition(initial = DRAFT, terminals = PUBLISHED, allowsCycle = REVIEW,
                    state = DocRefs.Status.class, id = DocRefs.Id.class)
                @DocsGraph.Row(state = DRAFT, otherwise = "a draft is sent to review")
                @DocsGraph.Row(state = REVIEW, otherwise = "a review is published or sent back")
                @DocsGraph.Cell(from = REVIEW, to = DRAFT)
                """, OPERATIONS));

        assertTrue(result.success(), result.describe());
        assertTrue(result.generated("fx.DocsRuntime").contains(".to(DocStatus.DRAFT).move()"), result.generated("fx.DocsRuntime"));
    }

    @Test
    void keepsTheOriginalNamesOfTheMembersItCalls() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER, """
                    @DocsGraph.Move(from = DRAFT, to = REVIEW)
                    Outcome<Doc, DocStatus> submit(Doc doc);
                    @DocsGraph.Action(from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class,
                        guards = DocActionsRefs.Approved.class, afterCommit = DocActionsRefs.Notified.class)
                    Outcome<Doc, DocStatus> publish(Doc doc);
                """));

        assertTrue(result.success(), result.describe());
        String companion = result.generated("fx.DocsRuntime");
        assertTrue(companion.contains(".guard(\"approved\", (context) -> docActions.approved(context))"), companion);
        assertTrue(companion.contains(".afterCommit(\"notified\", (doc) -> docActions.notified(doc))"), companion);
    }

    @Test
    void reportsAGuardAskedTwice() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER, """
                    @DocsGraph.Move(from = DRAFT, to = REVIEW)
                    Outcome<Doc, DocStatus> submit(Doc doc);
                    @DocsGraph.Action(from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class,
                        guards = {DocActionsRefs.Approved.class, DocActionsRefs.Approved.class})
                    Outcome<Doc, DocStatus> publish(Doc doc);
                """));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG105"), result.describe());
    }

    @Test
    void keepsTheFailsOnRestrictions() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER, """
                    @DocsGraph.Move(from = DRAFT, to = REVIEW)
                    Outcome<Doc, DocStatus> submit(Doc doc);
                    @DocsGraph.Action(from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class,
                        failsOn = RuntimeException.class)
                    Outcome<Doc, DocStatus> publish(Doc doc);
                """));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG302"), result.describe());
    }

    @Test
    void reportsAStateThatClosesNoRow() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph("""
                @DocsGraph.Definition(initial = DRAFT, terminals = PUBLISHED,
                    state = DocRefs.Status.class, id = DocRefs.Id.class)
                @DocsGraph.Row(state = DRAFT, otherwise = "a draft is sent to review")
                """, OPERATIONS));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG506"), result.describe());
    }

    @Test
    void reportsABlankReason() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(
                HEADER.replace("\"a review is published\"", "\"\""), OPERATIONS));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG103"), result.describe());
    }

    @Test
    void reportsACellOnTheDiagonal() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER,
                OPERATIONS.replace("from = REVIEW, to = PUBLISHED", "from = REVIEW, to = REVIEW")));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG104"), result.describe());
    }

    @Test
    void reportsATerminalStateThatStillExits() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(
                HEADER.replace("terminals = PUBLISHED", "terminals = {REVIEW, PUBLISHED}"), OPERATIONS));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG202"), result.describe());
    }

    @Test
    void reportsACellDeclaredTwice() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(
                HEADER + "@DocsGraph.Forbid(from = DRAFT, to = REVIEW, reason = \"already a move\")\n", OPERATIONS));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG102"), result.describe());
    }

    @Test
    void reportsAnUnreachableState() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER, """
                    @DocsGraph.Action(from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class)
                    Outcome<Doc, DocStatus> publish(Doc doc);
                """));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG203"), result.describe());
    }

    @Test
    void reportsAnUndeclaredCycle() {
        Compilation.Result result = Compilation.compileTyped(TypedFixtures.graph(HEADER, OPERATIONS + """
                    @DocsGraph.Move(from = REVIEW, to = DRAFT)
                    Outcome<Doc, DocStatus> reject(Doc doc);
                """));

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG204"), result.describe());
    }
}
