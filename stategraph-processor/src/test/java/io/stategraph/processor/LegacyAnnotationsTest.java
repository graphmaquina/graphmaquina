package io.stategraph.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The v0.2 string vocabulary is no longer a way to declare a graph: it is a migration diagnostic.
 *
 * <p>The annotations still exist in core, deprecated, only so that a project that has not been migrated is told
 * exactly what to write instead of being met with an unclaimed annotation and a machine nobody generated a
 * companion for. Every fixture here therefore expects {@code SG609} at the declaration that carries the old
 * vocabulary, and no companion at all: half a migration is refused as loudly as none.</p>
 */
class LegacyAnnotationsTest {

    /** The entity and the host a legacy facade binds, with neither markers nor generated references. */
    private static Map<String, String> domain() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocStatus", """
                package fx;
                public enum DocStatus { DRAFT, PUBLISHED }
                """);
        sources.put("fx.Doc", """
                package fx;
                public final class Doc {
                    private DocStatus status = DocStatus.DRAFT;
                    public String id() { return "doc"; }
                    public DocStatus status() { return status; }
                    public void status(DocStatus value) { status = value; }
                }
                """);
        sources.put("fx.DocActions", """
                package fx;
                import io.stategraph.api.ActionOutcome;
                import io.stategraph.api.TransitionContext;
                public final class DocActions {
                    public ActionOutcome publish(TransitionContext<Doc, DocStatus> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        return sources;
    }

    /** A complete v0.2 machine, valid under the frontend 0.3 removed. {@code @StateGraph} is on line 7. */
    private static final String LEGACY_DOCS = """
            package fx;
            import io.stategraph.annotation.Action;
            import io.stategraph.annotation.Row;
            import io.stategraph.annotation.StateGraph;
            import io.stategraph.api.Machine;
            import io.stategraph.api.Outcome;
            @StateGraph(initial = "DRAFT", terminals = "PUBLISHED")
            @Row(state = "DRAFT", otherwise = "publish before finishing")
            @Row(state = "PUBLISHED", otherwise = "publication is final")
            public interface Docs extends Machine<Doc, DocStatus> {
                @Action(from = "DRAFT", to = "PUBLISHED", by = DocActions.class, action = "publish")
                Outcome<Doc, DocStatus> publish(Doc doc);
            }
            """;

    @Test
    void aLegacyMachineIsReportedAtItsStateGraphWithTheTypedDeclarationToWrite() {
        Map<String, String> sources = domain();
        sources.put("fx.Docs", LEGACY_DOCS);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG609"), result.describe());
        assertEquals(7, result.lineOf("SG609"), result.describe());
        String reported = result.errors().stream().filter(error -> error.contains("[SG609]")).findFirst()
                .orElseThrow(() -> new AssertionError(result.describe()));
        assertTrue(reported.contains("@GenerateGraphApi"), reported);
        assertTrue(reported.contains("DocsGraph.Definition"), reported);
        assertTrue(reported.contains("Refs"), reported);
        assertFalse(result.hasGenerated("fx.Docs_"), result.describe());
    }

    @Test
    void aLegacyOperationIsReportedEvenWhenItsInterfaceCarriesNoStateGraph() {
        Map<String, String> sources = domain();
        sources.put("fx.Docs", LEGACY_DOCS.replace("@StateGraph(initial = \"DRAFT\", terminals = \"PUBLISHED\")\n", ""));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG609"), result.describe());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("[SG609]") && error.contains("publish")),
                result::describe);
    }

    @Test
    void oneLegacyDeclarationOnATypedMachineIsRefusedToo() {
        Map<String, String> sources = new LinkedHashMap<>(TypedFixtures.minimal());
        sources.put("fx.Docs", sources.get("fx.Docs")
                .replace("import io.stategraph.annotation.GenerateGraphApi;",
                        "import io.stategraph.annotation.GenerateGraphApi;\nimport io.stategraph.annotation.Row;")
                .replace("@GenerateGraphApi\n",
                        "@GenerateGraphApi\n@Row(state = \"DRAFT\", otherwise = \"a leftover of the v0.2 header\")\n"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG609"), result.describe());
        assertFalse(result.hasGenerated("fx.Docs_"), result.describe());
    }

    @Test
    void everyDeclarationCarryingTheOldVocabularyIsReportedOnce() {
        Map<String, String> sources = domain();
        sources.put("fx.Docs", LEGACY_DOCS);
        sources.put("fx.Pages", """
                package fx;
                import io.stategraph.annotation.Move;
                import io.stategraph.annotation.Row;
                import io.stategraph.annotation.StateGraph;
                import io.stategraph.api.Machine;
                import io.stategraph.api.Outcome;
                @StateGraph(initial = "OPEN", terminals = "DONE")
                @Row(state = "OPEN", otherwise = "a page is finished")
                public interface Pages extends Machine<Page, PageStatus> {
                    @Move(from = "OPEN", to = "DONE")
                    Outcome<Page, PageStatus> finish(Page page);
                }
                """);
        sources.put("fx.PageStatus", """
                package fx;
                public enum PageStatus { OPEN, DONE }
                """);
        sources.put("fx.Page", """
                package fx;
                public final class Page {
                    private PageStatus status = PageStatus.OPEN;
                    public String id() { return "page"; }
                    public PageStatus status() { return status; }
                    public void status(PageStatus value) { status = value; }
                }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertFalse(result.success(), result.describe());
        assertEquals(4, result.countError("SG609"), result.describe());
        assertFalse(result.hasGenerated("fx.Pages_"), result.describe());
    }
}
