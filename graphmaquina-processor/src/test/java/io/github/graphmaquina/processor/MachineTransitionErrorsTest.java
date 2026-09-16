package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** What one transition of a machine must hold: its signature, its elements and the key it claims. */
class MachineTransitionErrorsTest {

    private static Compilation.Result body(String members) {
        return Compilation.compileTyped(Fixtures.withDocs(Fixtures.DOCS_HEADER, members));
    }

    private static Compilation.Result header(String typeAnnotations) {
        return Compilation.compileTyped(Fixtures.withDocs(typeAnnotations, Fixtures.DOCS_BODY));
    }

    static final String SUBMIT = """
                @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
            """;

    static final String ARCHIVE = """
                @DocsGraph.Transition(id = ARCHIVE, from = PUBLISHED, to = ARCHIVED, action = DocActionsRefs.ArchiveAsync.class, timeout = @Timeout(seconds = 2))
                Outcome<Doc, DocStatus, DocTransition> archive(Doc doc);
            """;

    private static String publish(String elements) {
        return "    @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class"
                + elements + ")\n    Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);\n";
    }

    /** The type-level transition of the header, with {@code elements} appended to it. */
    private static String declared(String elements) {
        return Fixtures.DOCS_HEADER.replace("@DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT)",
                "@DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT" + elements + ")");
    }

    @Test
    void sg504OperationSignatureAndMissingAnnotation() {
        assertTrue(body(SUBMIT + "    Outcome<Doc, DocStatus, DocTransition> orphan(Doc doc);\n"
                + publish("") + ARCHIVE).hasError("SG504"));
        assertTrue(body(SUBMIT + "    @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class)\n"
                + "    Outcome<Doc, DocStatus, DocTransition> publish(Doc doc, String reason, String extra);\n" + ARCHIVE).hasError("SG504"));
        assertTrue(body(SUBMIT + "    @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class)\n"
                + "    void publish(Doc doc);\n" + ARCHIVE).hasError("SG504"));
        Compilation.Result defaults = body(SUBMIT + publish("") + ARCHIVE
                + "    default Outcome<Doc, DocStatus, DocTransition> publishTwice(Doc doc) { publish(doc); return publish(doc); }\n");
        assertTrue(defaults.success(), defaults.describe());
    }

    /**
     * A symbol implements the category of every role its host declares and no other, so an element that asks
     * for a role the symbol does not export refuses it in the language itself, before any processor runs.
     */
    @Test
    void aSymbolUsedAsARoleItDoesNotExportIsAJavacTypeError() {
        Compilation.Result guard = body(SUBMIT + publish(", guards = DocActionsRefs.Publish.class") + ARCHIVE);
        assertFalse(guard.success(), guard.describe());
        assertFalse(guard.errors().stream().anyMatch(error -> error.contains("[SG")), guard.describe());
        assertTrue(guard.errors().stream().anyMatch(error ->
                error.contains("io.github.graphmaquina.symbol.GuardSymbol")), guard::describe);

        Compilation.Result hook = body(SUBMIT + publish(", afterCommit = DocActionsRefs.Approved.class") + ARCHIVE);
        assertFalse(hook.success(), hook.describe());
        assertTrue(hook.errors().stream().anyMatch(error ->
                error.contains("io.github.graphmaquina.symbol.HookSymbol")), hook::describe);
    }

    @Test
    void sg507WhatOnlyAnActionCanUse() {
        assertTrue(header(declared(", onFailure = PUBLISH")).hasError("SG507"));
        assertTrue(header(declared(", failsOn = java.io.IOException.class")).hasError("SG507"));
        assertTrue(header(declared(", timeout = @Timeout(seconds = 1)")).hasError("SG507"));
    }

    @Test
    void sg105DuplicateGuardAndSg302ForbiddenFailsOn() {
        assertTrue(body(SUBMIT + publish(", guards = {DocActionsRefs.Approved.class, DocActionsRefs.Approved.class}")
                + ARCHIVE).hasError("SG105"));
        assertTrue(body(SUBMIT + publish(", failsOn = RuntimeException.class") + ARCHIVE).hasError("SG302"));
    }

    @Test
    void sg102TheSameKeyClaimedByAnOperationAndByTheInterface() {
        Compilation.Result result = header(Fixtures.DOCS_HEADER
                + "@DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)\n");
        assertTrue(result.hasError("SG102"), result.describe());
    }

    @Test
    void staticGuardsSeveralGuardsAndRejectAreAccepted() {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withDocs(
                Fixtures.DOCS_HEADER
                        + "@DocsGraph.Reject(from = DRAFT, transition = ARCHIVE, reason = \"a draft is never archived\")\n",
                SUBMIT + publish(", guards = {DocActionsRefs.Ready.class, DocActionsRefs.Approved.class},"
                        + " failsOn = IOException.class") + ARCHIVE));
        assertTrue(result.success(), result.describe());
        Compilation.Result guardedMove = body("    @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW, guards = DocActionsRefs.Ready.class)\n"
                + "    Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);\n" + publish("") + ARCHIVE);
        assertTrue(guardedMove.success(), guardedMove.describe());
    }

    @Test
    void errorsAreCountedOncePerProblem() {
        Compilation.Result result = body(SUBMIT
                + publish(", guards = {DocActionsRefs.Approved.class, DocActionsRefs.Approved.class}")
                        .replace("from = REVIEW", "from = {REVIEW, PUBLISHED}")
                + ARCHIVE);
        assertEquals(1, result.countError("SG105"), result.describe());
    }
}
