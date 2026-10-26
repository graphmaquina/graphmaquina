package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The checks a facade declares with its generated {@code Check}: what the processor refuses in them. A check is no
 * operation. It names the identifier of one of the facade's operations ({@code SG512}), repeats that operation's
 * parameters under the result {@code Eligibility<E, S, T>} ({@code SG513}), and is the only check of that operation,
 * on a method of its own ({@code SG514}). Like any vocabulary, it is read on an abstract method of its own facade
 * only.
 *
 * <p>Every case is an error. Each fixture is otherwise valid, so the error a test expects is the only one the
 * compilation reports; {@link TypedCheckGenerationTest} compiles the checks that are valid. The result is named in
 * full, so no fixture has to import it.</p>
 */
class TypedCheckDiagnosticsTest {

    /** The result of a check over the machine of {@link TypedFixtures#minimal()}. */
    private static final String ELIGIBILITY = "io.github.graphmaquina.api.Eligibility<Doc, DocStatus, DocTransition>";

    /** The operation of {@link TypedFixtures#minimal()}, as its facade spells it. */
    private static final String PUBLISH = "    Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);\n";

    /** What {@code SG512} says after the method and the identifier it checks. */
    private static final String NO_OPERATION = ", which no operation of Docs executes: a Check names the identifier"
            + " of one of the facade's operations";

    @Test
    void checkOfARecoveryRouteIsSG512() {
        Map<String, String> sources = minimal("Doc doc", check("FAIL", "canFail(Doc doc)"));
        sources.put("fx.DocStatus", "package fx;\npublic enum DocStatus { DRAFT, PUBLISHED, FAILED }\n");
        sources.put("fx.Unavailable", "package fx;\n"
                + "public record Unavailable() implements io.github.graphmaquina.api.FailureKind { }\n");
        sources.put("fx.Docs", sources.get("fx.Docs")
                .replace("terminals = PUBLISHED", "terminals = {PUBLISHED, FAILED}")
                .replace("public interface Docs",
                        "@DocsGraph.Transition(id = FAIL, from = DRAFT, to = FAILED)\npublic interface Docs")
                .replace("action = DocActionsRefs.Publish.class)", "action = DocActionsRefs.Publish.class,"
                        + " recoveries = @DocsGraph.Recovery(kind = Unavailable.class, transition = FAIL))"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.hasError("SG512"), result.describe());
        assertEquals(List.of("[SG512] canFail checks FAIL" + NO_OPERATION), result.errors(), result.describe());
    }

    @Test
    void checkOfAnExitIsSG512() {
        Map<String, String> sources = TypedSubmachineTest.bound(TypedSubmachineTest.BOUND);
        sources.put("fx.Docs", sources.get("fx.Docs")
                .replace(PUBLISH, PUBLISH + check("COMPLETE", "canComplete(Doc doc)")));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.hasError("SG512"), result.describe());
        assertEquals(List.of("[SG512] canComplete checks COMPLETE" + NO_OPERATION), result.errors(),
                result.describe());
    }

