package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A transition is chosen by its identifier and its origin, never by where it ends. These are the declarations
 * that must be refused because two of them claim one key or one identifier, or because an identifier is named
 * where its machine or its state does not declare it, and the ones that must be accepted because two edges only
 * happen to share an endpoint. A state with no policy is refused here too.
 */
class TransitionIdentityTest {

    /** A valid graph over the fixture's four states: every negative case varies exactly one declaration of it. */
    private static final String BODY = """
                @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class)
                Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
                @DocsGraph.Transition(id = ARCHIVE, from = PUBLISHED, to = ARCHIVED)
                Outcome<Doc, DocStatus, DocTransition> archive(Doc doc);
            """;

    private static Compilation.Result compile(String header, String members) {
        return Compilation.compileTyped(Fixtures.withDocs(header, members));
    }

    @Test
    void theBaseGraphIsValid() {
        Compilation.Result result = compile(Fixtures.DOCS_HEADER, BODY);
        assertTrue(result.success(), result.describe());
    }

    @Test
    void sg102TheSameIdentifierLeavingOneOriginTwice() {
        Compilation.Result result = compile(Fixtures.DOCS_HEADER
                + "@DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = DRAFT)\n", BODY);
        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG102"), result.describe());
    }

    /** An identifier of another machine's catalogue is a type error of javac, never a diagnostic of this tool. */
    @Test
    void anIdentifierOfAnotherCatalogueIsRefusedByJavacAlone() {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withPages(Fixtures.DOCS_HEADER,
                BODY.replace("id = SUBMIT, from = DRAFT", "id = PageTransition.FINISH, from = DRAFT")));
        assertFalse(result.success(), result.describe());
        assertFalse(result.errors().stream().anyMatch(error -> error.contains("[SG")), result.describe());
    }

    @Test
    void sg701AStateWithNoPolicy() {
        Compilation.Result result = compile(Fixtures.DOCS_HEADER.replace(
                "@DocsGraph.StatePolicy(state = REVIEW, otherwise = \"a review is published or sent back\")\n", ""), BODY);
        assertTrue(result.hasError("SG701"), result.describe());
    }

    @Test
    void sg702ARejectionOfAnIdentifierThatStateDeclares() {
        Compilation.Result result = compile(Fixtures.DOCS_HEADER
                + "@DocsGraph.Reject(from = REVIEW, transition = PUBLISH, reason = \"already declared\")\n", BODY);
        assertTrue(result.hasError("SG702"), result.describe());
    }

    @Test
    void sg301AFailureRouteThatLeavesNoEdgeFromTheOrigin() {
        Compilation.Result result = compile(Fixtures.DOCS_HEADER,
                BODY.replace("action = DocActionsRefs.Publish.class", "action = DocActionsRefs.Publish.class, onFailure = ARCHIVE"));
        assertTrue(result.hasError("SG301"), result.describe());
    }

    @Test
    void sg401AWatchThatExecutesAnIdentifierItsStateDoesNotDeclare() {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withPages(Fixtures.DOCS_HEADER
                + Fixtures.DOCS_WATCH.replace("transition = PUBLISH", "transition = ARCHIVE"), BODY));
        assertTrue(result.hasError("SG401"), result.describe());
    }

    @Test
    void sg703OneMethodNamingTwoIdentifiers() {
        Compilation.Result result = compile(Fixtures.DOCS_HEADER.replace(
                "@DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT)\n", ""),
                BODY.replace("    @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)\n",
                        "    @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)\n"
                                + "    @DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT)\n"));
        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError("SG703"), result.describe());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[SG703]") && error.contains("submit")),
                result::describe);
    }

    @Test
    void sg703OneIdentifierExecutedByTwoMethods() {
        Compilation.Result result = compile(Fixtures.DOCS_HEADER, BODY
                + "    @DocsGraph.Transition(id = PUBLISH, from = DRAFT, to = REVIEW)\n"
                + "    Outcome<Doc, DocStatus, DocTransition> rush(Doc doc);\n");
        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError("SG703"), result.describe());
        assertTrue(result.errors().stream().anyMatch(error ->
                error.contains("[SG703]") && error.contains("publish")), result::describe);
    }

    @Test
    void parallelEdgesToOneDestinationAreAccepted() {
        Compilation.Result result = compile(Fixtures.DOCS_HEADER, BODY
                + "    @DocsGraph.Transition(id = AUTO_PUBLISH, from = REVIEW, to = PUBLISHED,"
                + " action = DocActionsRefs.AutoPublish.class)\n"
                + "    Outcome<Doc, DocStatus, DocTransition> autoPublish(Doc doc);\n");
        assertTrue(result.success(), result.describe());
        String source = result.generated("fx.DocsRuntime");
        assertTrue(source.contains(".transition(DocTransition.PUBLISH, DocStatus.REVIEW, DocStatus.PUBLISHED)"), source);
        assertTrue(source.contains(".transition(DocTransition.AUTO_PUBLISH, DocStatus.REVIEW, DocStatus.PUBLISHED)"), source);
    }

    @Test
    void anExplicitSelfLoopIsAcceptedWhereItsStateAuthorisesTheCycle() {
        Compilation.Result result = compile(Fixtures.DOCS_HEADER.replace(
                "@DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT)",
                "@DocsGraph.Transition(id = RETURN, from = REVIEW, to = REVIEW)"), BODY);
        assertTrue(result.success(), result.describe());
        assertTrue(result.generated("fx.DocsRuntime")
                .contains(".transition(DocTransition.RETURN, DocStatus.REVIEW, DocStatus.REVIEW)"),
                result.generated("fx.DocsRuntime"));
    }

    /** An operation owns its identifier; the interface may open further branches of it from other origins. */
    @Test
    void oneIdentifierFromTwoOriginsReachingTwoDestinationsIsAccepted() {
        Compilation.Result result = compile(Fixtures.DOCS_HEADER
                + "@DocsGraph.Transition(id = PUBLISH, from = DRAFT, to = REVIEW)\n", BODY);
        assertTrue(result.success(), result.describe());
        String source = result.generated("fx.DocsRuntime");
        assertTrue(source.contains(".transition(DocTransition.PUBLISH, DocStatus.DRAFT, DocStatus.REVIEW)"), source);
        assertTrue(source.contains(".transition(DocTransition.PUBLISH, DocStatus.REVIEW, DocStatus.PUBLISHED)"), source);
    }

    @Test
    void aFailureRouteReachingTheSameDestinationUnderAnotherIdentifierIsAccepted() {
        Map<String, String> sources = Fixtures.withDocs(Fixtures.DOCS_HEADER, BODY
                .replace("action = DocActionsRefs.Publish.class", "action = DocActionsRefs.Publish.class, onFailure = AUTO_PUBLISH")
                + "    @DocsGraph.Transition(id = AUTO_PUBLISH, from = REVIEW, to = PUBLISHED,"
                + " action = DocActionsRefs.AutoPublish.class)\n"
                + "    Outcome<Doc, DocStatus, DocTransition> autoPublish(Doc doc);\n");
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result.describe());
        assertTrue(result.generated("fx.DocsRuntime").contains(".onFailure(DocTransition.AUTO_PUBLISH)"),
                result.generated("fx.DocsRuntime"));
    }
}
