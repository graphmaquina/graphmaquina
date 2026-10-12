package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A class literal javac cannot resolve in the first round may name a type a later round brings: the machine's own
 * companion, or a type another processor generates. Such a value is legal, and javac refuses it only if no round
 * resolves it.
 *
 * <p>The value of an annotation StateGraph does not own, the application's or another tool's, never affects the
 * machine, which gets its companion and its place in the published index whatever such a value names. A value of
 * StateGraph's own vocabulary is read once a round resolves it: the machine, and a set that holds it, wait for that
 * round. A value no round resolves is javac's alone to refuse, which {@code MachineHeaderErrorsTest} pins, and the
 * machine is still checked declaration by declaration.</p>
 */
class LaterRoundValuesTest {

    private static final String PUBLISHED = "META-INF/graphmaquina/exports.json";

    /** An application annotation that takes a class, which {@link ManifestProcessors.Other} claims. */
    private static final String MARKER = """
            package fx;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            @Target({ElementType.TYPE, ElementType.METHOD})
            public @interface OtherMarker { Class<?> value(); }
            """;

    private static final String PUBLISH = "    Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);";

    @Test
    void anApplicationAnnotationOnTheFacadeMayNameTheCompanion() {
        Map<String, String> sources = marked(TypedFixtures.minimal());
        sources.put("fx.Docs", sources.get("fx.Docs").replace("@GenerateGraphApi\n",
                "@GenerateGraphApi\n@OtherMarker(DocsRuntime.class)\n"));

        Compilation.Prepared prepared = Compilation.prepare(sources);
        Compilation.Result result = compile(prepared, ManifestProcessors.Other.class);

        assertTrue(result.success(), result.describe());
        assertTrue(result.hasGenerated("fx.DocsRuntime"), result.describe());
        assertTrue(Files.isRegularFile(prepared.request().classesOutput().resolve(PUBLISHED)), result.describe());
    }

    @Test
    void anApplicationAnnotationOnAnOperationMayNameTheCompanion() {
        Map<String, String> sources = marked(TypedFixtures.minimal());
        sources.put("fx.Docs", sources.get("fx.Docs").replace(PUBLISH,
                "    @OtherMarker(DocsRuntime.class)\n" + PUBLISH));

        Compilation.Prepared prepared = Compilation.prepare(sources);
        Compilation.Result result = compile(prepared, ManifestProcessors.Other.class);

        assertTrue(result.success(), result.describe());
        assertTrue(result.hasGenerated("fx.DocsRuntime"), result.describe());
        assertTrue(Files.isRegularFile(prepared.request().classesOutput().resolve(PUBLISHED)), result.describe());
    }

    /** {@link AnotherProcessor} generates {@code fx.Extra} in the first round, which javac resolves in the second. */
    @Test
    void anApplicationAnnotationMayNameATypeAnotherProcessorGenerates() {
        Map<String, String> sources = marked(TypedFixtures.minimal());
        sources.put("fx.Docs", sources.get("fx.Docs").replace("@GenerateGraphApi\n",
                "@GenerateGraphApi\n@OtherMarker(Extra.class)\n"));
        AnotherProcessor.generates(Map.of("fx.Extra", "package fx;\npublic final class Extra { }\n"));

        Compilation.Prepared prepared = Compilation.prepare(sources);
        Compilation.Result result = compile(prepared, ManifestProcessors.Other.class, AnotherProcessor.class);

        assertTrue(result.success(), result.describe());
        assertTrue(result.hasGenerated("fx.DocsRuntime"), result.describe());
        assertTrue(Files.isRegularFile(prepared.request().classesOutput().resolve(PUBLISHED)), result.describe());
    }

