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
                @DocsGraph.Transition(id = ARCHIVE, from = PUBLISHED, to = ARCHIVED, \
            action = DocActionsRefs.ArchiveAsync.class, timeout = @Timeout(seconds = 2))
                Outcome<Doc, DocStatus, DocTransition> archive(Doc doc);
            """;

    private static String publish(String elements) {
        return "    @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED,"
                + " action = DocActionsRefs.Publish.class"
                + elements + ")\n    Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);\n";
    }

    /** The header with {@code elements} appended to its type-level transition. */
    private static String declared(String elements) {
        return Fixtures.DOCS_HEADER.replace("@DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT)",
                "@DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT" + elements + ")");
    }

    @Test
    void sg504OperationSignatureAndMissingAnnotation() {
        assertTrue(body(SUBMIT + "    Outcome<Doc, DocStatus, DocTransition> orphan(Doc doc);\n"
                + publish("") + ARCHIVE).hasError("SG504"));
        assertTrue(body(SUBMIT
                + "    @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED,"
                + " action = DocActionsRefs.Publish.class)\n"
                + "    Outcome<Doc, DocStatus, DocTransition> publish(Doc doc, String reason, String extra);\n"
                + ARCHIVE).hasError("SG504"));
        assertTrue(body(SUBMIT
                + "    @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED,"
                + " action = DocActionsRefs.Publish.class)\n"
                + "    void publish(Doc doc);\n" + ARCHIVE).hasError("SG504"));
        Compilation.Result defaults = body(SUBMIT + publish("") + ARCHIVE
                + "    default Outcome<Doc, DocStatus, DocTransition> publishTwice(Doc doc) {"
                + " publish(doc); return publish(doc); }\n");
        assertTrue(defaults.success(), defaults.describe());
    }

    /**
     * A facade may redeclare a method of Machine or of Object, to document it, and the companion implements it
     * already: such a redeclaration declares no edge. An operation that would override one of them could not be
     * implemented next to it, so it is refused with SG504 rather than left to collide in the companion.
     */
    @Test
    void sg504ForAnOperationOverridingAMachineMethodButRedeclarationsAreNoOperations() {
        Compilation.Result redeclared = body(SUBMIT + publish("") + ARCHIVE
                + "    @Override io.github.graphmaquina.api.GraphDefinition<Doc, DocStatus, DocTransition>"
                + " definition();\n"
                + "    @Override String toString();\n"
                + "    @Override boolean equals(Object other);\n");
        assertTrue(redeclared.success(), redeclared.describe());

        Compilation.Result overriding = body(SUBMIT + publish("")
                + ARCHIVE.replace("archive(Doc doc)", "reevaluate(Doc doc)"));
        assertEquals(1, overriding.countError("SG504"), overriding.describe());
        assertTrue(overriding.errors().get(0).contains("reevaluate") && overriding.errors().get(0).contains("Machine"),
                overriding.describe());
        assertFalse(overriding.hasGenerated("fx.DocsRuntime"), overriding.describe());
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

    /**
     * The runtime never treats a cancellation as a domain failure, and it unwraps a {@code CompletionException}
     * or an {@code ExecutionException} before matching, so a route declared on one of them could never run.
     * Only the two wrappers themselves are refused: a subtype of either is matched as it is thrown.
     */
    @Test
    void sg302RefusesWhatTheRuntimeNeverMatchesAsADomainFailure() {
        java.util.Map<String, String> refused = Fixtures.withDocs(Fixtures.DOCS_HEADER, SUBMIT
                + publish(", failsOn = {java.util.concurrent.CancellationException.class, Halted.class,"
                        + " java.util.concurrent.CompletionException.class,"
                        + " java.util.concurrent.ExecutionException.class}")
                + ARCHIVE);
        refused.put("fx.Halted", """
                package fx;
                public class Halted extends java.util.concurrent.CancellationException { }
                """);
        Compilation.Result result = Compilation.compileTyped(refused);

        assertEquals(4, result.countError("SG302"), result.describe());
        assertTrue(result.errors().contains("[SG302] failsOn does not allow java.util.concurrent.CancellationException;"
                + " only throwable types foreign to the library, and never Throwable, Exception, RuntimeException,"
                + " an Error or a CancellationException, which are never domain failures, nor CompletionException or"
                + " ExecutionException, which are unwrapped before matching: declare the type of their cause"),
                result.describe());
        assertTrue(result.errors().stream().anyMatch(
                (String error) -> error.startsWith("[SG302] failsOn does not allow fx.Halted;")),
                result.describe());
        assertTrue(result.errors().stream().anyMatch((String error) -> error.startsWith(
                "[SG302] failsOn does not allow java.util.concurrent.ExecutionException;")), result.describe());

        java.util.Map<String, String> accepted = Fixtures.withDocs(Fixtures.DOCS_HEADER, SUBMIT
                + publish(", failsOn = {IllegalStateException.class, Wrapped.class}") + ARCHIVE);
        accepted.put("fx.Wrapped", """
                package fx;
                public class Wrapped extends java.util.concurrent.CompletionException { }
                """);
        Compilation.Result allowed = Compilation.compileTyped(accepted);
        assertTrue(allowed.success(), allowed.describe());
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
                        + "@DocsGraph.Reject(from = DRAFT, transition = ARCHIVE,"
                        + " reason = \"a draft is never archived\")\n",
                SUBMIT + publish(", guards = {DocActionsRefs.Ready.class, DocActionsRefs.Approved.class},"
                        + " failsOn = IOException.class") + ARCHIVE));
        assertTrue(result.success(), result.describe());
        Compilation.Result guardedMove = body("    @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW,"
                + " guards = DocActionsRefs.Ready.class)\n"
                + "    Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);\n" + publish("") + ARCHIVE);
        assertTrue(guardedMove.success(), guardedMove.describe());
    }

    /** Two independent problems on one declaration, each reported once. */
    @Test
    void errorsAreCountedOncePerProblem() {
        Compilation.Result result = body(SUBMIT
                + publish(", guards = {DocActionsRefs.Approved.class, DocActionsRefs.Approved.class},"
                        + " failsOn = RuntimeException.class")
                + ARCHIVE);
        assertEquals(1, result.countError("SG105"), result.describe());
        assertEquals(1, result.countError("SG302"), result.describe());
    }
}
