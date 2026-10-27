package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Operations and accessors are read from the interface that declares them, and the companion implements what
 * it reads. An abstract method a facade or a set inherits from another super-interface than Machine or Machines
 * would be left unimplemented, so each one is refused where the interface inherits it, unless it declares again a
 * method that the companion writes or inherits with a result that method can return: the companion implements
 * that one already, and the interface is accepted.
 */
class InheritedMembersTest {

    private static final String EXTENDS = "public interface Docs extends Machine<Doc, DocStatus, DocTransition> {";

    @Test
    void sg504AnOperationTheFacadeInheritsFromAnotherInterface() {
        Map<String, String> sources = Fixtures.withDocs(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY);
        sources.put("fx.Archivable", """
                package fx;
                import io.github.graphmaquina.api.Outcome;
                public interface Archivable {
                    Outcome<Doc, DocStatus, DocTransition> shelve(Doc doc);
                }
                """);
        sources.put("fx.Docs", sources.get("fx.Docs").replace(EXTENDS,
                "public interface Docs extends Machine<Doc, DocStatus, DocTransition>, Archivable {"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.countError(DiagnosticCode.SG504.name()), result.describe());
        assertTrue(result.errors().get(0).contains("shelve") && result.errors().get(0).contains("fx.Archivable"),
                result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
    }

    /** The advice names the checks as well as the operations, since the companion implements both from the facade. */
    @Test
    void sg504AMethodTheFacadeInheritsFromAnotherInterfaceIsToBeDeclaredOnTheFacade() {
        Map<String, String> sources = Fixtures.withDocs(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY);
        sources.put("fx.Checkable", """
                package fx;
                import io.github.graphmaquina.api.Eligibility;
                public interface Checkable {
                    Eligibility<Doc, DocStatus, DocTransition> canShelve(Doc doc);
                }
                """);
        sources.put("fx.Docs", sources.get("fx.Docs").replace(EXTENDS,
                "public interface Docs extends Machine<Doc, DocStatus, DocTransition>, Checkable {"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(List.of("[" + DiagnosticCode.SG504
                + "] Docs inherits the abstract method canShelve from fx.Checkable, which the"
                + " generated companion would not implement: declare every operation and check on Docs itself"),
                result.errors(), result.describe());
    }

    /**
     * An operation declared on a base the facade extends is refused too. The fixture leaves the operation and its
     * edge out of the facade, so no state but the initial one is reachable, yet no SG203 follows: a machine with an
     * error is not checked as a whole.
     */
    @Test
    void sg504AnOperationDeclaredOnABaseOfTheFacade() {
        Map<String, String> sources = Fixtures.withDocs(Fixtures.DOCS_HEADER,
                Fixtures.DOCS_BODY.replace("""
                            @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                            Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                        """, ""));
        sources.put("fx.DocOperations", """
                package fx;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                public interface DocOperations extends Machine<Doc, DocStatus, DocTransition> {
                    Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                }
                """);
        sources.put("fx.Docs",
                sources.get("fx.Docs").replace(EXTENDS, "public interface Docs extends DocOperations {"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.countError(DiagnosticCode.SG504.name()), result.describe());
        assertTrue(result.errors().get(0).contains("submit") && result.errors().get(0).contains("fx.DocOperations"),
                result.describe());
        assertEquals(0, result.countError(DiagnosticCode.SG203.name()), result.describe());
    }

    /**
     * The same operation carrying its edge on the base: the edge is vocabulary the processor never reads there,
     * and the operation is one the companion would not implement. Both are reported in the same build.
     */
    @Test
    void reportsTheVocabularyOnABaseAndTheOperationItLeavesUnimplementedTogether() {
        Map<String, String> sources = Fixtures.withDocs(Fixtures.DOCS_HEADER,
                Fixtures.DOCS_BODY.replace("""
                            @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                            Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                        """, ""));
        sources.put("fx.DocOperations", """
                package fx;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import static fx.DocStatus.*;
                import static fx.DocTransition.*;
                public interface DocOperations extends Machine<Doc, DocStatus, DocTransition> {
                    @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                    Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                }
                """);
        sources.put("fx.Docs",
                sources.get("fx.Docs").replace(EXTENDS, "public interface Docs extends DocOperations {"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.countError(DiagnosticCode.SG606.name()), result.describe());
        assertEquals(1, result.countError(DiagnosticCode.SG504.name()), result.describe());
        assertEquals(0, result.countError(DiagnosticCode.SG203.name()), result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
    }

    /** A base that only redeclares methods of Machine or Object, or adds default ones, leaves nothing to implement. */
    @Test
    void aBaseWithNothingAbstractOfItsOwnIsAccepted() {
        Map<String, String> sources = Fixtures.withDocs(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY);
        sources.put("fx.Documented", """
                package fx;
                import io.github.graphmaquina.api.GraphDefinition;
                import io.github.graphmaquina.api.Machine;
                public interface Documented extends Machine<Doc, DocStatus, DocTransition> {
                    @Override GraphDefinition<Doc, DocStatus, DocTransition> definition();
                    @Override String toString();
                    default String label() { return "docs"; }
                }
                """);
        sources.put("fx.Docs", sources.get("fx.Docs").replace(EXTENDS, "public interface Docs extends Documented {"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.success(), result.describe());
    }

    /**
     * A super-interface that is no Machine or Machines may declare again a method of Machine, Machines or Object,
     * with a result the companion's own method can stand for: the companion implements it already, so the facade
     * and the set are generated, and they run.
     */
    @Test
    void anUnrelatedBaseRedeclaringWhatTheCompanionImplementsIsAccepted() throws Exception {
        Map<String, String> sources = redeclaringBases();

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.success(), result.describe());
        Class<?> docActions = result.load("fx.DocActions");
        Object builder = result.load("fx.LibraryRuntime").getMethod("builder", java.time.Duration.class, docActions)
                .invoke(null, java.time.Duration.ofSeconds(5), docActions.getConstructor().newInstance());
        try (AutoCloseable library = (AutoCloseable) builder.getClass().getMethod("build").invoke(builder)) {
            Object docs = library.getClass().getMethod("docs").invoke(library);
            Class<?> docClass = result.load("fx.Doc");
            Object doc = docClass.getConstructor(String.class).newInstance("d1");
            Object outcome = result.load("fx.Reevaluable").getMethod("reevaluate", Object.class).invoke(docs, doc);
            assertTrue(outcome instanceof io.github.graphmaquina.api.Outcome.Ignored, String.valueOf(outcome));
            Object answer = result.load("fx.AsyncCapable").getMethod("async", java.util.function.Supplier.class)
                    .invoke(docs, (java.util.function.Supplier<String>) () -> "answered");
            assertEquals("answered", ((java.util.concurrent.CompletionStage<?>) answer).toCompletableFuture().get());
        }
    }

    /** The bases of {@link #anUnrelatedBaseRedeclaringWhatTheCompanionImplementsIsAccepted()}, around the fx domain. */
    private static Map<String, String> redeclaringBases() {
        Map<String, String> sources = Fixtures.withPages(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY);
        sources.put("fx.Reevaluable", """
                package fx;
                import io.github.graphmaquina.api.Outcome;
                public interface Reevaluable<E> {
                    Outcome<E, ?, ?> reevaluate(E entity);
                }
                """);
        sources.put("fx.AsyncCapable", """
                package fx;
                import java.util.concurrent.CompletionStage;
                import java.util.function.Supplier;
                public interface AsyncCapable {
                    <R> CompletionStage<R> async(Supplier<R> call);
                }
                """);
        sources.put("fx.Describable", """
                package fx;
                public interface Describable {
                    String toString();
                    boolean equals(Object other);
                    int hashCode();
                }
                """);
        sources.put("fx.Lifecycle", """
                package fx;
                public interface Lifecycle {
                    void close();
                }
                """);
        sources.put("fx.Docs", sources.get("fx.Docs").replace(EXTENDS,
                "public interface Docs extends Machine<Doc, DocStatus, DocTransition>, Reevaluable<Doc>, "
                        + "AsyncCapable, Describable {"));
        sources.put("fx.Library", Fixtures.LIBRARY.replace("public interface Library extends Machines {",
                "public interface Library extends Machines, Lifecycle, Describable {"));
        return sources;
    }

    /**
     * The same redeclaration with a result more specific than the one the companion returns is not one the
     * companion implements, whether the facade inherits it or declares it: it is refused, never generated, and the
     * diagnostic names the result rather than sending the author to declare it on the facade, where it would be
     * refused again.
     */
    @Test
    void sg504ARedeclarationWhoseResultTheCompanionCannotReturn() {
        Map<String, String> inherited = Fixtures.withDocs(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY);
        inherited.put("fx.Strict", """
                package fx;
                import io.github.graphmaquina.api.Outcome;
                public interface Strict {
                    Outcome.Applied<Doc, DocStatus, DocTransition> reevaluate(Doc doc);
                }
                """);
        inherited.put("fx.Docs", inherited.get("fx.Docs").replace(EXTENDS,
                "public interface Docs extends Machine<Doc, DocStatus, DocTransition>, Strict {"));
        Compilation.Result throughBase = Compilation.compileTyped(inherited);
        assertEquals(1, throughBase.countError(DiagnosticCode.SG504.name()), throughBase.describe());
        assertTrue(throughBase.errors().get(0).contains("Docs inherits reevaluate from fx.Strict, which redeclares"
                + " Machine.reevaluate with a result the generated companion's reevaluate cannot return: declare it in"
                + " fx.Strict with the result Machine.reevaluate has, or leave the redeclaration out"),
                throughBase.describe());
        assertFalse(throughBase.hasGenerated("fx.DocsRuntime"), throughBase.describe());

        Compilation.Result declared = Compilation.compileTyped(Fixtures.withDocs(Fixtures.DOCS_HEADER,
                Fixtures.DOCS_BODY
                        + "    @Override io.github.graphmaquina.api.Outcome.Applied<Doc, DocStatus, DocTransition> "
                        + "reevaluate(Doc doc);\n"));
        assertEquals(1, declared.countError(DiagnosticCode.SG504.name()), declared.describe());
        assertTrue(declared.errors().get(0).contains("reevaluate redeclares Machine.reevaluate with a result the"
                + " generated companion's reevaluate cannot return: declare it with the result Machine.reevaluate has,"
                + " or leave the redeclaration out"), declared.describe());
        assertFalse(declared.hasGenerated("fx.DocsRuntime"), declared.describe());
    }

    /** A set inherits an accessor: it is not "no machine", it is an accessor the companion would not implement. */
    @Test
    void sg510AnAccessorTheSetInheritsFromAnotherInterface() {
        Map<String, String> sources = Fixtures.withPages(Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH,
                Fixtures.DOCS_BODY);
        sources.put("fx.HasPages", """
                package fx;
                public interface HasPages {
                    Pages pages();
                }
                """);
        sources.put("fx.Library", Fixtures.LIBRARY.replace("public interface Library extends Machines {",
                "public interface Library extends Machines, HasPages {").replace("    Pages pages();\n", ""));

        Compilation.Result inheritedAndDeclared = Compilation.compileTyped(sources);

        assertEquals(1, inheritedAndDeclared.countError(DiagnosticCode.SG510.name()), inheritedAndDeclared.describe());
        assertTrue(inheritedAndDeclared.errors().get(0).contains("pages")
                && inheritedAndDeclared.errors().get(0).contains("fx.HasPages"), inheritedAndDeclared.describe());
        assertFalse(inheritedAndDeclared.hasGenerated("fx.LibraryRuntime"), inheritedAndDeclared.describe());

        sources.put("fx.Library", sources.get("fx.Library").replace("    Docs docs();\n", ""));
        sources.put("fx.Docs", Fixtures.docs(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY));
        Compilation.Result inheritedOnly = Compilation.compileTyped(sources);

        assertEquals(1, inheritedOnly.countError(DiagnosticCode.SG510.name()), inheritedOnly.describe());
        assertTrue(inheritedOnly.errors().get(0).contains("fx.HasPages"), inheritedOnly.describe());
        assertFalse(inheritedOnly.errors().get(0).contains("declares no machine"), inheritedOnly.describe());
    }
}