    @Test
    void checkOfAnUndeclaredIdentifierIsSG512() {
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc",
                check("ARCHIVE", "canArchive(Doc doc)")));

        assertTrue(result.hasError("SG512"), result.describe());
        assertEquals(List.of("[SG512] canArchive checks ARCHIVE" + NO_OPERATION), result.errors(), result.describe());
    }

    /** The operation is refused for its signature, and only that refusal is reported: its check adds no SG512. */
    @Test
    void checkOfAnOperationRefusedForItsSignatureReportsThatRefusalAlone() {
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc, String first, String second",
                check("PUBLISH", "canPublish(Doc doc)")));

        assertEquals(List.of("[SG504] an operation is declared as Outcome<Doc, DocStatus, DocTransition> publish(Doc)"
                + " or publish(Doc, I), without varargs or method type parameters on input operations"),
                result.errors(), result.describe());
    }

    /** The operation is refused for an error of its edge, and only that error is reported: its check adds no SG512. */
    @Test
    void checkOfAnOperationRefusedForItsEdgeReportsThatErrorAlone() {
        Map<String, String> sources = minimal("Doc doc", check("PUBLISH", "canPublish(Doc doc)"));
        sources.put("fx.Docs", sources.get("fx.Docs").replace("action = DocActionsRefs.Publish.class)",
                "action = DocActionsRefs.Publish.class, failsOn = Exception.class)"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.hasError("SG302"), result.describe());
        assertEquals(1, result.errors().size(), result.describe());
    }

    @Test
    void checkWithoutTheOperationsInputIsSG513() {
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc, String reason",
                check("PUBLISH", "canPublish(Doc doc)")));

        assertTrue(result.hasError("SG513"), result.describe());
        assertEquals(List.of("[SG513] canPublish must be declared as Eligibility<Doc, DocStatus, DocTransition>"
                + " canPublish(Doc, java.lang.String), the parameters of publish"), result.errors(), result.describe());
    }

    @Test
    void checkWithAnExtraInputIsSG513() {
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc",
                check("PUBLISH", "canPublish(Doc doc, String reason)")));

        assertTrue(result.hasError("SG513"), result.describe());
        assertEquals(List.of("[SG513] canPublish must be declared as Eligibility<Doc, DocStatus, DocTransition>"
                + " canPublish(Doc), the parameters of publish"), result.errors(), result.describe());
    }

    @Test
    void checkWithAnotherEntityIsSG513() {
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc",
                check("PUBLISH", "canPublish(Object doc)")));

        assertTrue(result.hasError("SG513"), result.describe());
    }

    @Test
    void checkWithAnotherInputTypeIsSG513() {
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc, String reason",
                check("PUBLISH", "canPublish(Doc doc, CharSequence reason)")));

        assertTrue(result.hasError("SG513"), result.describe());
    }

    @Test
    void checkWithAnotherReturnIsSG513() {
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc", """
                    @DocsGraph.Check(id = PUBLISH)
                    Outcome<Doc, DocStatus, DocTransition> canPublish(Doc doc);
                """));

        assertTrue(result.hasError("SG513"), result.describe());
    }

    /** The operation takes a {@code String[]}, so only the varargs of the check sets it apart. */
    @Test
    void varargsCheckIsSG513() {
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc, String[] reasons",
                check("PUBLISH", "canPublish(Doc doc, String... reasons)")));

        assertTrue(result.hasError("SG513"), result.describe());
        assertEquals(List.of("[SG513] canPublish must be declared as Eligibility<Doc, DocStatus, DocTransition>"
                + " canPublish(Doc, java.lang.String[]), the parameters of publish"), result.errors(),
                result.describe());
    }

    @Test
    void checkWithAMethodTypeParameterIsSG513() {
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc",
                "    @DocsGraph.Check(id = PUBLISH)\n    <X> " + ELIGIBILITY + " canPublish(Doc doc);\n"));

        assertTrue(result.hasError("SG513"), result.describe());
    }

    @Test
    void twoChecksOfOneOperationIsSG514() {
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc",
                check("PUBLISH", "canPublish(Doc doc)") + check("PUBLISH", "mayPublish(Doc doc)")));

        assertTrue(result.hasError("SG514"), result.describe());
        assertEquals(List.of("[SG514] mayPublish checks PUBLISH, which canPublish checks already: an operation has"
                + " one Check at most"), result.errors(), result.describe());
    }

    /** The method is still read as the operation its Transition declares, so its signature is no further error. */
    @Test
    void transitionAndCheckOnOneMethodIsSG514() {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("fx.Docs", sources.get("fx.Docs").replace(PUBLISH, "    @DocsGraph.Check(id = PUBLISH)\n"
                + PUBLISH));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.hasError("SG514"), result.describe());
        assertEquals(List.of("[SG514] publish carries a Transition and a Check: a Check is declared on a method of"
                + " its own, apart from the operation it checks"), result.errors(), result.describe());
    }

    @Test
    void checkOnADefaultMethodIsSG504() {
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc", """
                    @DocsGraph.Check(id = PUBLISH)
                    default io.github.graphmaquina.api.Eligibility<Doc, DocStatus, DocTransition> canPublish(Doc doc) {
                        throw new UnsupportedOperationException();
                    }
                """));

        assertTrue(result.hasError("SG504"), result.describe());
        assertEquals(List.of("[SG504] canPublish is a default method, so its fx.DocsGraph.Check is never read: a"
                + " Check is declared on an abstract method of Docs"), result.errors(), result.describe());
    }

    /** A method of {@code Machine} is implemented by the companion already, so it can be no check. */
    @Test
    void checkOnARedeclarationOfAMachineMethodIsSG504() {
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc", """
                    @DocsGraph.Check(id = PUBLISH)
                    Outcome<Doc, DocStatus, DocTransition> reevaluate(Doc doc);
                """));

        assertEquals(List.of("[SG504] reevaluate overrides Machine.reevaluate, which the generated companion"
                + " implements already: give the method another name"), result.errors(), result.describe());
    }

    /** The Check of another machine is that machine's vocabulary, so the method carries none of its own facade. */
    @Test
    void checkOfAnotherMachineIsSG606() {
        Map<String, String> sources = TypedSubmachineTest.bound(TypedSubmachineTest.BOUND);
        sources.put("fx.Docs", sources.get("fx.Docs").replace(PUBLISH, PUBLISH
                + "    @PagesGraph.Check(id = PageTransition.FINISH)\n    " + ELIGIBILITY + " canFinish(Doc doc);\n"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(List.of("[SG606] fx.PagesGraph.Check is the vocabulary of fx.Pages, not of fx.Docs",
                "[SG504] canFinish must carry at least one Transition, or one Check, of its own graph vocabulary"),
                result.errors(), result.describe());
    }

    /**
     * The minimal fixture with {@code parameters} as those of its operation and {@code members} declared after it.
     * Its identifiers gain {@code FAIL} and {@code ARCHIVE}, which no edge declares.
     */
    private static Map<String, String> minimal(String parameters, String members) {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("fx.DocTransition", "package fx;\npublic enum DocTransition { PUBLISH, FAIL, ARCHIVE }\n");
        sources.put("fx.Docs", sources.get("fx.Docs").replace(PUBLISH,
                "    Outcome<Doc, DocStatus, DocTransition> publish(" + parameters + ");\n" + members));
        return sources;
    }

    /** A check of {@code id}, declared as {@code declaration} under the result of the minimal machine. */
    private static String check(String id, String declaration) {
        return "    @DocsGraph.Check(id = " + id + ")\n    " + ELIGIBILITY + " " + declaration + ";\n";
    }
}
