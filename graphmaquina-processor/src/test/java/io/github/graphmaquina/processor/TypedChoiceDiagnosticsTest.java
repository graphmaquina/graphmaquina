package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The choices a facade declares with its generated {@code Choice}: what the processor refuses in them, all as
 * {@code SG516}, each rule with a message of its own. A choice is declared on an abstract method of its own, as
 * {@code Outcome<E, S, T> name(E)} or {@code name(E, I)} to run it, or as {@code ChoiceEligibility<E, S, T>} to
 * check it. Its candidates are at least one identifier, each named once, declared by an edge, no exit of a
 * sub-machine, and run by no operation whose input differs from the choice's.
 *
 * <p>Every case is an error. Each fixture is otherwise valid, so the error a test expects is the only one the
 * compilation reports; {@link TypedChoiceGenerationTest} compiles the choices that are valid. The results are named
 * in full, so no fixture has to import them.</p>
 */
class TypedChoiceDiagnosticsTest {

    /** The result of a choice over the machine of {@link TypedFixtures#minimal()}. */
    private static final String OUTCOME = "Outcome<Doc, DocStatus, DocTransition>";

    /** The operation of {@link TypedFixtures#minimal()}, as its facade spells it. */
    private static final String PUBLISH = "    Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);\n";

    /** What {@code SG516} says of an input a candidate's operation does not take. */
    private static final String SAME_INPUT =
            ": a Choice takes the same input as each operation that executes one of its candidates";

