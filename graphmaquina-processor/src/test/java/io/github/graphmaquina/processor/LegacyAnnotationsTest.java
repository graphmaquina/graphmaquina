package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The retired matrix vocabulary is no longer a way to declare a graph: it is a migration diagnostic.
 *
 * <p>Those annotation types are gone from this release, so a project that has not been migrated only still
 * resolves them from the older release it compiles against. The fixtures supply them the way that classpath
 * would, and every one of them expects {@code SG609} at the declaration that carries it, and no companion at
 * all: half a migration is refused as loudly as none. A project whose imports no longer resolve is told by
 * javac itself, and {@code docs/migration-2.0.md} supplies the replacement.</p>
 */
class LegacyAnnotationsTest {

    /** The retired annotation types as an older release still on the classpath declares them. */
    private static Map<String, String> retiredVocabulary() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("io.github.graphmaquina.annotation.StateGraph", """
                package io.github.graphmaquina.annotation;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Target;
                @Target(ElementType.TYPE)
                public @interface StateGraph { String initial(); String[] terminals() default {}; }
                """);
        sources.put("io.github.graphmaquina.annotation.Row", """
                package io.github.graphmaquina.annotation;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Repeatable;
                import java.lang.annotation.Target;
                @Target(ElementType.TYPE)
                @Repeatable(Rows.class)
                public @interface Row { String state(); String otherwise(); }
                """);
        sources.put("io.github.graphmaquina.annotation.Rows", """
                package io.github.graphmaquina.annotation;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Target;
                @Target(ElementType.TYPE)
                public @interface Rows { Row[] value(); }
                """);
        sources.put("io.github.graphmaquina.annotation.Action", """
                package io.github.graphmaquina.annotation;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Target;
                @Target(ElementType.METHOD)
                public @interface Action { String from(); String to(); Class<?> by(); String action(); }
                """);
        sources.put("io.github.graphmaquina.annotation.Move", """
                package io.github.graphmaquina.annotation;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Target;
                @Target(ElementType.METHOD)
                public @interface Move { String from(); String to(); }
                """);
        return sources;
    }

    /** The entity and the host a retired facade binds, with neither markers nor generated references. */
    private static Map<String, String> domain() {
        Map<String, String> sources = retiredVocabulary();
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
                public final class DocActions {
                    public void publish(Doc doc) { }
                }
                """);
        return sources;
    }

    /** A complete machine of the retired vocabulary. {@code @StateGraph} is on line 6. */
    private static final String LEGACY_DOCS = """
            package fx;
            import io.github.graphmaquina.annotation.Action;
            import io.github.graphmaquina.annotation.Row;
            import io.github.graphmaquina.annotation.StateGraph;
            @StateGraph(initial = "DRAFT", terminals = "PUBLISHED")
            @Row(state = "DRAFT", otherwise = "publish before finishing")
            @Row(state = "PUBLISHED", otherwise = "publication is final")
            public interface Docs {
                @Action(from = "DRAFT", to = "PUBLISHED", by = DocActions.class, action = "publish")
                void publish(Doc doc);
            }
            """;

    @Test
    void aLegacyMachineIsReportedAtItsStateGraphWithTheTypedDeclarationToWrite() {
        Map<String, String> sources = domain();
        sources.put("fx.Docs", LEGACY_DOCS);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG609"), result.describe());
        assertEquals(5, result.lineOf("SG609"), result.describe());
        String reported = result.errors().stream().filter(error -> error.contains("[SG609]")).findFirst()
                .orElseThrow(() -> new AssertionError(result.describe()));
        assertTrue(reported.contains("@GenerateGraphApi"), reported);
        assertTrue(reported.contains("DocsGraph.Definition"), reported);
        assertTrue(reported.contains("DocsGraph.Transition"), reported);
        assertTrue(reported.contains("Refs"), reported);
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
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
    void oneLegacyDeclarationOnAMigratedMachineIsRefusedToo() {
        Map<String, String> sources = new LinkedHashMap<>(TypedFixtures.minimal());
        sources.putAll(retiredVocabulary());
        sources.put("fx.Docs", sources.get("fx.Docs")
                .replace("import io.github.graphmaquina.annotation.GenerateGraphApi;",
                        "import io.github.graphmaquina.annotation.GenerateGraphApi;\nimport io.github.graphmaquina.annotation.Row;")
                .replace("@GenerateGraphApi\n",
                        "@GenerateGraphApi\n@Row(state = \"DRAFT\", otherwise = \"a leftover of the matrix header\")\n"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG609"), result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
    }

    /**
     * Every retired name the processor detects on a type. The names are written as strings now that the types
     * are gone, so each one is exercised: a typo in one of them would otherwise be silent.
     */
    @ParameterizedTest
    @ValueSource(strings = {"StateGraph", "Row", "Rows", "Cell", "Cells", "Forbid", "Forbids", "Watch", "Watches"})
    void reportsEveryRetiredAnnotationOfAType(String retired) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("io.github.graphmaquina.annotation." + retired, marker(retired, "TYPE"));
        sources.put("fx.Docs", """
                package fx;
                @io.github.graphmaquina.annotation.%s
                public interface Docs { }
                """.formatted(retired));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.countError("SG609"), () -> retired + "\n" + result.describe());
    }

    /** The same, for the two retired names an operation carried. */
    @ParameterizedTest
    @ValueSource(strings = {"Action", "Move"})
    void reportsEveryRetiredAnnotationOfAnOperation(String retired) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("io.github.graphmaquina.annotation." + retired, marker(retired, "METHOD"));
        sources.put("fx.Docs", """
                package fx;
                public interface Docs {
                    @io.github.graphmaquina.annotation.%s
                    void publish();
                }
                """.formatted(retired));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.countError("SG609"), () -> retired + "\n" + result.describe());
    }

    /** One retired annotation type, as an older release still on the classpath declares it. */
    private static String marker(String name, String target) {
        return """
                package io.github.graphmaquina.annotation;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Target;
                @Target(ElementType.%s)
                public @interface %s { }
                """.formatted(target, name);
    }

    @Test
    void everyDeclarationCarryingTheOldVocabularyIsReportedOnce() {
        Map<String, String> sources = domain();
        sources.put("fx.Docs", LEGACY_DOCS);
        sources.put("fx.Pages", """
                package fx;
                import io.github.graphmaquina.annotation.Move;
                import io.github.graphmaquina.annotation.Row;
                import io.github.graphmaquina.annotation.StateGraph;
                @StateGraph(initial = "OPEN", terminals = "DONE")
                @Row(state = "OPEN", otherwise = "a page is finished")
                public interface Pages {
                    @Move(from = "OPEN", to = "DONE")
                    void finish(Page page);
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
        assertFalse(result.hasGenerated("fx.PagesRuntime"), result.describe());
    }
}