    /**
     * A class literal of an application annotation that names no type at all is javac's to refuse, and nothing
     * else: the machine is built and generated as if the annotation were not there.
     */
    @Test
    void anApplicationAnnotationNamingNoTypeLeavesTheMachineToItself() {
        Map<String, String> sources = marked(TypedFixtures.minimal());
        sources.put("fx.Docs", sources.get("fx.Docs").replace("@GenerateGraphApi\n",
                "@GenerateGraphApi\n@OtherMarker(Nowhere.class)\n"));

        Compilation.Result result = compile(Compilation.prepare(sources), ManifestProcessors.Other.class);

        assertFalse(result.success(), result.describe());
        assertFalse(result.errors().isEmpty(), result.describe());
        assertTrue(result.errors().stream().allMatch((String error) -> error.contains("cannot find symbol")
                && !error.contains("[SG")), result.describe());
        assertTrue(result.hasGenerated("fx.DocsRuntime"), result.describe());
    }

    /**
     * The exception a transition fails on and the failure kind a recovery names are classes {@link AnotherProcessor}
     * generates in the first round. Docs waits for the second round, which resolves both; so does the set that holds
     * it, while Pages is generated in the first. No companion is written in the last round, which
     * {@code -Xlint:processing} would warn of and {@code -Werror} refuse.
     */
    @Test
    void aMachineWaitsForTheRoundThatBringsATypeItsDeclarationsName() {
        Map<String, String> sources = Fixtures.withLibrary(Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH,
                Fixtures.DOCS_BODY.replace("onFailure = RETURN,", """
                    failsOn = Broken.class,
                    recoveries = @DocsGraph.Recovery(kind = Unavailable.class, transition = RETURN),
                    onFailure = RETURN,"""), Fixtures.LIBRARY);
        AnotherProcessor.generates(Map.of(
                "fx.Unavailable", """
                        package fx;
                        import io.github.graphmaquina.api.FailureKind;
                        public record Unavailable() implements FailureKind { }
                        """,
                "fx.Broken", """
                        package fx;
                        public final class Broken extends RuntimeException {
                            private static final long serialVersionUID = 1L;
                        }
                        """));

        Compilation.Prepared prepared = Compilation.prepare(sources);
        Compilation.Result result = compile(prepared, AnotherProcessor.class);

        assertTrue(result.success(), result.describe());
        assertEquals(List.of(), result.diagnostics(), result.describe());
        assertTrue(result.hasGenerated("fx.DocsRuntime"), result.describe());
        assertTrue(result.hasGenerated("fx.PagesRuntime"), result.describe());
        assertTrue(result.hasGenerated("fx.LibraryRuntime"), result.describe());
        assertTrue(Files.isRegularFile(prepared.request().classesOutput().resolve(PUBLISHED)), result.describe());
    }

    /**
     * A symbol no round resolves leaves the machine to javac's {@code cannot find symbol}, decided once processing
     * is over, but every other problem of its declarations is still reported then, here a state given two
     * policies; the set that holds it waits with it and gets no companion either.
     */
    @Test
    void aMachineNoRoundResolvesIsStillCheckedDeclarationByDeclaration() {
        String header = Fixtures.DOCS_HEADER.replace("id = DocRefs.Id.class", "id = DocRefs.Ids.class")
                + "@DocsGraph.StatePolicy(state = DRAFT, otherwise = \"a draft again\")\n";
        Map<String, String> sources = Fixtures.withLibrary(header + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY,
                Fixtures.LIBRARY);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError("SG102"), result.describe());
        assertEquals(1, result.errors().stream().filter((String error) -> error.contains("[SG")).count(),
                result.describe());
        assertTrue(result.errors().stream().anyMatch((String error) -> error.contains("cannot find symbol")),
                result.describe());
        assertTrue(result.hasGenerated("fx.PagesRuntime"), result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
        assertFalse(result.hasGenerated("fx.LibraryRuntime"), result.describe());
    }

    /** {@code sources} with {@link #MARKER}. */
    private static Map<String, String> marked(Map<String, String> sources) {
        sources.put("fx.OtherMarker", MARKER);
        return sources;
    }

    /** Compiles {@code prepared} with the build's lifecycle, the processor and {@code others} after it. */
    private static Compilation.Result compile(Compilation.Prepared prepared, Class<?>... others) {
        List<String> processors = new ArrayList<>(List.of(StateGraphProcessor.class.getName()));
        for (Class<?> other : others) {
            processors.add(other.getName());
        }
        return Compilation.compilePrepared(prepared, List.of("-processor", String.join(",", processors)));
    }
}
