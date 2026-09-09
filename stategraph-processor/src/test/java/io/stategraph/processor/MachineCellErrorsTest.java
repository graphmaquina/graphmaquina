package io.stategraph.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MachineCellErrorsTest {

    private static Compilation.Result body(String members) {
        return Compilation.compileTyped(Fixtures.withDocs(Fixtures.DOCS_HEADER, members));
    }

    private static Compilation.Result header(String typeAnnotations) {
        return Compilation.compileTyped(Fixtures.withDocs(typeAnnotations, Fixtures.DOCS_BODY));
    }

    static final String SUBMIT = """
                @DocsGraph.Move(from = DRAFT, to = REVIEW)
                Outcome<Doc, DocStatus> submit(Doc doc);
            """;

    static final String ARCHIVE = """
                @DocsGraph.Action(from = PUBLISHED, to = ARCHIVED, action = DocActionsRefs.ArchiveAsync.class, timeout = @Timeout(seconds = 2))
                Outcome<Doc, DocStatus> archive(Doc doc);
            """;

    private static String publish(String elements) {
        return "    @DocsGraph.Action(from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class"
                + elements + ")\n    Outcome<Doc, DocStatus> publish(Doc doc);\n";
    }

    /** The type-level cell of the header, with {@code elements} appended to it. */
    private static String cell(String elements) {
        return Fixtures.DOCS_HEADER.replace("@DocsGraph.Cell(from = REVIEW, to = DRAFT)",
                "@DocsGraph.Cell(from = REVIEW, to = DRAFT" + elements + ")");
    }

    @Test
    void sg504OperationSignatureAndMissingOrDoubleAnnotation() {
        assertTrue(body(SUBMIT + "    Outcome<Doc, DocStatus> orphan(Doc doc);\n" + publish("") + ARCHIVE).hasError("SG504"));
        assertTrue(body(SUBMIT + "    @DocsGraph.Move(from = REVIEW, to = PUBLISHED)\n" + publish("") + ARCHIVE).hasError("SG504"));
        assertTrue(body(SUBMIT + "    @DocsGraph.Action(from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class)\n"
                + "    Outcome<Doc, DocStatus> publish(Doc doc, String reason);\n" + ARCHIVE).hasError("SG504"));
        assertTrue(body(SUBMIT + "    @DocsGraph.Action(from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class)\n"
                + "    void publish(Doc doc);\n" + ARCHIVE).hasError("SG504"));
        Compilation.Result defaults = body(SUBMIT + publish("") + ARCHIVE
                + "    default Outcome<Doc, DocStatus> publishTwice(Doc doc) { publish(doc); return publish(doc); }\n");
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
                error.contains("io.stategraph.symbol.GuardSymbol")), guard::describe);

        Compilation.Result hook = body(SUBMIT + publish(", afterCommit = DocActionsRefs.Approved.class") + ARCHIVE);
        assertFalse(hook.success(), hook.describe());
        assertTrue(hook.errors().stream().anyMatch(error ->
                error.contains("io.stategraph.symbol.HookSymbol")), hook::describe);
    }

    @Test
    void sg507WhatOnlyAnActionCanUse() {
        assertTrue(header(cell(", onFailure = REVIEW")).hasError("SG507"));
        assertTrue(header(cell(", failsOn = java.io.IOException.class")).hasError("SG507"));
        assertTrue(header(cell(", timeout = @Timeout(seconds = 1)")).hasError("SG507"));
    }

    @Test
    void sg104SelfLoopSg105DuplicateGuardAndSg302ForbiddenFailsOn() {
        assertTrue(body(SUBMIT + publish("").replace("from = REVIEW", "from = {REVIEW, PUBLISHED}") + ARCHIVE).hasError("SG104"));
        assertTrue(body(SUBMIT + publish(", guards = {DocActionsRefs.Approved.class, DocActionsRefs.Approved.class}")
                + ARCHIVE).hasError("SG105"));
        assertTrue(body(SUBMIT + publish(", failsOn = RuntimeException.class") + ARCHIVE).hasError("SG302"));
    }

    @Test
    void sg102DuplicateCellAcrossOperationsAndCells() {
        Compilation.Result result = header(Fixtures.DOCS_HEADER + "@DocsGraph.Cell(from = DRAFT, to = REVIEW)\n");
        assertTrue(result.hasError("SG102"), result.describe());
        Compilation.Result forbid = header(Fixtures.DOCS_HEADER
                + "@DocsGraph.Forbid(from = REVIEW, to = DRAFT, reason = \"no\")\n");
        assertTrue(forbid.hasError("SG102"), forbid.describe());
    }

    @Test
    void staticGuardsSeveralGuardsAndForbidAreAccepted() {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withDocs(
                Fixtures.DOCS_HEADER
                        + "@DocsGraph.Forbid(from = DRAFT, to = ARCHIVED, reason = \"a draft is never archived\")\n",
                SUBMIT + publish(", guards = {DocActionsRefs.Ready.class, DocActionsRefs.Approved.class},"
                        + " failsOn = IOException.class") + ARCHIVE));
        assertTrue(result.success(), result.describe());
        Compilation.Result guardedMove = body("    @DocsGraph.Move(from = DRAFT, to = REVIEW, guards = DocActionsRefs.Ready.class)\n"
                + "    Outcome<Doc, DocStatus> submit(Doc doc);\n" + publish("") + ARCHIVE);
        assertTrue(guardedMove.success(), guardedMove.describe());
    }

    @Test
    void errorsAreCountedOncePerProblem() {
        Compilation.Result result = body(SUBMIT
                + publish(", guards = {DocActionsRefs.Approved.class, DocActionsRefs.Approved.class}")
                        .replace("from = REVIEW", "from = {REVIEW, PUBLISHED}")
                + ARCHIVE);
        assertEquals(1, result.countError("SG104"), result.describe());
        assertEquals(1, result.countError("SG105"), result.describe());
    }
}
