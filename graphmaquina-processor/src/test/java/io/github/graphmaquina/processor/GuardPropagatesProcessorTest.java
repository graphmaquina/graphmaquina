package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * {@code @GraphGuard(propagates = ...)} reaches the generated graph: the companion declares the guard with the types
 * it propagates, in their order, a guard of a jar keeps them, since javac reads the marker from its class file, and a
 * type that {@code failsOn} may not list either is refused as {@code SG302} where the edge names the guard.
 */
class GuardPropagatesProcessorTest {

    private static final String UNAVAILABLE = """
            package fx;
            public final class KycUnavailable extends RuntimeException {
                public KycUnavailable(String message) { super(message); }
            }
            """;

    private static final String HEADER = """
            @DocsGraph.Definition(initial = DRAFT, terminals = PUBLISHED, allowsCycle = REVIEW,
                state = DocRefs.Status.class, id = DocRefs.Id.class)
            @DocsGraph.StatePolicy(state = DRAFT, otherwise = "review first")
            @DocsGraph.StatePolicy(state = REVIEW, otherwise = "publish or return")
            @DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT)
            """;

    private static final String MEMBERS = """
                @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW, guards = KycRefs.KycReady.class)
                Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED)
                Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
            """;

    private static final String LIBRARY = """
            package fx;
            import io.github.graphmaquina.annotation.StateGraphs;
            import io.github.graphmaquina.api.Machines;
            @StateGraphs public interface Library extends Machines { Docs docs(); }
            """;

    /** The guard host, whose marker carries {@code propagates}, such as {@code (propagates = X.class)}, or nothing. */
    private static String kyc(String propagates) {
        return """
            package fx;
            import io.github.graphmaquina.annotation.GraphGuard;
            import io.github.graphmaquina.api.GuardContext;
            import io.github.graphmaquina.api.GuardResult;
            public final class Kyc {
                private Kyc() { }
                @GraphGuard%s
                public static GuardResult kycReady(GuardContext<Doc, DocStatus, DocTransition> context) {
                    throw new KycUnavailable("kyc unavailable");
                }
            }
            """.formatted(propagates);
    }

    private static Map<String, String> sources(String propagates) {
        Map<String, String> sources = TypedFixtures.graph(HEADER, MEMBERS);
        sources.put("fx.KycUnavailable", UNAVAILABLE);
        sources.put("fx.Kyc", kyc(propagates));
        sources.put("fx.Library", LIBRARY);
        return sources;
    }

    /** The {@code .guard("kycReady", ...)} declaration the companion of {@code result} writes. */
    private static String declaration(Compilation.Result result) {
        return result.generated("fx.DocsRuntime").lines()
                .filter((String line) -> line.contains(".guard(\"kycReady\"")).findFirst().orElseThrow();
    }

    @Test
    void theCompanionDeclaresTheGuardWithItsTypes() {
        Compilation.Result result = Compilation.compileTyped(
                sources("(propagates = {KycUnavailable.class, java.io.UncheckedIOException.class})"));

        assertTrue(result.success(), result.describe());
        String declaration = declaration(result);
        assertTrue(declaration.contains("KycUnavailable.class, java.io.UncheckedIOException.class"), declaration);
    }

    @Test
    void aGuardThatPropagatesNothingDeclaresNoTypes() {
        Compilation.Result result = Compilation.compileTyped(sources(""));

        assertTrue(result.success(), result.describe());
        String declaration = declaration(result);
        assertFalse(declaration.contains("KycUnavailable"), declaration);
    }

    @Test
    void aGuardOfAJarKeepsItsTypes() throws Exception {
        Map<String, String> producer = TypedFixtures.domain();
        producer.put("fx.KycUnavailable", UNAVAILABLE);
        producer.put("fx.Kyc", kyc("(propagates = KycUnavailable.class)"));
        Path jar = PublishedJars.publish(producer, Compilation.temporaryDirectory().resolve("kyc.jar"),
                UnaryOperator.identity());
        Map<String, String> consumer = new LinkedHashMap<>();
        consumer.put("fx.Docs", TypedFixtures.docs(HEADER, MEMBERS));
        consumer.put("fx.Library", LIBRARY);

        Compilation.Result result = Compilation.compileTyped(consumer, List.of(jar));

        assertTrue(result.success(), result.describe());
        assertTrue(declaration(result).contains("KycUnavailable.class"), declaration(result));
    }

    @Test
    void aForbiddenTypeIsSg302() {
        Compilation.Result result = Compilation.compileTyped(sources("(propagates = RuntimeException.class)"));

        assertFalse(result.success(), result.describe());
        assertEquals(1, result.countError(DiagnosticCode.SG302.name()), result.describe());
        assertTrue(result.errors().stream().anyMatch((String error) ->
                error.contains("the guard kycReady propagates java.lang.RuntimeException")), result.describe());
    }
}
