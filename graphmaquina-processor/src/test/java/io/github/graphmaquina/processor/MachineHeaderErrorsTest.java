package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * What the header of a machine must hold: a top-level interface ({@code SG502}) over an entity and two distinct
 * enums ({@code SG601}), properties that can serve the use they are named for ({@code SG503}), and, for every state
 * that is not terminal, one policy with a reason ({@code SG701}, {@code SG102}, {@code SG103}). A state that is not
 * a constant of the machine's own enum is javac's to refuse.
 */
class MachineHeaderErrorsTest {

    @Test
    void validDocsHasNoErrors() {
        Compilation.Result result = Compilation.compileTyped(Fixtures.validDocs());
        assertTrue(result.success(), result.describe());
    }

    /**
     * A state is an enum constant of the machine's own enum, so a name that is not one is not a diagnostic of
     * this tool at all: javac refuses it at the value, wherever the header spells a state or an identifier, and no
     * companion is generated. javac 21 words that error as {@code cannot find symbol} and javac 25 as
     * {@code an enum annotation value must be an enum constant}, so the test reads where it is, not what it says.
     */
    @Test
    void aStateThatIsNotAConstantIsRefusedByJavacAlone() {
        List<String[]> typos = List.of(
                new String[] {"initial = DRAFT", "initial = DRAFTT", "DRAFTT"},
                new String[] {"terminals = ARCHIVED", "terminals = {ARCHIVED, GONE}", "GONE"},
                new String[] {"allowsCycle = REVIEW", "allowsCycle = LOOP", "LOOP"},
                new String[] {"@DocsGraph.StatePolicy(state = DRAFT", "@DocsGraph.StatePolicy(state = DRAF", "DRAF"},
                new String[] {"@DocsGraph.Transition(id = RETURN", "@DocsGraph.Transition(id = RETURNN", "RETURNN"});
        for (String[] typo : typos) {
            assertTrue(Fixtures.DOCS_HEADER.contains(typo[0]), typo[0]);
            Map<String, String> sources =
                    Fixtures.withDocs(Fixtures.DOCS_HEADER.replace(typo[0], typo[1]), Fixtures.DOCS_BODY);
            long start = sources.get("fx.Docs").indexOf(typo[1]) + typo[1].lastIndexOf(typo[2]);

            Compilation.Result result = Compilation.compileTyped(sources);

            assertFalse(result.success(), result.describe());
            assertFalse(result.errors().stream().anyMatch(error -> error.contains("[SG")), result.describe());
            assertEquals(1, result.diagnostics().stream().filter((Diagnostic<? extends JavaFileObject> diagnostic) ->
                    diagnostic.getKind() == Diagnostic.Kind.ERROR && diagnostic.getStartPosition() == start
                            && diagnostic.getEndPosition() == start + typo[2].length()).count(), result.describe());
            assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
        }
    }

