package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import java.lang.annotation.Repeatable;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What the processor reads of the automatic declarations of the typed vocabulary and what it generates for them:
 * the {@code automatic} mark of an edge, written on an operation or on the interface and on every origin of a
 * multi-origin annotation, the {@code AutomaticOrder} of a state and its repeatable container, the continuing
 * failure routes ({@code onFailureContinues} and {@code Recovery.continueChain}) and the {@code maxAutomaticChain}
 * of the generated set. The rules of the whole graph reach the processor through the outline the DSL validates,
 * and the processor adds its own: an automatic attempt has no input, and a continuing {@code onFailure} needs an
 * {@code onFailure}.
 */
class TypedAutomaticTest {

    /** A graph whose review returns to the draft: the draft is the initial state and the review allows the cycle. */
    private static final String HEADER = """
            @DocsGraph.Definition(initial = DRAFT, terminals = PUBLISHED, allowsCycle = REVIEW,
                state = DocRefs.Status.class, id = DocRefs.Id.class)
            @DocsGraph.StatePolicy(state = DRAFT, otherwise = "a draft is sent to review")
            @DocsGraph.StatePolicy(state = REVIEW, otherwise = "a review is published or sent back")
            """;

    /** The same graph without the cycle, for the declarations that put the review last. */
    private static final String STRAIGHT_HEADER = HEADER.replace(" allowsCycle = REVIEW,", "");

    /** The operation that submits a draft and the one that publishes a review, both plain. */
    private static final String OPERATIONS = """
                @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED,
                    action = DocActionsRefs.Publish.class)
                Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
            """;

    /** The domain of the fixtures, with the identifiers and the failure kind these tests add. */
    private static Map<String, String> sources(String header, String members) {
        Map<String, String> sources = TypedFixtures.graph(header, members);
        sources.put("fx.DocTransition", """
                package fx;
                /** Identifiers of the automatic fixtures. */
                public enum DocTransition { SUBMIT, PUBLISH, RETURN, FAST, SLOW }
                """);
        sources.put("fx.Busy", """
                package fx;
                /** A failure kind of the automatic fixtures. */
                public record Busy() implements io.github.graphmaquina.api.FailureKind { }
                """);
        return sources;
    }

    /** An interface that declares {@code edges} itself, and the plain operations of {@link #OPERATIONS}. */
    private static Compilation.Result compile(String header, String edges, String members) {
        return Compilation.compileTyped(sources(header + edges, members));
    }

    @Test
    void automaticOnATypeLevelTransitionGeneratesAutomatic() {
        Compilation.Result result = compile(STRAIGHT_HEADER, """
                @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW, automatic = true)
                """, """
                @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED,
                    action = DocActionsRefs.Publish.class)
                Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
                """);

        assertTrue(result.success(), result.describe());
        assertTrue(result.generated("fx.DocsRuntime").contains(
                ".transition(DocTransition.SUBMIT, DocStatus.DRAFT, DocStatus.REVIEW).automatic().endTransition()"),
                result.generated("fx.DocsRuntime"));
        assertEquals(1, result.generated("fx.DocsRuntime").split("\\.automatic\\(\\)", -1).length - 1);
    }

