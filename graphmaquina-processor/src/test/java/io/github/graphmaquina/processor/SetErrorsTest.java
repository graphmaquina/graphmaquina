package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a {@code @StateGraphs} set must hold. A set that is not a top-level interface extending {@code Machines} is
 * refused as {@code SG502}. An accessor that takes an argument or returns no machine, a machine held twice, a
 * watch whose peer the set lacks, two machines over one entity and state enum, a set without a machine and an
 * accessor that would collide with the companion's own {@code builder()} are refused as {@code SG510}; the last is
 * checked only once nothing else is wrong with the set. A machine with errors adds no error to its set.
 */
class SetErrorsTest {

    private static Compilation.Result library(String source) {
        return Compilation.compileTyped(Fixtures.withLibrary(Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH,
                Fixtures.DOCS_BODY, source));
    }

    @Test
    void validSetIsAccepted() {
        Compilation.Result result = library(Fixtures.LIBRARY);
        assertTrue(result.success(), result.describe());
    }

    @Test
    void sg502NotAnInterfaceOrNotMachines() {
        assertTrue(library(Fixtures.LIBRARY.replace("public interface Library extends Machines",
                "public abstract class Library implements Machines")).hasError(DiagnosticCode.SG502.name()));
        assertTrue(library(Fixtures.LIBRARY.replace(" extends Machines", "")).hasError(DiagnosticCode.SG502.name()));
    }

