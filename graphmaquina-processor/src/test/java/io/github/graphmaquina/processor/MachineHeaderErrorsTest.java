package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MachineHeaderErrorsTest {

    @Test
    void validDocsHasNoErrors() {
        Compilation.Result result = Compilation.compileTyped(Fixtures.validDocs());
        assertTrue(result.success(), result.describe());
    }

    /**
     * A state is an enum constant of the machine's own enum, so a name that is not one is not a diagnostic of
     * this tool at all: javac refuses it at the value, wherever the header spells a state.
     */
    @Test
    void aStateThatIsNotAConstantIsRefusedByJavacAlone() {
        List<String> headers = List.of(
                Fixtures.DOCS_HEADER.replace("initial = DRAFT", "initial = DRAFTT"),
                Fixtures.DOCS_HEADER.replace("terminals = ARCHIVED", "terminals = {ARCHIVED, GONE}"),
                Fixtures.DOCS_HEADER.replace("allowsCycle = REVIEW", "allowsCycle = LOOP"),
                Fixtures.DOCS_HEADER.replace("@DocsGraph.Row(state = DRAFT", "@DocsGraph.Row(state = DRAF"));
        for (String header : headers) {
            Compilation.Result result = Compilation.compileTyped(Fixtures.withDocs(header, Fixtures.DOCS_BODY));

            assertFalse(result.success(), result.describe());
            assertFalse(result.errors().stream().anyMatch(error -> error.contains("[SG")), result.describe());
            assertTrue(result.errors().stream().anyMatch(error -> error.contains("cannot find symbol")),
                    result::describe);
        }
    }

    @Test
    void sg502NotAnInterface() {
        Map<String, String> notInterface = Fixtures.domain();
        notInterface.put("fx.Docs", Fixtures.DOCS_IMPORTS + Fixtures.DOCS_HEADER
                + "public abstract class Docs implements Machine<Doc, DocStatus> {\n}\n");
        Compilation.Result result = Compilation.compileTyped(notInterface);
        assertTrue(result.hasError("SG502"), result.describe());
    }

    @Test
    void sg502NestedInterfaceIsRejectedAndGeneratesNothing() {
        Map<String, String> nested = Fixtures.domain();
        nested.put("fx.Holder", Fixtures.DOCS_IMPORTS.replace("@GenerateGraphApi\n", "")
                + "public final class Holder {\n@GenerateGraphApi\n"
                + Fixtures.DOCS_HEADER.replace("@DocsGraph.", "@Holder_DocsGraph.")
                + "public interface Docs extends Machine<Doc, DocStatus> {\n"
                + Fixtures.DOCS_BODY.replace("@DocsGraph.", "@Holder_DocsGraph.") + "}\n}\n");
        Compilation.Result result = Compilation.compileTyped(nested);
        assertTrue(result.hasError("SG502"), result.describe());
        assertFalse(result.hasGenerated("fx.Holder.Docs_"), result.describe());
        assertFalse(result.hasGenerated("fx.Docs_"), result.describe());
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
    void sg506MissingRowAndSg102DuplicateRowAndSg103BlankReason() {
        Compilation.Result missing = Compilation.compileTyped(Fixtures.withDocs(
                Fixtures.DOCS_HEADER.replace(
                        "@DocsGraph.Row(state = PUBLISHED, otherwise = \"a published doc can only be archived\")\n", ""),
                Fixtures.DOCS_BODY));
        assertTrue(missing.hasError("SG506"), missing.describe());
        assertTrue(missing.errors().get(0).contains("PUBLISHED"), missing.describe());

        Compilation.Result duplicate = Compilation.compileTyped(Fixtures.withDocs(
                Fixtures.DOCS_HEADER + "@DocsGraph.Row(state = DRAFT, otherwise = \"again\")\n", Fixtures.DOCS_BODY));
        assertTrue(duplicate.hasError("SG102"), duplicate.describe());

        Compilation.Result blank = Compilation.compileTyped(Fixtures.withDocs(
                Fixtures.DOCS_HEADER.replace("otherwise = \"a draft is sent to review\"", "otherwise = \" \""),
                Fixtures.DOCS_BODY));
        assertTrue(blank.hasError("SG103"), blank.describe());
        assertEquals(0, blank.countError("SG506"), blank.describe());
    }

    @Test
    void terminalMayCarryARowWithACustomReason() {
        Compilation.Result result = Compilation.compileTyped(Fixtures.withDocs(
                Fixtures.DOCS_HEADER + "@DocsGraph.Row(state = ARCHIVED, otherwise = \"archived docs are frozen\")\n",
                Fixtures.DOCS_BODY));
        assertTrue(result.success(), result.describe());
    }
}