    @Test
    void automaticOnAnOperationKeepsTheOperationCallable() throws Exception {
        Map<String, String> sources = sources(STRAIGHT_HEADER, """
                @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW, automatic = true)
                Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED,
                    action = DocActionsRefs.Publish.class)
                Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
                """);
        sources.put("fx.Library", """
                package fx;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                @StateGraphs public interface Library extends Machines { Docs docs(); }
                """);
        sources.put("fx.Scenario", """
                package fx;
                import io.github.graphmaquina.api.Outcome;
                public final class Scenario {
                    public static String run() {
                        try (Library library = LibraryRuntime.builder(new DocActions()).build()) {
                            Doc doc = new Doc();
                            Outcome<Doc, DocStatus, DocTransition> outcome = library.docs().submit(doc);
                            return (outcome instanceof Outcome.Applied<?, ?, ?>) + " " + doc.status();
                        }
                    }
                }
                """);
        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.success(), result.describe());
        assertTrue(result.generated("fx.DocsRuntime").contains(
                ".transition(DocTransition.SUBMIT, DocStatus.DRAFT, DocStatus.REVIEW).automatic().endTransition()"),
                result.generated("fx.DocsRuntime"));
        assertEquals("true REVIEW", result.load("fx.Scenario").getMethod("run").invoke(null));
    }

    @Test
    void aMultiFromAnnotationMarksEveryEdge() {
        Compilation.Result result = compile(STRAIGHT_HEADER, """
                @DocsGraph.Transition(id = FAST, from = {DRAFT, REVIEW}, to = PUBLISHED, automatic = true)
                """, "@DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)\n"
                + "Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);\n");

        assertTrue(result.success(), result.describe());
        String generated = result.generated("fx.DocsRuntime");
        assertTrue(generated.contains(
                ".transition(DocTransition.FAST, DocStatus.DRAFT, DocStatus.PUBLISHED).automatic()"), generated);
        assertTrue(generated.contains(
                ".transition(DocTransition.FAST, DocStatus.REVIEW, DocStatus.PUBLISHED).automatic()"), generated);
    }

    /** The order is the one the annotation names, here the reverse of the declaration order, in the built graph. */
    @Test
    void automaticOrderGeneratesTheOrder() throws Exception {
        Map<String, String> sources = sources(HEADER + """
                @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED, automatic = true,
                    guards = DocActionsRefs.Approved.class)
                @DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT, automatic = true,
                    guards = DocActionsRefs.Approved.class)
                @DocsGraph.AutomaticOrder(from = REVIEW, transitions = {RETURN, PUBLISH})
                """, "@DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)\n"
                + "Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);\n");
        sources.put("fx.Scenario", """
                package fx;
                import io.github.graphmaquina.api.TransitionSpec;
                public final class Scenario {
                    public static String run() {
                        return DocsRuntime.graph(new DocActions()).automaticFrom(DocStatus.REVIEW).stream()
                                .map((TransitionSpec<Doc, DocStatus, DocTransition> spec) ->
                                        spec.transition().name()).toList().toString();
                    }
                }
                """);
        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.success(), result.describe());
        assertTrue(result.generated("fx.DocsRuntime").contains(
                ".automaticOrder(DocStatus.REVIEW, List.of(DocTransition.RETURN, DocTransition.PUBLISH))"),
                result.generated("fx.DocsRuntime"));
        assertEquals("[RETURN, PUBLISH]", result.load("fx.Scenario").getMethod("run").invoke(null));
    }

    @Test
    void repeatedAutomaticOrdersUseTheContainer() {
        Compilation.Result result = compile(STRAIGHT_HEADER, """
                @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW, automatic = true,
                    guards = DocActionsRefs.Approved.class)
                @DocsGraph.Transition(id = FAST, from = DRAFT, to = PUBLISHED, automatic = true)
                @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED, automatic = true,
                    guards = DocActionsRefs.Approved.class)
                @DocsGraph.Transition(id = SLOW, from = REVIEW, to = PUBLISHED, automatic = true)
                @DocsGraph.AutomaticOrder(from = DRAFT, transitions = {SUBMIT, FAST})
                @DocsGraph.AutomaticOrder(from = REVIEW, transitions = {PUBLISH, SLOW})
                """, "");

        assertTrue(result.success(), result.describe());
        String generated = result.generated("fx.DocsRuntime");
        assertTrue(generated.contains(
                ".automaticOrder(DocStatus.DRAFT, List.of(DocTransition.SUBMIT, DocTransition.FAST))"),
                generated);
        assertTrue(generated.contains(
                ".automaticOrder(DocStatus.REVIEW, List.of(DocTransition.PUBLISH, DocTransition.SLOW))"),
                generated);
        assertEquals(result.load("fx.DocsGraph$AutomaticOrders"),
                result.load("fx.DocsGraph$AutomaticOrder").getAnnotation(Repeatable.class).value());
    }

    /** A graph whose draft takes a fast automatic edge, so the review may continue the chain into the draft. */
    private static final String CONTINUING = """
            @DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT)
            @DocsGraph.Transition(id = FAST, from = DRAFT, to = PUBLISHED, automatic = true)
            """;

    /** The operation of {@link #CONTINUING} that submits, and the one that publishes with {@code extra}. */
    private static String continuing(String extra) {
        return """
                @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED, automatic = true,
                    action = DocActionsRefs.Publish.class, %s)
                Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
                """.formatted(extra);
    }

    @Test
    void continueChainGeneratesRecoverAndContinue() {
        Compilation.Result result = compile(HEADER, CONTINUING, continuing("""
                recoveries = {
                    @DocsGraph.Recovery(kind = Busy.class, transition = RETURN, continueChain = true)}"""));

        assertTrue(result.success(), result.describe());
        String generated = result.generated("fx.DocsRuntime");
        assertTrue(generated.contains(".recoverAndContinue(fx.Busy.class, DocTransition.RETURN)"), generated);
        assertFalse(generated.contains(".recover("), generated);
    }

    @Test
    void onFailureContinuesGeneratesOnFailureAndContinue() {
        Compilation.Result result = compile(HEADER, CONTINUING,
                continuing("onFailure = RETURN, onFailureContinues = true"));

        assertTrue(result.success(), result.describe());
        String generated = result.generated("fx.DocsRuntime");
        assertTrue(generated.contains(".onFailureAndContinue(DocTransition.RETURN)"), generated);
        assertFalse(generated.contains(".onFailure("), generated);
    }

    @Test
    void sg515AutomaticEdgeOfAnOperationWithInput() {
        Compilation.Result result = compile(STRAIGHT_HEADER, "", """
                @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW, automatic = true)
                Outcome<Doc, DocStatus, DocTransition> submit(Doc doc, String reason);
                @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED,
                    action = DocActionsRefs.Publish.class)
                Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
                """);

        assertEquals(List.of("[" + DiagnosticCode.SG515 + "] the automatic transition DRAFT -SUBMIT-> REVIEW runs the"
                + " operation submit, which takes an input, and an automatic attempt has none"), result.errors(),
                result.describe());
        assertFalse(result.hasGenerated("fx.DocsRuntime"), result.describe());
    }

    @Test
    void sg515TypeLevelBranchOfAnInputOperation() {
        Compilation.Result result = compile(STRAIGHT_HEADER, """
                @DocsGraph.Transition(id = SUBMIT, from = REVIEW, to = PUBLISHED, automatic = true)
                """, """
                @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                Outcome<Doc, DocStatus, DocTransition> submit(Doc doc, String reason);
                """);

        assertEquals(List.of("[" + DiagnosticCode.SG515 + "] the automatic transition REVIEW -SUBMIT-> PUBLISHED runs"
                + " the operation submit, which takes an input, and an automatic attempt has none"), result.errors(),
                result.describe());
    }

    @Test
    void sg305OnFailureContinuesWithoutOnFailure() {
        Compilation.Result result = compile(HEADER, CONTINUING, continuing("onFailureContinues = true"));

        assertEquals(List.of("[" + DiagnosticCode.SG305
                + "] onFailureContinues needs an onFailure on REVIEW -PUBLISH-> PUBLISHED"), result.errors(),
                result.describe());
    }

    @Test
    void sg108AnAutomaticOrderIsRequiredByTheOutlineRules() {
        Compilation.Result result = compile(STRAIGHT_HEADER, """
                @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW, automatic = true)
                @DocsGraph.Transition(id = FAST, from = DRAFT, to = PUBLISHED, automatic = true)
                """, "@DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED)\n"
                + "Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);\n");

        assertEquals(List.of("[" + DiagnosticCode.SG108 + "] state DRAFT has 2 automatic transitions, SUBMIT and FAST,"
                + " and declares no automaticOrder"), result.errors(), result.describe());
    }

    @Test
    void sg109AnUnguardedEdgeBeforeAnotherReachesTheProcessor() {
        Compilation.Result result = compile(STRAIGHT_HEADER, """
                @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW, automatic = true)
                @DocsGraph.Transition(id = FAST, from = DRAFT, to = PUBLISHED, automatic = true)
                @DocsGraph.AutomaticOrder(from = DRAFT, transitions = {SUBMIT, FAST})
                """, "@DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED)\n"
                + "Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);\n");

        assertEquals(List.of("[" + DiagnosticCode.SG109 + "] in the automaticOrder of DRAFT, SUBMIT has no guard and"
                + " comes before FAST, which could never be chosen"), result.errors(), result.describe());
    }

    @Test
    void sg206AutomaticCycleReachesTheProcessor() {
        Compilation.Result result = compile(HEADER, """
                @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW, automatic = true)
                @DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT, automatic = true)
                """, "@DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED)\n"
                + "Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);\n");

        assertEquals(1, result.errors().size(), result.describe());
        assertTrue(result.errors().get(0).startsWith("[" + DiagnosticCode.SG206
                + "] the automatic transitions form a cycle: "), result.describe());
    }

    @Test
    void sg305ContinuingIntoAStateWithoutAutomaticEdgesReachesTheProcessor() {
        Compilation.Result result = compile(HEADER, "@DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT)\n",
                continuing("onFailure = RETURN, onFailureContinues = true"));

        assertEquals(List.of("[" + DiagnosticCode.SG305 + "] the failure route RETURN of REVIEW -PUBLISH-> PUBLISHED"
                + " continues the chain, but DRAFT has no automatic transition"), result.errors(), result.describe());
    }

    @Test
    void sg807AnAutomaticEdgeOfABoundStateReachesTheProcessor() {
        Compilation.Result result = Compilation.compileTyped(TypedSubmachineTest.bound(TypedSubmachineTest.BOUND
                + "@DocsGraph.Transition(id = SUBMIT, from = REVIEW, to = PUBLISHED, automatic = true)\n"));

        assertEquals(List.of("[" + DiagnosticCode.SG807
                + "] the automatic transition SUBMIT leaves REVIEW, which holds a sub-machine"), result.errors(),
                result.describe());
    }

    /**
     * The generated set forwards {@code maxAutomaticChain} to its registry: a ladder of two attempts after the
     * submitted call stops after the first under a bound of 1 and runs both under the default, and a value below 1
     * is refused where it is given.
     */
    @Test
    void theGeneratedSetForwardsMaxAutomaticChain() throws Exception {
        Compilation.Result result = Compilation.compileTyped(ladder());

        assertTrue(result.success(), result.describe());
        assertTrue(result.generated("fx.LibraryRuntime").contains(".maxAutomaticChain("),
                result.generated("fx.LibraryRuntime"));
        Class<?> scenario = result.load("fx.Scenario");
        assertEquals("PUBLISHED", scenario.getMethod("run", int.class).invoke(null, 1));
        assertEquals("ARCHIVED", scenario.getMethod("run").invoke(null));
        assertEquals("maxAutomaticChain must be greater than or equal to 1", scenario.getMethod("refuse").invoke(null));
    }

    /** A set over a machine whose submitted call is followed by two automatic attempts, and the scenario that runs it. */
    private static Map<String, String> ladder() {
        Map<String, String> sources = sources(STRAIGHT_HEADER.replace("terminals = PUBLISHED", "terminals = ARCHIVED")
                + """
                @DocsGraph.StatePolicy(state = PUBLISHED, otherwise = "a published document is archived")
                @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED, automatic = true)
                @DocsGraph.Transition(id = SLOW, from = PUBLISHED, to = ARCHIVED, automatic = true)
                """, "@DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)\n"
                + "Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);\n");
        sources.put("fx.DocStatus", """
                package fx;
                /** States of the ladder. */
                public enum DocStatus { DRAFT, REVIEW, PUBLISHED, ARCHIVED }
                """);
        sources.put("fx.Library", """
                package fx;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                @StateGraphs public interface Library extends Machines { Docs docs(); }
                """);
        sources.put("fx.Scenario", """
                package fx;
                public final class Scenario {
                    public static String run(int chain) {
                        try (Library library = LibraryRuntime.builder().maxAutomaticChain(chain).build()) {
                            Doc doc = new Doc();
                            library.docs().submit(doc);
                            return doc.status().name();
                        }
                    }
                    public static String run() {
                        try (Library library = LibraryRuntime.builder().build()) {
                            Doc doc = new Doc();
                            library.docs().submit(doc);
                            return doc.status().name();
                        }
                    }
                    public static String refuse() {
                        try {
                            LibraryRuntime.builder().maxAutomaticChain(0);
                            return "accepted";
                        } catch (IllegalArgumentException refused) {
                            return refused.getMessage();
                        }
                    }
                }
                """);
        return sources;
    }
}