    /**
     * A generated symbol or a class named in a declaration is javac's to refuse when its name is mistyped, as a
     * constant is, with every source of the compilation handed to javac. The processor reads such a value as one
     * javac already refused: it does not report the machine as one the compilation never processed ({@code SG602}),
     * nor an edge whose action it cannot read as an edge without one ({@code SG507}), nor anything else.
     */
    @Test
    void aMistypedSymbolIsRefusedByJavacAlone() {
        List<String[]> typos = List.of(
                new String[] {"state = DocRefs.Status.class", "state = DocRefs.Statuss.class"},
                new String[] {"id = DocRefs.Id.class", "id = DocRefs.Ids.class"},
                new String[] {"action = DocActionsRefs.Publish.class", "action = DocActionsRefs.Publsh.class"},
                new String[] {"guards = DocActionsRefs.Approved.class", "guards = DocActionsRefs.Aproved.class"},
                new String[] {"afterCommit = DocActionsRefs.Notify.class", "afterCommit = DocActionsRefs.Notfy.class"},
                new String[] {"action = DocActionsRefs.ArchiveAsync.class",
                        "action = DocActionsRefs.ArchivAsync.class"},
                new String[] {"kind = Unavailable.class", "kind = Unavailabl.class"},
                new String[] {"failsOn = Broken.class", "failsOn = Brokn.class"},
                new String[] {"peers = DocRefs.Pages.class", "peers = DocRefs.Pags.class"},
                new String[] {"owner = PageRefs.Doc.class", "owner = PageRefs.Dc.class"});
        String publish = """
                    failsOn = Broken.class,
                    recoveries = @DocsGraph.Recovery(kind = Unavailable.class, transition = RETURN),
                    onFailure = RETURN,""";
        Map<String, String> valid = Fixtures.withLibrary(Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH,
                Fixtures.DOCS_BODY.replace("onFailure = RETURN,", publish), Fixtures.LIBRARY);
        valid.put("fx.Unavailable", """
                package fx;
                import io.github.graphmaquina.api.FailureKind;
                public record Unavailable() implements FailureKind { }
                """);
        valid.put("fx.Broken", """
                package fx;
                public final class Broken extends RuntimeException {
                    private static final long serialVersionUID = 1L;
                }
                """);
        Compilation.Result accepted = Compilation.compileTyped(valid);
        assertTrue(accepted.success(), accepted.describe());
        List<String> offenders = new ArrayList<>();
        for (String[] typo : typos) {
            Map<String, String> sources = new LinkedHashMap<>(valid);
            assertTrue(sources.get("fx.Docs").contains(typo[0]), typo[0]);
            sources.put("fx.Docs", sources.get("fx.Docs").replace(typo[0], typo[1]));

            Compilation.Result result = Compilation.compileTyped(sources);

            if (result.success() || result.errors().stream().anyMatch(error -> error.contains("[SG"))
                    || result.errors().stream().noneMatch(error -> error.contains("cannot find symbol"))) {
                offenders.add(typo[1] + "\n" + result.describe());
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n", offenders));
    }

    @Test
    void sg502NotAnInterface() {
        Map<String, String> notInterface = Fixtures.domain();
        notInterface.put("fx.Docs", Fixtures.DOCS_IMPORTS + Fixtures.DOCS_HEADER
                + "public abstract class Docs implements Machine<Doc, DocStatus, DocTransition> {\n}\n");
        Compilation.Result result = Compilation.compileTyped(notInterface);
        assertTrue(result.hasError(DiagnosticCode.SG502.name()), result.describe());
    }

    /**
     * A nested machine that declares its whole graph with the vocabulary the generator emits for it,
     * {@code HolderDocsGraph}, is refused for being nested, and for nothing else, and no companion is generated.
     */
    @Test
    void sg502NestedInterfaceIsRejectedAndGeneratesNothing() throws IOException {
        Map<String, String> nested = Fixtures.domain();
        nested.put("fx.Holder", Fixtures.DOCS_IMPORTS.replace("@GenerateGraphApi\n", "")
                + "public final class Holder {\n@GenerateGraphApi\n"
                + Fixtures.DOCS_HEADER.replace("@DocsGraph.", "@HolderDocsGraph.")
                + "public interface Docs extends Machine<Doc, DocStatus, DocTransition> {\n"
                + Fixtures.DOCS_BODY.replace("@DocsGraph.", "@HolderDocsGraph.") + "}\n}\n");
        Compilation.Result result = Compilation.compileTyped(nested);
        assertEquals(List.of("[" + DiagnosticCode.SG502
                + "] Docs must be a top-level interface: the generated companion lives next to it in"
                + " the package"), result.errors(), result.describe());
        try (Stream<Path> generated = Files.walk(result.output())) {
            assertEquals(List.of(), generated.filter(Files::isRegularFile).toList(), result.describe());
        }
    }

    /**
     * A nested machine is told that it must be top-level, whether or not it applies the Definition generated for
     * it. It is never told to apply that Definition first, which would only lead to the same refusal.
     */
    @Test
    void sg502NestedInterfaceIsToldToBeTopLevelWhetherOrNotItAppliesItsDefinition() {
        String definition = "@HolderInnerGraph.Definition(initial = DRAFT, terminals = ARCHIVED,"
                + " state = DocRefs.Status.class, id = DocRefs.Id.class)\n";
        for (String header : List.of("", definition)) {
            Map<String, String> sources = Fixtures.domain();
            sources.put("fx.Holder", """
                    package fx;
                    import io.github.graphmaquina.annotation.GenerateGraphApi;
                    import io.github.graphmaquina.api.Machine;
                    import static fx.DocStatus.*;
                    public final class Holder {
                        @GenerateGraphApi
                        %s
                        public interface Inner extends Machine<Doc, DocStatus, DocTransition> { }
                    }
                    """.formatted(header));

            Compilation.Result result = Compilation.compileTyped(sources);

            assertFalse(result.success(), result.describe());
            assertEquals(List.of("[" + DiagnosticCode.SG502
                    + "] Inner must be a top-level interface: the generated companion lives next to"
                    + " it in the package"), result.errors(), result.describe());
        }
    }

    /**
     * A machine declared as a class is told that it must be an interface, whether or not it applies the Definition
     * generated for it. It is never told to apply that Definition first, which would only lead to the same refusal.
     */
    @Test
    void sg502NotAnInterfaceIsToldSoWhetherOrNotItAppliesItsDefinition() {
        for (String header : List.of("", Fixtures.DOCS_HEADER)) {
            Map<String, String> sources = Fixtures.domain();
            sources.put("fx.Docs", Fixtures.DOCS_IMPORTS + header
                    + "public abstract class Docs implements Machine<Doc, DocStatus, DocTransition> {\n}\n");

            Compilation.Result result = Compilation.compileTyped(sources);

            assertFalse(result.success(), result.describe());
            assertEquals(List.of("[" + DiagnosticCode.SG502 + "] Docs must be an interface extending Machine<E, S, T>"),
                    result.errors(),
                    result.describe());
        }
    }

    /**
     * The machine still declares the vocabulary generated for it, but its own {@code Machine<E, S, T>} no
     * longer resolves to two distinct enums. The declaration itself is valid Java, so it is not SG502: the
     * generics are what contradict, and the catalogue gives that to SG601.
     */
    @Test
    void sg601StatesAndTransitionsThatAreNotTwoDistinctEnums() {
        Compilation.Prepared prepared = Compilation.prepare(Fixtures.validDocs());
        rewriteOriginal(prepared, "fx.Docs", Fixtures.docs(Fixtures.DOCS_HEADER, Fixtures.DOCS_BODY)
                .replace("Machine<Doc, DocStatus, DocTransition>", "Machine<Doc, DocStatus, DocStatus>"));

        Compilation.Result result = Compilation.compilePrepared(prepared);

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError(DiagnosticCode.SG601.name()), result.describe());
        assertEquals(0, result.countError(DiagnosticCode.SG502.name()), result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
    }

    /** Replaces one original after the generator has read it, as an edit between the two stages does. */
    private static void rewriteOriginal(Compilation.Prepared prepared, String qualifiedName, String source) {
        try {
            Files.writeString(prepared.request().sources().get(0)
                    .resolve(qualifiedName.replace('.', File.separatorChar) + ".java"), source, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** A property symbol exists, but the accessors it names cannot read or write what the use requires. */
    @Test
    void sg503APropertyThatCannotServeTheUseItIsNamedFor() {
        Compilation.Result state = Compilation.compileTyped(Fixtures.withDocs(
                Fixtures.DOCS_HEADER.replace("state = DocRefs.Status.class", "state = DocRefs.Pages.class"),
                Fixtures.DOCS_BODY));
        assertTrue(state.hasError(DiagnosticCode.SG503.name()), state.describe());
        assertTrue(state.errors().stream().anyMatch(error -> error.contains("[" + DiagnosticCode.SG503 + "]")
                && error.contains("'pages'")), state::describe);
    }

    @Test
    void sg701MissingPolicySg102DuplicatePolicyAndSg103BlankReason() {
        Compilation.Result missing = Compilation.compileTyped(Fixtures.withDocs(
                Fixtures.DOCS_HEADER.replace(
                        "@DocsGraph.StatePolicy(state = PUBLISHED, otherwise = "
                                + "\"a published doc can only be archived\")\n", ""),
                Fixtures.DOCS_BODY));
        assertTrue(missing.hasError(DiagnosticCode.SG701.name()), missing.describe());
        assertTrue(missing.errors().get(0).contains("PUBLISHED"), missing.describe());

        Compilation.Result duplicate = Compilation.compileTyped(Fixtures.withDocs(
                Fixtures.DOCS_HEADER + "@DocsGraph.StatePolicy(state = DRAFT, otherwise = \"again\")\n",
                Fixtures.DOCS_BODY));
        assertTrue(duplicate.hasError(DiagnosticCode.SG102.name()), duplicate.describe());

        Compilation.Result blank = Compilation.compileTyped(Fixtures.withDocs(
                Fixtures.DOCS_HEADER.replace("otherwise = \"a draft is sent to review\"", "otherwise = \" \""),
                Fixtures.DOCS_BODY));
        assertTrue(blank.hasError(DiagnosticCode.SG103.name()), blank.describe());
        assertEquals(0, blank.countError(DiagnosticCode.SG701.name()), blank.describe());
    }

    @Test
    void terminalMayCarryAPolicyWithACustomReason() {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withDocs(
                Fixtures.DOCS_HEADER
                        + "@DocsGraph.StatePolicy(state = ARCHIVED, otherwise = \"archived docs are frozen\")\n",
                Fixtures.DOCS_BODY));
        assertTrue(result.success(), result.describe());
    }
}
