package io.stategraph.processor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * An element the model holds at most once is declared as an array with an empty default, because an annotation
 * cannot express "zero or one" otherwise. None and one are the two declarations that mean something; more than
 * one is refused as {@code SG607} instead of being read as the first, or the last, of them.
 */
class TypedCardinalityTest {

    /** A graph whose review returns to the draft, so a failure route out of the review has somewhere to go. */
    private static final String HEADER = """
            @DocsGraph.Definition(initial = DRAFT, terminals = PUBLISHED, allowsCycle = REVIEW,
                state = DocRefs.Status.class, id = DocRefs.Id.class)
            @DocsGraph.Row(state = DRAFT, otherwise = "a draft is sent to review")
            @DocsGraph.Row(state = REVIEW, otherwise = "a review is published or sent back")
            @DocsGraph.Cell(from = REVIEW, to = DRAFT)
            """;

    @Test
    void readsNoneOneAndRefusesTwoFailureTargets() {
        Compilation.Result none = Compilation.compileTyped(TypedFixtures.graph(HEADER, publish("")));
        assertTrue(none.success(), none.describe());
        assertFalse(none.generated("fx.Docs_").contains(".onFailure("), none.generated("fx.Docs_"));

        Compilation.Result one = Compilation.compileTyped(TypedFixtures.graph(HEADER, publish(", onFailure = DRAFT")));
        assertTrue(one.success(), one.describe());
        assertTrue(one.generated("fx.Docs_").contains(".onFailure(DocStatus.DRAFT)"), one.generated("fx.Docs_"));

        Compilation.Result many = Compilation.compileTyped(TypedFixtures.graph(HEADER,
                publish(", onFailure = {DRAFT, REVIEW}")));
        assertFalse(many.success(), many.describe());
        assertTrue(many.hasError("SG607"), many.describe());
    }

    @Test
    void readsNoneOneAndRefusesTwoCommitHooks() {
        Compilation.Result none = Compilation.compileTyped(TypedFixtures.graph(HEADER, publish("")));
        assertTrue(none.success(), none.describe());
        assertFalse(none.generated("fx.Docs_").contains(".afterCommit("), none.generated("fx.Docs_"));

        Compilation.Result one = Compilation.compileTyped(TypedFixtures.graph(HEADER,
                publish(", afterCommit = DocActionsRefs.Notified.class")));
        assertTrue(one.success(), one.describe());
        assertTrue(one.generated("fx.Docs_").contains(".afterCommit(\"notified\""), one.generated("fx.Docs_"));

        Compilation.Result many = Compilation.compileTyped(TypedFixtures.graph(HEADER,
                publish(", afterCommit = {DocActionsRefs.Notified.class, DocActionsRefs.Notified.class}")));
        assertFalse(many.success(), many.describe());
        assertTrue(many.hasError("SG607"), many.describe());
    }

    @Test
    void readsNoneOneAndRefusesTwoTimeouts() {
        Compilation.Result none = Compilation.compileTyped(TypedFixtures.graph(HEADER, archive("")));
        assertTrue(none.success(), none.describe());
        assertFalse(none.generated("fx.Docs_").contains(".timeout("), none.generated("fx.Docs_"));

        Compilation.Result one = Compilation.compileTyped(TypedFixtures.graph(HEADER,
                archive(", timeout = @Timeout(seconds = 2)")));
        assertTrue(one.success(), one.describe());
        assertTrue(one.generated("fx.Docs_").contains(".timeout(Duration.parse(\"PT2S\"))"), one.generated("fx.Docs_"));

        Compilation.Result many = Compilation.compileTyped(TypedFixtures.graph(HEADER,
                archive(", timeout = {@Timeout(seconds = 1), @Timeout(seconds = 2)}")));
        assertFalse(many.success(), many.describe());
        assertTrue(many.hasError("SG607"), many.describe());
    }

    @Test
    void readsNoneOneAndRefusesTwoActionsOfACell() {
        Compilation.Result none = Compilation.compileTyped(TypedFixtures.graph(HEADER, publish("")));
        assertTrue(none.success(), none.describe());
        assertTrue(none.generated("fx.Docs_").contains(".to(DocStatus.DRAFT).move()"), none.generated("fx.Docs_"));

        Compilation.Result one = Compilation.compileTyped(TypedFixtures.graph(
                HEADER.replace("to = DRAFT)", "to = DRAFT, action = DocActionsRefs.Publish.class)"), publish("")));
        assertTrue(one.success(), one.describe());
        assertTrue(one.generated("fx.Docs_").contains(".to(DocStatus.DRAFT).action(\"publish\""), one.generated("fx.Docs_"));

        Compilation.Result many = Compilation.compileTyped(TypedFixtures.graph(
                HEADER.replace("to = DRAFT)",
                        "to = DRAFT, action = {DocActionsRefs.Publish.class, DocActionsRefs.Publish.class})"),
                publish("")));
        assertFalse(many.success(), many.describe());
        assertTrue(many.hasError("SG607"), many.describe());
    }

    /** The operations of the fixture, publishing with the synchronous action and {@code extra} declared on it. */
    private static String publish(String extra) {
        return """
                    @DocsGraph.Move(from = DRAFT, to = REVIEW)
                    Outcome<Doc, DocStatus> submit(Doc doc);
                    @DocsGraph.Action(from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class%s)
                    Outcome<Doc, DocStatus> publish(Doc doc);
                """.formatted(extra);
    }

    /** The same operations, publishing with the asynchronous action a timeout needs. */
    private static String archive(String extra) {
        return """
                    @DocsGraph.Move(from = DRAFT, to = REVIEW)
                    Outcome<Doc, DocStatus> submit(Doc doc);
                    @DocsGraph.Action(from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Archive.class%s)
                    Outcome<Doc, DocStatus> publish(Doc doc);
                """.formatted(extra);
    }
}