    @Test
    void sg502NestedSetIsRejectedAndGeneratesNothing() {
        Map<String, String> sources = Fixtures.withPages(Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH,
                Fixtures.DOCS_BODY);
        sources.put("fx.Holder", """
                package fx;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                public final class Holder {
                    @StateGraphs
                    public interface Library extends Machines {
                        Docs docs();
                        Pages pages();
                    }
                }
                """);
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.hasError(DiagnosticCode.SG502.name()), result.describe());
        assertFalse(result.hasGenerated("fx.LibraryRuntime"), result.describe());
    }

    @Test
    void sg510AccessorShapeDuplicatesAndMissingPeer() {
        assertTrue(library(Fixtures.LIBRARY.replace("Docs docs();",
                "Docs docs(String name);")).hasError(DiagnosticCode.SG510.name()));
        assertTrue(library(Fixtures.LIBRARY.replace("Docs docs();",
                "String docs();")).hasError(DiagnosticCode.SG510.name()));
        assertTrue(library(Fixtures.LIBRARY.replace("Pages pages();", "Pages pages();\n    Pages more();"))
                .hasError(DiagnosticCode.SG510.name()));
        Compilation.Result missingPeer = library(Fixtures.LIBRARY.replace("    Pages pages();\n", ""));
        assertTrue(missingPeer.hasError(DiagnosticCode.SG510.name()), missingPeer.describe());
        assertTrue(missingPeer.errors().get(0).contains("Pages"), missingPeer.describe());
        assertTrue(library(Fixtures.LIBRARY.replace("    Docs docs();\n    Pages pages();\n",
                "")).hasError(DiagnosticCode.SG510.name()));
    }

    /**
     * A registry refuses two machines that write the same entity and state enum, whether their transition
     * catalogues differ or not, so a set holding two of them could never be built: it is refused where its
     * second accessor declares the second machine.
     */
    @Test
    void sg510TwoMachinesWritingTheSameEntityAndStateEnum() {
        Map<String, String> catalogues = Fixtures.withDocs(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY);
        catalogues.put("fx.ReviewTransition", "package fx;\npublic enum ReviewTransition { SEND, ACCEPT, SHELVE }\n");
        catalogues.put("fx.Reviews", """
                package fx;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import static fx.DocStatus.*;
                import static fx.ReviewTransition.*;
                @GenerateGraphApi
                @ReviewsGraph.Definition(initial = DRAFT, terminals = ARCHIVED, \
                state = DocRefs.Status.class, id = DocRefs.Id.class)
                @ReviewsGraph.StatePolicy(state = DRAFT, otherwise = "a draft is sent")
                @ReviewsGraph.StatePolicy(state = REVIEW, otherwise = "a review is accepted")
                @ReviewsGraph.StatePolicy(state = PUBLISHED, otherwise = "a published doc is shelved")
                public interface Reviews extends Machine<Doc, DocStatus, ReviewTransition> {
                    @ReviewsGraph.Transition(id = SEND, from = DRAFT, to = REVIEW)
                    Outcome<Doc, DocStatus, ReviewTransition> send(Doc doc);
                    @ReviewsGraph.Transition(id = ACCEPT, from = REVIEW, to = PUBLISHED)
                    Outcome<Doc, DocStatus, ReviewTransition> accept(Doc doc);
                    @ReviewsGraph.Transition(id = SHELVE, from = PUBLISHED, to = ARCHIVED)
                    Outcome<Doc, DocStatus, ReviewTransition> shelve(Doc doc);
                }
                """);
        catalogues.put("fx.Desk", """
                package fx;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                @StateGraphs
                public interface Desk extends Machines {
                    Docs docs();
                    Reviews reviews();
                }
                """);
        Compilation.Result different = Compilation.compileTyped(catalogues);
        assertEquals(1, different.countError(DiagnosticCode.SG510.name()), different.describe());
        assertTrue(different.errors().get(0).contains("reviews") && different.errors().get(0).contains("docs"),
                different.describe());
        assertFalse(different.hasGenerated("fx.DeskRuntime"), different.describe());

        Map<String, String> same = Fixtures.withDocs(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY);
        same.put("fx.Copies", Fixtures.docs(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY)
                .replace("DocsGraph", "CopiesGraph").replace("interface Docs ", "interface Copies "));
        same.put("fx.Desk", catalogues.get("fx.Desk").replace("Reviews reviews();", "Copies copies();"));
        Compilation.Result identical = Compilation.compileTyped(same);
        assertEquals(1, identical.countError(DiagnosticCode.SG510.name()), identical.describe());
        assertFalse(identical.hasGenerated("fx.DeskRuntime"), identical.describe());
    }

    /**
     * An accessor is a member of the set companion too. One named like a field the companion declares only
     * renames that private field, while one whose signature is the companion's own static {@code builder()},
     * which takes nothing for a synchronous set without dependencies, cannot be declared next to it; the refusal
     * names that companion.
     */
    @Test
    void sg510AnAccessorCollidingWithTheCompanionsBuilderAndRenamedFieldsOtherwise() {
        Compilation.Result builder = synchronousLibrary("Pages builder();");
        assertFalse(builder.success(), builder.describe());
        assertEquals(1, builder.countError(DiagnosticCode.SG510.name()), builder.describe());
        assertTrue(builder.errors().get(0).contains("[" + DiagnosticCode.SG510
                + "] builder() is the static builder() the generated"
                + " LibraryRuntime declares"), builder.describe());
        assertFalse(builder.hasGenerated("fx.LibraryRuntime"), builder.describe());

        Compilation.Result registry = synchronousLibrary("Pages machineRegistry();");
        assertTrue(registry.success(), registry.describe());

        Compilation.Result overload = library(Fixtures.LIBRARY.replace("Pages pages();", "Pages builder();"));
        assertTrue(overload.success(), overload.describe());
    }

    /** A set holding only Pages, a synchronous machine without dependencies, under {@code accessor}. */
    private static Compilation.Result synchronousLibrary(String accessor) {
        Map<String, String> sources = Fixtures.withPages(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY);
        sources.put("fx.Library",
                Fixtures.LIBRARY.replace("    Docs docs();\n    Pages pages();\n", "    " + accessor + "\n"));
        return Compilation.compileTyped(sources);
    }

    /**
     * Neither as a missing machine nor through what the set's companion would declare without it: Docs is
     * asynchronous and has an instance dependency, so with Docs valid the companion's {@code builder(...)} takes both,
     * and an accessor named {@code builder} is only an overload of it.
     */
    @Test
    void aMachineWithErrorsDoesNotAddSetErrors() {
        String blankPolicy = Fixtures.DOCS_HEADER
                .replace("otherwise = \"a draft is sent to review\"", "otherwise = \" \"");
        Compilation.Result result = Compilation.compileTyped(Fixtures.withLibrary(blankPolicy + Fixtures.DOCS_WATCH,
                Fixtures.DOCS_BODY, Fixtures.LIBRARY));
        assertEquals(1, result.errors().size(), result.describe());
        assertTrue(result.hasError(DiagnosticCode.SG103.name()), result.describe());

        Compilation.Result builder = Compilation.compileTyped(Fixtures.withLibrary(blankPolicy, Fixtures.DOCS_BODY,
                Fixtures.LIBRARY.replace("Pages pages();", "Pages builder();")));
        assertEquals(1, builder.errors().size(), builder.describe());
        assertTrue(builder.hasError(DiagnosticCode.SG103.name()), builder.describe());
    }

    /**
     * What the companion's {@code builder(...)} takes is known only once every accessor of the set holds a machine
     * it builds: here {@code docs} takes an argument, and the asynchronous Docs it returns, which has an instance
     * dependency, is held by no accessor yet. The accessor named {@code builder} is not reported as well.
     */
    @Test
    void aBuilderAccessorIsNotReportedBesideAnotherErrorOfTheSet() {
        Compilation.Result result = library(Fixtures.LIBRARY.replace("Docs docs();", "Docs docs(String name);")
                .replace("Pages pages();", "Pages builder();"));

        assertEquals(1, result.errors().size(), result.describe());
        assertTrue(result.errors().get(0).contains("[" + DiagnosticCode.SG510
                + "] docs must be a no-argument accessor"), result.describe());
    }
}
