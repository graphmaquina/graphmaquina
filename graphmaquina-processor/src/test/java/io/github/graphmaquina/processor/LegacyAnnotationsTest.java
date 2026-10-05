package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The retired matrix vocabulary is no longer a way to declare a graph: it is a migration diagnostic.
 *
 * <p>Those annotation types are gone from this release, so a project that has not been migrated only still
 * resolves them from the older release it compiles against. The fixtures supply them the way that classpath
 * would, and every test expects {@code SG609}; the one that mixes the old vocabulary with the new also expects no
 * companion: half a migration is refused as loudly as none. A project whose imports no longer resolve is told by
 * javac itself, and {@code docs/migration-2.0.md} supplies the replacement.</p>
 */
class LegacyAnnotationsTest {

    /**
     * The five retired annotation types the machine fixtures use, as an older release still on the classpath
     * declares them.
     */
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

    /**
     * The retired vocabulary, the state enum, the entity and the host a retired facade binds, with neither markers
     * nor generated references.
     */
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

    /** A complete machine of the retired vocabulary. {@code @StateGraph} is on line 5. */
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
        String reported = result.errors().stream().filter((String error) -> error.contains("[SG609]")).findFirst()
                .orElseThrow(() -> new AssertionError(result.describe()));
        assertTrue(reported.contains("@GenerateGraphApi"), reported);
        assertTrue(reported.contains("DocsGraph.Definition"), reported);
        assertTrue(reported.contains("DocsGraph.Transition"), reported);
        assertTrue(reported.contains("Refs"), reported);
    }

    /**
     * The vocabulary {@code SG609} tells the author to write is the one the generator writes once the facade is
     * marked: named after the facade's simple name, normalized to PascalCase, so {@code docs_api} is told
     * {@code DocsApiGraph}. A name with no PascalCase form, which the generator refuses, is told a placeholder rather
     * than a type nothing generates.
     */
    @Test
    void spellsTheVocabularyWithTheNameTheGeneratorGivesIt() {
        Map<String, String> sources = domain();
        sources.put("fx.docs_api", LEGACY_DOCS.replace("public interface Docs", "public interface docs_api"));
        sources.put("fx.__", """
                package fx;
                import io.github.graphmaquina.annotation.StateGraph;
                @StateGraph(initial = "DRAFT")
                public interface __ { }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(3, result.countError("SG609"), result.describe());
        String facade = reported(result, "fx.docs_api declares its graph");
        for (String annotation : List.of("Definition(initial", "StatePolicy", "Transition", "Reject")) {
            assertTrue(facade.contains("@DocsApiGraph." + annotation), facade);
        }
        assertFalse(facade.contains("top-level"), facade);
        assertTrue(reported(result, "fx.docs_api.publish declares its transition")
                .contains("use @DocsApiGraph.Transition(id"), result.describe());
        assertTrue(reported(result, "fx.__ declares its graph").contains("@<Machine>Graph.Definition(initial"),
                result.describe());
        assertFalse(result.describe().contains("docs_apiGraph"), result.describe());
        assertFalse(result.describe().contains("@__Graph."), result.describe());
    }

    /**
     * A machine's companion lives next to its facade in the package, so a nested facade is refused as
     * {@code SG502} once it is marked. {@code SG609} says so first, at the facade and at each of its operations,
     * and names the vocabulary of the top-level interface it must become, which the generator names after its
     * simple name.
     */
    @Test
    void aNestedLegacyFacadeIsToldToBecomeATopLevelInterface() {
        Map<String, String> sources = domain();
        sources.put("fx.Outer", """
                package fx;
                import io.github.graphmaquina.annotation.Move;
                import io.github.graphmaquina.annotation.StateGraph;
                public final class Outer {
                    @StateGraph(initial = "DRAFT")
                    public interface Docs {
                        @Move(from = "DRAFT", to = "PUBLISHED")
                        void publish(Doc doc);
                    }
                }
                """);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(2, result.countError("SG609"), result.describe());
        String facade = reported(result, "fx.Outer.Docs declares its graph");
        assertTrue(facade.contains("which this version no longer reads: make it a top-level interface of its package,"
                + " since its generated companion lives next to it there, mark it @GenerateGraphApi"), facade);
        assertTrue(facade.contains("@DocsGraph.Definition(initial"), facade);
        String operation = reported(result, "fx.Outer.Docs.publish declares its transition");
        assertTrue(operation.contains("which this version no longer reads: make fx.Outer.Docs a top-level interface"
                + " of its package, since its generated companion lives next to it there, then use"
                + " @DocsGraph.Transition(id"), operation);
        assertFalse(result.describe().contains("OuterDocsGraph"), result.describe());
    }

    /** The SG609 message that contains {@code text}, which a test expects exactly once. */
    private static String reported(Compilation.Result result, String text) {
        List<String> matching = result.errors().stream()
                .filter((String error) -> error.contains("[SG609]") && error.contains(text)).toList();
        assertEquals(1, matching.size(), result.describe());
        return matching.get(0);
    }

    @Test
    void aLegacyOperationIsReportedEvenWhenItsInterfaceCarriesNoStateGraph() {
        Map<String, String> sources = domain();
        sources.put("fx.Docs", LEGACY_DOCS.replace("@StateGraph(initial = \"DRAFT\", terminals = \"PUBLISHED\")\n", ""));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG609"), result.describe());
        assertTrue(result.errors().stream()
                .anyMatch((String error) -> error.contains("[SG609]") && error.contains("publish")),
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
    }
}