    @Test
    void aChoiceThatReturnsNeitherAnOutcomeNorAChoiceEligibilityIsRefused() {
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc", "    @DocsGraph.Choice(PUBLISH)\n"
                + "    io.github.graphmaquina.api.Eligibility<Doc, DocStatus, DocTransition> pick(Doc doc);\n"));

        assertEquals(List.of("[" + DiagnosticCode.SG516 + "] pick must return Outcome<Doc, DocStatus, DocTransition>,"
                + " to run the choice, or ChoiceEligibility<Doc, DocStatus, DocTransition>, to check it"),
                result.errors(), result.describe());
    }

    /** No entity, another entity, two inputs, varargs and a method type parameter all leave the shape. */
    @ParameterizedTest
    @ValueSource(strings = {"pick()", "pick(Object doc)", "pick(Doc doc, String first, String second)",
            "pick(Doc doc, String... reasons)", "<X> pick(Doc doc)"})
    void aChoiceThatTakesAnythingButTheEntityAndOneInputIsRefused(String declaration) {
        String typed = declaration.startsWith("<X> ")
                ? "<X> " + OUTCOME + " " + declaration.substring("<X> ".length())
                : OUTCOME + " " + declaration;
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc",
                "    @DocsGraph.Choice(PUBLISH)\n    " + typed + ";\n"));

        assertEquals(List.of("[" + DiagnosticCode.SG516 + "] pick must be declared as pick(Doc) or pick(Doc, I), the"
                + " entity and at most one input, without varargs or method type parameters"), result.errors(),
                declaration + "\n" + result.describe());
    }

    /** The method is still read as the operation its Transition declares, so its signature is no further error. */
    @Test
    void aChoiceOnAnOperationIsRefused() {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("fx.Docs", sources.get("fx.Docs").replace(PUBLISH, "    @DocsGraph.Choice(PUBLISH)\n" + PUBLISH));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(List.of("[" + DiagnosticCode.SG516 + "] publish carries a Choice and a Transition: a Choice is"
                + " declared on a method of its own, without a Transition or a Check"), result.errors(),
                result.describe());
    }

    /** The method is read as neither a check nor a choice, so its result is no further error. */
    @Test
    void aChoiceOnACheckIsRefused() {
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc", "    @DocsGraph.Choice(PUBLISH)\n"
                + "    @DocsGraph.Check(id = PUBLISH)\n"
                + "    io.github.graphmaquina.api.Eligibility<Doc, DocStatus, DocTransition> canPublish(Doc doc);\n"));

        assertEquals(List.of("[" + DiagnosticCode.SG516 + "] canPublish carries a Choice and a Check: a Choice is"
                + " declared on a method of its own, without a Transition or a Check"), result.errors(),
                result.describe());
    }

    @Test
    void aChoiceOfNoCandidateIsRefused() {
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc", choice("{}", "pick(Doc doc)")));

        assertEquals(List.of("[" + DiagnosticCode.SG516 + "] pick names no candidate: a Choice names at least one"
                + " identifier"), result.errors(), result.describe());
    }

    @Test
    void aChoiceThatNamesACandidateTwiceIsRefused() {
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc",
                choice("{PUBLISH, PUBLISH}", "pick(Doc doc)")));

        assertEquals(List.of("[" + DiagnosticCode.SG516 + "] pick names PUBLISH twice: a Choice names each candidate"
                + " once"), result.errors(), result.describe());
    }

    @Test
    void aChoiceOfAnIdentifierNoEdgeDeclaresIsRefused() {
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc",
                choice("{PUBLISH, ARCHIVE}", "pick(Doc doc)")));

        assertEquals(List.of("[" + DiagnosticCode.SG516 + "] pick names ARCHIVE, which no edge of Docs declares: each"
                + " candidate of a Choice is the identifier of an edge"), result.errors(), result.describe());
    }

    @Test
    void aChoiceOfAnExitIsRefused() {
        Map<String, String> sources = TypedSubmachineTest.bound(TypedSubmachineTest.BOUND);
        sources.put("fx.Docs", sources.get("fx.Docs").replace(PUBLISH, PUBLISH
                + choice("{PUBLISH, COMPLETE}", "pick(Doc doc)")));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(List.of("[" + DiagnosticCode.SG516 + "] pick names COMPLETE, an exit of the sub-machine of"
                + " Docs.REVIEW, which only the sub-machine runs: a Choice cannot name an exit"), result.errors(),
                result.describe());
    }

    @Test
    void aChoiceWithoutTheInputOfACandidatesOperationIsRefused() {
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc, String reason",
                choice("PUBLISH", "pick(Doc doc)")));

        assertEquals(List.of("[" + DiagnosticCode.SG516 + "] pick names PUBLISH, which publish executes with an input"
                + " of type java.lang.String, while pick takes no input" + SAME_INPUT), result.errors(),
                result.describe());
    }

    @Test
    void aChoiceWithAnInputACandidatesOperationDoesNotTakeIsRefused() {
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc",
                choice("PUBLISH", "pick(Doc doc, String reason)")));

        assertEquals(List.of("[" + DiagnosticCode.SG516 + "] pick names PUBLISH, which publish executes with no input,"
                + " while pick takes an input of type java.lang.String" + SAME_INPUT), result.errors(),
                result.describe());
    }

    @Test
    void aChoiceWithAnotherInputTypeThanACandidatesOperationIsRefused() {
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc, String reason",
                choice("PUBLISH", "pick(Doc doc, CharSequence reason)")));

        assertEquals(List.of("[" + DiagnosticCode.SG516 + "] pick names PUBLISH, which publish executes with an input"
                + " of type java.lang.String, while pick takes an input of type java.lang.CharSequence" + SAME_INPUT),
                result.errors(), result.describe());
    }

    /**
     * The operation is refused for its signature, and only that refusal is reported: its Transition still names
     * PUBLISH, though no edge declares it, so the choice adds no SG516.
     */
    @Test
    void aChoiceOfAnOperationRefusedForItsSignatureReportsThatRefusalAlone() {
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc, String first, String second",
                choice("PUBLISH", "pick(Doc doc)")));

        assertEquals(List.of("[" + DiagnosticCode.SG504
                + "] an operation is declared as Outcome<Doc, DocStatus, DocTransition> publish(Doc)"
                + " or publish(Doc, I), without varargs or method type parameters on input operations"),
                result.errors(), result.describe());
    }

    /** The edge the interface declares is refused for its own error, and only that error is reported. */
    @Test
    void aChoiceOfAnEdgeRefusedOnTheInterfaceReportsThatRefusalAlone() {
        Map<String, String> sources = TypedChoiceGenerationTest.choosing(TypedChoiceGenerationTest.choice(
                "{REJECT, APPROVE}", OUTCOME, "review(Doc doc)"));
        sources.put("fx.Docs", sources.get("fx.Docs").replace("@DocsGraph.Transition(id = APPROVE, from = DRAFT,"
                + " to = PUBLISHED)", "@DocsGraph.Transition(id = APPROVE, from = DRAFT, to = PUBLISHED,"
                + " action = DocActionsRefs.Publish.class, failsOn = Exception.class)"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(1, result.errors().size(), result.describe());
        assertEquals(1, result.countError(DiagnosticCode.SG302.name()), result.describe());
    }

    @Test
    void aChoiceOnADefaultMethodIsRefused() {
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc", """
                    @DocsGraph.Choice(PUBLISH)
                    default Outcome<Doc, DocStatus, DocTransition> pick(Doc doc) {
                        throw new UnsupportedOperationException();
                    }
                """));

        assertEquals(List.of("[" + DiagnosticCode.SG504
                + "] pick is a default method, so its fx.DocsGraph.Choice is never read: a"
                + " Choice is declared on an abstract method of Docs"), result.errors(), result.describe());
    }

    /** A method of {@code Machine} is implemented by the companion already, so it can be no choice. */
    @Test
    void aChoiceOnARedeclarationOfAMachineMethodIsRefused() {
        Compilation.Result result = Compilation.compileTyped(minimal("Doc doc",
                choice("PUBLISH", "reevaluate(Doc doc)")));

        assertEquals(List.of("[" + DiagnosticCode.SG504
                + "] reevaluate overrides Machine.reevaluate, which the generated companion"
                + " implements already: give the method another name"), result.errors(), result.describe());
    }

    /** The Choice of another machine is that machine's vocabulary, so the method carries none of its own facade. */
    @Test
    void aChoiceOfAnotherMachineIsMisplacedVocabulary() {
        Map<String, String> sources = TypedSubmachineTest.bound(TypedSubmachineTest.BOUND);
        sources.put("fx.Docs", sources.get("fx.Docs").replace(PUBLISH, PUBLISH
                + "    @PagesGraph.Choice(PageTransition.FINISH)\n    " + OUTCOME + " finish(Doc doc);\n"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(List.of("[" + DiagnosticCode.SG606
                + "] fx.PagesGraph.Choice is the vocabulary of fx.Pages, not of fx.Docs",
                "[" + DiagnosticCode.SG504
                        + "] finish must carry at least one Transition, Check or Choice of its own graph vocabulary"),
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

    /** A choice of {@code candidates}, declared as {@code declaration} under the result that runs it. */
    private static String choice(String candidates, String declaration) {
        return "    @DocsGraph.Choice(" + candidates + ")\n    " + OUTCOME + " " + declaration + ";\n";
    }
}
