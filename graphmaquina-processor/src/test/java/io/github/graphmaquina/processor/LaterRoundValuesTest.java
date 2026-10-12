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
 * A class literal javac cannot resolve in the first round, or a constant that gives a text or a number, may name a
 * type a later round brings: the machine's own companion, or a type another processor generates. Such a value is
 * legal, and javac refuses it only if no round resolves it.
 *
 * <p>The value of an annotation StateGraph does not own, the application's or another tool's, never affects the
 * machine, which gets its companion and its place in the published index whatever such a value names. A value of
 * StateGraph's own vocabulary is read once a round resolves it: the machine, and a set that holds it, wait for that
 * round. A value no round resolves is javac's alone to refuse, which {@code MachineHeaderErrorsTest} pins, and the
 * machine is still checked declaration by declaration. A text that reads like javac's placeholder for such a value
 * is still a text. javac itself refuses, before any processor runs, such a constant in an annotation repeated
 * without its container, and a state or an identifier that is not an enum constant.</p>
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

    /** The policies of {@link Fixtures#DOCS_HEADER}, repeated without their container. */
    private static final String POLICIES = """
            @DocsGraph.StatePolicy(state = DRAFT, otherwise = "a draft is sent to review")
            @DocsGraph.StatePolicy(state = REVIEW, otherwise = "a review is published or sent back")
            @DocsGraph.StatePolicy(state = PUBLISHED, otherwise = "a published doc can only be archived")
            """;

    /** The same policies in their container, the text of DRAFT's policy a constant of {@code Texts}. */
    private static final String CONTAINED_POLICIES = """
            @DocsGraph.StatePolicies({
                    @DocsGraph.StatePolicy(state = DRAFT, otherwise = Texts.DRAFT),
                    @DocsGraph.StatePolicy(state = REVIEW, otherwise = "a review is published or sent back"),
                    @DocsGraph.StatePolicy(state = PUBLISHED, otherwise = "a published doc can only be archived")})
            """;

    /** The texts {@link AnotherProcessor} generates for a header that names them. */
    private static final String TEXTS = """
            package fx;
            public final class Texts {
                public static final String DRAFT = "a draft goes " + "to review first";
                public static final String NEVER_ARCHIVED = "a draft is never archived";
                private Texts() { }
            }
            """;

    /** A rejection whose reason is a constant of {@code Texts}. */
    private static final String NEVER_ARCHIVED =
            "@DocsGraph.Reject(from = DRAFT, transition = ARCHIVE, reason = Texts.NEVER_ARCHIVED)\n";

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

    /**
     * The text of a policy, in the container of the policies, and the reason of a rejection are constants of a class
     * {@link AnotherProcessor} generates in the first round. Docs waits for the second round, which resolves both,
     * and its companion carries their text; so does the set that holds it, while Pages is generated in the first.
     */
    @Test
    void aMachineWaitsForTheRoundThatBringsTheConstantOfAText() {
        String header = Fixtures.DOCS_HEADER.replace(POLICIES, CONTAINED_POLICIES) + NEVER_ARCHIVED;
        Map<String, String> sources = Fixtures.withLibrary(header + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY,
                Fixtures.LIBRARY);
        AnotherProcessor.generates(Map.of("fx.Texts", TEXTS));

        Compilation.Prepared prepared = Compilation.prepare(sources);
        Compilation.Result result = compile(prepared, AnotherProcessor.class);

        assertTrue(result.success(), result.describe());
        assertEquals(List.of(), result.diagnostics(), result.describe());
        String companion = result.generated("fx.DocsRuntime");
        assertTrue(companion.contains("\"a draft goes to review first\""), companion);
        assertTrue(companion.contains("\"a draft is never archived\""), companion);
        assertFalse(companion.contains("<error>"), companion);
        assertTrue(result.hasGenerated("fx.PagesRuntime"), result.describe());
        assertTrue(result.hasGenerated("fx.LibraryRuntime"), result.describe());
        assertTrue(Files.isRegularFile(prepared.request().classesOutput().resolve(PUBLISHED)), result.describe());
    }

    /**
     * javac itself refuses a constant it cannot resolve yet in an annotation repeated without its container, in
     * the first round and before any processor runs, even when a later round would bring it.
     */
    @Test
    void javacRefusesAnUnresolvedConstantInAnAnnotationRepeatedWithoutItsContainer() {
        String header = Fixtures.DOCS_HEADER.replace("\"a draft is sent to review\"", "Texts.DRAFT");
        Map<String, String> sources = Fixtures.withLibrary(header + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY,
                Fixtures.LIBRARY);
        AnotherProcessor.generates(Map.of("fx.Texts", TEXTS));

        Compilation.Result result = compile(Compilation.prepare(sources), AnotherProcessor.class);

        assertFalse(result.success(), result.describe());
        assertTrue(result.errors().stream().anyMatch((String error) -> error.contains("expression not allowed")),
                result.describe());
        assertTrue(result.errors().stream().noneMatch((String error) -> error.contains("[SG")), result.describe());
        assertFalse(result.hasGenerated("fx.PagesRuntime"), result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
    }

    /**
     * A state or an identifier is an enum constant of the machine's own enum, never a constant a later round
     * brings: javac refuses any other value before any processor runs.
     */
    @Test
    void javacRefusesAStateThatIsNotAnEnumConstant() {
        Map<String, String> sources = Fixtures.withLibrary(Fixtures.DOCS_HEADER
                + "@DocsGraph.Reject(from = States.FIRST, transition = ARCHIVE, reason = \"never\")\n"
                + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY, Fixtures.LIBRARY);
        AnotherProcessor.generates(Map.of("fx.States", """
                package fx;
                public final class States {
                    public static final DocStatus FIRST = DocStatus.DRAFT;
                    private States() { }
                }
                """));

        Compilation.Result result = compile(Compilation.prepare(sources), AnotherProcessor.class);

        assertFalse(result.success(), result.describe());
        assertTrue(result.errors().stream().anyMatch((String error) -> error.contains("must be an enum constant")),
                result.describe());
        assertTrue(result.errors().stream().noneMatch((String error) -> error.contains("[SG")), result.describe());
        assertFalse(result.hasGenerated("fx.PagesRuntime"), result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
    }

    /** The seconds and the nanos of a timeout are constants of a class another processor generates. */
    @Test
    void aMachineWaitsForTheRoundThatBringsTheConstantOfATimeout() {
        Map<String, String> sources = Fixtures.withLibrary(Fixtures.DOCS_HEADER + Fixtures.DOCS_WATCH,
                Fixtures.DOCS_BODY.replace("@Timeout(seconds = 2)",
                        "@Timeout(seconds = Limits.SECONDS, nanos = Limits.NANOS)"), Fixtures.LIBRARY);
        AnotherProcessor.generates(Map.of("fx.Limits", """
                package fx;
                public final class Limits {
                    public static final long SECONDS = 3;
                    public static final int NANOS = 500_000_000;
                    private Limits() { }
                }
                """));

        Compilation.Prepared prepared = Compilation.prepare(sources);
        Compilation.Result result = compile(prepared, AnotherProcessor.class);

        assertTrue(result.success(), result.describe());
        assertEquals(List.of(), result.diagnostics(), result.describe());
        assertTrue(result.generated("fx.DocsRuntime").contains("\"PT3.5S\""), result.generated("fx.DocsRuntime"));
        assertTrue(result.hasGenerated("fx.LibraryRuntime"), result.describe());
        assertTrue(Files.isRegularFile(prepared.request().classesOutput().resolve(PUBLISHED)), result.describe());
    }

    /**
     * A text that reads like javac's placeholder for a value it could not resolve is still a text: the machine is
     * generated with that very text.
     */
    @Test
    void aTextThatReadsLikeAnUnresolvedValueIsStillAText() {
        String header = Fixtures.DOCS_HEADER.replace("\"a draft is sent to review\"", "\"<error>\"");
        Map<String, String> sources = Fixtures.withLibrary(header + Fixtures.DOCS_WATCH, Fixtures.DOCS_BODY,
                Fixtures.LIBRARY);

        Compilation.Prepared prepared = Compilation.prepare(sources);
        Compilation.Result result = compile(prepared);

        assertTrue(result.success(), result.describe());
        assertEquals(List.of(), result.diagnostics(), result.describe());
        assertTrue(result.generated("fx.DocsRuntime").contains("\"<error>\""), result.generated("fx.DocsRuntime"));
        assertTrue(result.hasGenerated("fx.LibraryRuntime"), result.describe());
        assertTrue(Files.isRegularFile(prepared.request().classesOutput().resolve(PUBLISHED)), result.describe());
    }

    /**
     * A constant no round resolves is javac's to refuse, as {@code cannot find symbol} once processing is over: the
     * machine, and the set that holds it, get no companion, rather than one that carries javac's placeholder for the
     * text.
     */
    @Test
    void aTextNoRoundResolvesLeavesTheMachineWithoutACompanion() {
        Map<String, String> sources = Fixtures.withLibrary(Fixtures.DOCS_HEADER + NEVER_ARCHIVED + Fixtures.DOCS_WATCH,
                Fixtures.DOCS_BODY, Fixtures.LIBRARY);

        Compilation.Prepared prepared = Compilation.prepare(sources);
        Compilation.Result result = Compilation.compilePrepared(prepared);

        assertFalse(result.success(), result.describe());
        assertFalse(result.errors().isEmpty(), result.describe());
        assertTrue(result.errors().stream().allMatch((String error) -> error.contains("cannot find symbol")
                && !error.contains("[SG")), result.describe());
        assertTrue(result.hasGenerated("fx.PagesRuntime"), result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
        assertFalse(result.hasGenerated("fx.LibraryRuntime"), result.describe());
        assertFalse(Files.exists(prepared.request().classesOutput().resolve(PUBLISHED)), result.describe());
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
