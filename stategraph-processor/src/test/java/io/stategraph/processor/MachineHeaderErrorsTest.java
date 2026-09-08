package io.stategraph.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MachineHeaderErrorsTest {

    @Test
    void validDocsHasNoErrors() {
        Compilation.Result result = Compilation.compile(Fixtures.validDocs());
        assertTrue(result.success(), result.describe());
    }

    @Test
    void sg501UnknownStateWithSuggestionAnchoredAtTheValue() {
        Compilation.Result result = Compilation.compile(Fixtures.withDocs(
                Fixtures.DOCS_HEADER.replace("initial = \"DRAFT\"", "initial = \"DRAFTT\""), Fixtures.DOCS_BODY));
        assertFalse(result.success());
        assertEquals(1, result.countError("SG501"), result.describe());
        assertTrue(result.errors().get(0).contains("did you mean DRAFT?"), result.describe());
        assertTrue(result.errors().get(0).contains("DRAFT, REVIEW, PUBLISHED, ARCHIVED"), result.describe());
        // DOCS_IMPORTS is 12 lines; @StateGraph is the first line of the header, so line 13.
        assertEquals(13, result.lineOf("SG501"), result.describe());
    }

    @Test
    void sg501InTerminalsAndAllowsCycleAndRow() {
        Compilation.Result result = Compilation.compile(Fixtures.withDocs(
                Fixtures.DOCS_HEADER.replace("terminals = {\"ARCHIVED\"}", "terminals = {\"ARCHIVED\", \"GONE\"}")
                        .replace("allowsCycle = \"REVIEW\"", "allowsCycle = \"LOOP\"")
                        .replace("@Row(state = \"DRAFT\"", "@Row(state = \"DRAF\""), Fixtures.DOCS_BODY));
        assertEquals(3, result.countError("SG501"), result.describe());
    }

    @Test
    void sg502NotAnInterfaceOrNotAMachine() {
        Map<String, String> notInterface = Fixtures.domain();
        notInterface.put("fx.Docs", Fixtures.DOCS_IMPORTS + Fixtures.DOCS_HEADER
                + "public abstract class Docs implements Machine<Doc, DocStatus> {\n}\n");
        assertTrue(Compilation.compile(notInterface).hasError("SG502"));

        Map<String, String> notMachine = Fixtures.domain();
        notMachine.put("fx.Docs", Fixtures.DOCS_IMPORTS + Fixtures.DOCS_HEADER + "public interface Docs {\n}\n");
        assertTrue(Compilation.compile(notMachine).hasError("SG502"));

        Map<String, String> notEnum = Fixtures.domain();
        notEnum.put("fx.Docs", Fixtures.DOCS_IMPORTS + Fixtures.DOCS_HEADER + "public interface Docs extends Machine<Doc, String> {\n}\n");
        assertTrue(Compilation.compile(notEnum).hasError("SG502"));
    }

    @Test
    void sg502NestedInterfaceIsRejectedAndGeneratesNothing() {
        Map<String, String> nested = Fixtures.domain();
        nested.put("fx.Holder", Fixtures.DOCS_IMPORTS + "public final class Holder {\n" + Fixtures.DOCS_HEADER
                + "public interface Docs extends Machine<Doc, DocStatus> {\n" + Fixtures.DOCS_BODY + "}\n}\n");
        Compilation.Result result = Compilation.compile(nested);
        assertTrue(result.hasError("SG502"), result.describe());
        assertFalse(result.hasGenerated("fx.Holder.Docs_"), result.describe());
        assertFalse(result.hasGenerated("fx.Docs_"), result.describe());
    }

    @Test
    void sg503MissingAccessors() {
        Compilation.Result state = Compilation.compile(Fixtures.withDocs(
                Fixtures.DOCS_HEADER.replace("allowsCycle = \"REVIEW\"", "allowsCycle = \"REVIEW\", state = \"phase\""), Fixtures.DOCS_BODY));
        assertTrue(state.hasError("SG503"), state.describe());
        Compilation.Result id = Compilation.compile(Fixtures.withDocs(
                Fixtures.DOCS_HEADER.replace("allowsCycle = \"REVIEW\"", "allowsCycle = \"REVIEW\", id = \"code\""), Fixtures.DOCS_BODY));
        assertTrue(id.hasError("SG503"), id.describe());
    }

    @Test
    void sg506MissingRowAndSg102DuplicateRowAndSg103BlankReason() {
        Compilation.Result missing = Compilation.compile(Fixtures.withDocs(
                Fixtures.DOCS_HEADER.replace("@Row(state = \"PUBLISHED\", otherwise = \"a published doc can only be archived\")\n", ""),
                Fixtures.DOCS_BODY));
        assertTrue(missing.hasError("SG506"), missing.describe());
        assertTrue(missing.errors().get(0).contains("PUBLISHED"), missing.describe());

        Compilation.Result duplicate = Compilation.compile(Fixtures.withDocs(
                Fixtures.DOCS_HEADER + "@Row(state = \"DRAFT\", otherwise = \"again\")\n", Fixtures.DOCS_BODY));
        assertTrue(duplicate.hasError("SG102"), duplicate.describe());

        Compilation.Result blank = Compilation.compile(Fixtures.withDocs(
                Fixtures.DOCS_HEADER.replace("otherwise = \"a draft is sent to review\"", "otherwise = \" \""), Fixtures.DOCS_BODY));
        assertTrue(blank.hasError("SG103"), blank.describe());
        assertEquals(0, blank.countError("SG506"), blank.describe());
    }

    @Test
    void terminalMayCarryARowWithACustomReason() {
        Compilation.Result result = Compilation.compile(Fixtures.withDocs(
                Fixtures.DOCS_HEADER + "@Row(state = \"ARCHIVED\", otherwise = \"archived docs are frozen\")\n", Fixtures.DOCS_BODY));
        assertTrue(result.success(), result.describe());
    }
}
