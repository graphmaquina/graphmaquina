package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * this tool at all: javac refuses it at the value, wherever the header spells a state or an identifier.
     */
    @Test
    void aStateThatIsNotAConstantIsRefusedByJavacAlone() {
        List<String> headers = List.of(
                Fixtures.DOCS_HEADER.replace("initial = DRAFT", "initial = DRAFTT"),
                Fixtures.DOCS_HEADER.replace("terminals = ARCHIVED", "terminals = {ARCHIVED, GONE}"),
                Fixtures.DOCS_HEADER.replace("allowsCycle = REVIEW", "allowsCycle = LOOP"),
                Fixtures.DOCS_HEADER.replace("@DocsGraph.StatePolicy(state = DRAFT", "@DocsGraph.StatePolicy(state = DRAF"),
                Fixtures.DOCS_HEADER.replace("@DocsGraph.Transition(id = RETURN", "@DocsGraph.Transition(id = RETURNN"));
        for (String header : headers) {
            Compilation.Result result = Compilation.compileTyped(Fixtures.withDocs(header, Fixtures.DOCS_BODY));

            assertFalse(result.success(), result.describe());
            assertFalse(result.errors().stream().anyMatch(error -> error.contains("[SG")), result.describe());
            assertTrue(result.errors().stream().anyMatch(error -> error.contains("cannot find symbol")),
                    result::describe);
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
        assertTrue(result.hasError("SG502"), result.describe());
    }

    @Test
    void sg502NestedInterfaceIsRejectedAndGeneratesNothing() {
        Map<String, String> nested = Fixtures.domain();
        nested.put("fx.Holder", Fixtures.DOCS_IMPORTS.replace("@GenerateGraphApi\n", "")
                + "public final class Holder {\n@GenerateGraphApi\n"
                + Fixtures.DOCS_HEADER.replace("@DocsGraph.", "@Holder_DocsGraph.")
                + "public interface Docs extends Machine<Doc, DocStatus, DocTransition> {\n"
                + Fixtures.DOCS_BODY.replace("@DocsGraph.", "@Holder_DocsGraph.") + "}\n}\n");
        Compilation.Result result = Compilation.compileTyped(nested);
        assertTrue(result.hasError("SG502"), result.describe());
        assertFalse(result.hasGenerated("fx.Holder.DocsRuntime"), result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
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
        assertEquals(1, result.countError("SG601"), result.describe());
        assertEquals(0, result.countError("SG502"), result.describe());
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
        assertTrue(state.hasError("SG503"), state.describe());
        assertTrue(state.errors().stream().anyMatch(error -> error.contains("[SG503]") && error.contains("'pages'")),
                state::describe);
    }

    @Test
    void sg701MissingPolicySg102DuplicatePolicyAndSg103BlankReason() {
        Compilation.Result missing = Compilation.compileTyped(Fixtures.withDocs(
                Fixtures.DOCS_HEADER.replace(
                        "@DocsGraph.StatePolicy(state = PUBLISHED, otherwise = \"a published doc can only be archived\")\n", ""),
                Fixtures.DOCS_BODY));
        assertTrue(missing.hasError("SG701"), missing.describe());
        assertTrue(missing.errors().get(0).contains("PUBLISHED"), missing.describe());

        Compilation.Result duplicate = Compilation.compileTyped(Fixtures.withDocs(
                Fixtures.DOCS_HEADER + "@DocsGraph.StatePolicy(state = DRAFT, otherwise = \"again\")\n",
                Fixtures.DOCS_BODY));
        assertTrue(duplicate.hasError("SG102"), duplicate.describe());

        Compilation.Result blank = Compilation.compileTyped(Fixtures.withDocs(
                Fixtures.DOCS_HEADER.replace("otherwise = \"a draft is sent to review\"", "otherwise = \" \""),
                Fixtures.DOCS_BODY));
        assertTrue(blank.hasError("SG103"), blank.describe());
        assertEquals(0, blank.countError("SG701"), blank.describe());
    }

    @Test
    void terminalMayCarryAPolicyWithACustomReason() {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withDocs(
                Fixtures.DOCS_HEADER + "@DocsGraph.StatePolicy(state = ARCHIVED, otherwise = \"archived docs are frozen\")\n",
                Fixtures.DOCS_BODY));
        assertTrue(result.success(), result.describe());
    }
}
