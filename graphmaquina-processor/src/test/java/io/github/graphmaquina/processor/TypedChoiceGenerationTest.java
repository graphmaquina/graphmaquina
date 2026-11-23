package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * What the generated companion does with the choices a facade declares: each becomes a public method that refuses a
 * null argument, as an operation does, and hands its candidates, in the order the {@code Choice} names them, to
 * {@code choose} on the state machine, or to {@code chooseEligibility} when it returns a {@code ChoiceEligibility},
 * with the input when it takes one. A choice owns no identifier and opens no edge, so a candidate may also have an
 * operation of its own, and one that failure routes use may be named.
 *
 * <p>The case that runs code compiles a real consumer against the companion the processor wrote, so what it reads is
 * what the facade answers.</p>
 */
class TypedChoiceGenerationTest {

    /** The result of a choice over the machine of {@link #choosing(String)}. */
    private static final String OUTCOME = "Outcome<Doc, DocStatus, DocTransition>";

    /** The result of a choice check over the machine of {@link #choosing(String)}. */
    private static final String CHOICE_ELIGIBILITY = "ChoiceEligibility<Doc, DocStatus, DocTransition>";

    /**
     * The facade of {@link #choosing(String)}, with its members after the operation written {@code %s}: from
     * {@code DRAFT}, {@code PUBLISH} is an operation, {@code REJECT}, whose guard always refuses, and {@code APPROVE}
     * are edges the interface declares itself.
     */
    private static final String DOCS = """
            package fx;
            import io.github.graphmaquina.annotation.GenerateGraphApi;
            import io.github.graphmaquina.api.ChoiceEligibility;
            import io.github.graphmaquina.api.Machine;
            import io.github.graphmaquina.api.Outcome;
            import static fx.DocStatus.*;
            import static fx.DocTransition.*;
            @GenerateGraphApi
            @DocsGraph.Definition(initial = DRAFT, terminals = {PUBLISHED, REJECTED},
                state = DocRefs.Status.class, id = DocRefs.Id.class)
            @DocsGraph.StatePolicy(state = DRAFT, otherwise = "a review decides")
            @DocsGraph.Transition(id = REJECT, from = DRAFT, to = REJECTED, guards = DocActionsRefs.Refused.class)
            @DocsGraph.Transition(id = APPROVE, from = DRAFT, to = PUBLISHED)
            public interface Docs extends Machine<Doc, DocStatus, DocTransition> {
                @DocsGraph.Transition(id = PUBLISH, from = DRAFT, to = PUBLISHED, action = DocActionsRefs.Publish.class)
                Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
            %s}
            """;

    /** The host of {@link #choosing(String)}: its guard refuses every request, its action does nothing. */
    private static final String HOST = """
            package fx;
            import io.github.graphmaquina.annotation.GraphAction;
            import io.github.graphmaquina.annotation.GraphGuard;
            import io.github.graphmaquina.api.ActionOutcome;
            import io.github.graphmaquina.api.GuardContext;
            import io.github.graphmaquina.api.GuardResult;
            import io.github.graphmaquina.api.TransitionContext;
            public final class DocActions {
                @GraphGuard public GuardResult refused(GuardContext<Doc, DocStatus, DocTransition> ctx) {
                    return GuardResult.reject("the review approves");
                }
                @GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus, DocTransition> ctx) {
                    return ActionOutcome.ok();
                }
            }
            """;

    /**
     * The consumer of {@link #theGeneratedChoiceRunsTheFirstAdmitted}: it checks the choice, then runs it, over one
     * draft, and reports what it saw, one line for each call.
     */
    private static final String CONSUMER = """
            package fx;
            import io.github.graphmaquina.api.ChoiceEligibility;
            import io.github.graphmaquina.api.Eligibility;
            import io.github.graphmaquina.api.Outcome;
            import io.github.graphmaquina.runtime.StateMachines;
            import java.util.List;
            public final class Consumer {
                public static List<String> run() {
                    Docs docs = new DocsRuntime(StateMachines.from(DocsRuntime.graph(new DocActions()))
                            .state(Doc::status, Doc::status).build());
                    Doc doc = new Doc();
                    ChoiceEligibility<Doc, DocStatus, DocTransition> answer = docs.canReview(doc);
                    Eligibility.Ineligible<Doc, DocStatus, DocTransition> refusal =
                            answer.asChosen().orElseThrow().rejected().get(0);
                    String checked = "chosen " + answer.chosen().orElseThrow() + " after " + refusal.transition()
                            + " [" + refusal.reason() + "] " + doc.status();
                    Outcome<Doc, DocStatus, DocTransition> outcome = docs.review(doc);
                    String ran = outcome.getClass().getSimpleName() + " " + outcome.requestedTransition().orElseThrow()
                            + " " + doc.status();
                    return List.of(checked, ran);
                }
            }
            """;

    @Test
    void aChoiceDelegatesToChoose() {
        Compilation.Result result = Compilation.compileTyped(choosing(choice("{REJECT, APPROVE}", OUTCOME,
                "review(Doc doc)")));

        assertTrue(result.success(), result.describe());
        String generated = result.generated("fx.DocsRuntime");
        assertTrue(generated.contains("public Outcome<Doc, DocStatus, DocTransition> review(Doc doc)"), generated);
        assertTrue(generated.contains("Objects.requireNonNull(doc, \"doc\");"), generated);
        assertTrue(generated.contains(
                "return stateMachine.choose(doc, List.of(DocTransition.REJECT, DocTransition.APPROVE));"), generated);
        assertTrue(generated.contains("Runs, as one call, the first of {@code DocTransition.REJECT},"
                + " {@code DocTransition.APPROVE}"), generated);
    }

    @Test
    void aChoiceWithInputPassesIt() {
        Compilation.Result result = Compilation.compileTyped(choosing(choice("{REJECT, APPROVE}", OUTCOME,
                "review(Doc doc, external.Verdict verdict)")));

        assertTrue(result.success(), result.describe());
        String generated = result.generated("fx.DocsRuntime");
        assertTrue(generated.contains("review(Doc doc, external.Verdict verdict)"), generated);
        assertTrue(generated.contains("Objects.requireNonNull(verdict, \"verdict\");"), generated);
        assertTrue(generated.contains("return stateMachine.choose(doc, List.of(DocTransition.REJECT,"
                + " DocTransition.APPROVE), verdict);"), generated);
    }

    @Test
    void aChoiceCheckDelegatesToChooseEligibility() {
        Compilation.Result result = Compilation.compileTyped(choosing(choice("{REJECT, APPROVE}", CHOICE_ELIGIBILITY,
                "canReview(Doc doc, external.Verdict verdict)")));

        assertTrue(result.success(), result.describe());
        String generated = result.generated("fx.DocsRuntime");
        assertTrue(generated.contains("public ChoiceEligibility<Doc, DocStatus, DocTransition> canReview(Doc doc,"
                + " external.Verdict verdict)"), generated);
        assertTrue(generated.contains("Objects.requireNonNull(verdict, \"verdict\");"), generated);
        assertTrue(generated.contains("return stateMachine.chooseEligibility(doc, List.of(DocTransition.REJECT,"
                + " DocTransition.APPROVE), verdict);"), generated);
        assertTrue(generated.contains("without running it"), generated);
    }

    /**
     * A choice owns no identifier, so the operation that executes a candidate keeps it: no {@code SG703}. It opens no
     * edge either, so the graph declares the operation's edge once.
     */
    @Test
    void aCandidateMayAlsoHaveItsOwnOperation() {
        Compilation.Result result = Compilation.compileTyped(choosing(choice("{PUBLISH, APPROVE}", OUTCOME,
                "review(Doc doc)")));

        assertEquals(List.of(), result.errors(), result.describe());
        String generated = result.generated("fx.DocsRuntime");
        assertTrue(generated.contains("return stateMachine.execute(doc, DocTransition.PUBLISH);"), generated);
        assertTrue(generated.contains(
                "return stateMachine.choose(doc, List.of(DocTransition.PUBLISH, DocTransition.APPROVE));"), generated);
        assertEquals(1, occurrences(result.beforeStructure("fx.DocsRuntime"), ".action(\"publish\""), generated);
    }

    /**
     * A choice takes the input of the operation that executes a candidate, here an {@code int}, which cannot be null
     * and is passed boxed, as the state machine takes it as an {@code Object}.
     */
    @Test
    void aChoiceWithTheInputOfACandidatesOperationPassesItBoxed() {
        Map<String, String> sources = choosing(choice("{PUBLISH, APPROVE}", OUTCOME, "review(Doc doc, int verdict)"));
        sources.put("fx.Docs", sources.get("fx.Docs").replace("publish(Doc doc);", "publish(Doc doc, int verdict);"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(List.of(), result.errors(), result.describe());
        String generated = result.generated("fx.DocsRuntime");
        assertFalse(generated.contains("requireNonNull(verdict"), generated);
        assertTrue(generated.contains("return stateMachine.choose(doc, List.of(DocTransition.PUBLISH,"
                + " DocTransition.APPROVE), verdict);"), generated);
    }

    /** A route target can still be requested, so a choice may name an identifier that failure routes use. */
    @Test
    void aCandidateThatFailureRoutesUseMayBeChosen() {
        Map<String, String> sources = choosing(choice("{REJECT, FAIL}", OUTCOME, "review(Doc doc)"));
        sources.put("fx.DocStatus", "package fx;\npublic enum DocStatus { DRAFT, PUBLISHED, REJECTED, FAILED }\n");
        sources.put("fx.DocTransition", "package fx;\npublic enum DocTransition { PUBLISH, REJECT, APPROVE, FAIL }\n");
        sources.put("fx.Unavailable", "package fx;\n"
                + "public record Unavailable() implements io.github.graphmaquina.api.FailureKind { }\n");
        sources.put("fx.Docs", sources.get("fx.Docs")
                .replace("terminals = {PUBLISHED, REJECTED}", "terminals = {PUBLISHED, REJECTED, FAILED}")
                .replace("public interface Docs",
                        "@DocsGraph.Transition(id = FAIL, from = DRAFT, to = FAILED)\npublic interface Docs")
                .replace("action = DocActionsRefs.Publish.class)", "action = DocActionsRefs.Publish.class,"
                        + " recoveries = @DocsGraph.Recovery(kind = Unavailable.class, transition = FAIL))"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(List.of(), result.errors(), result.describe());
        assertTrue(result.generated("fx.DocsRuntime").contains(
                "return stateMachine.choose(doc, List.of(DocTransition.REJECT, DocTransition.FAIL));"),
                result.generated("fx.DocsRuntime"));
    }

    /**
     * The compiled consumer checks the choice of a draft, then runs it: the guard of {@code REJECT}, the first
     * candidate, refuses it with its reason, so the check names {@code APPROVE} and changes nothing, and the choice
     * applies {@code APPROVE}, which is the transition the outcome reports.
     */
    @Test
    void theGeneratedChoiceRunsTheFirstAdmitted() throws Exception {
        Map<String, String> sources = choosing(choice("{REJECT, APPROVE}", OUTCOME, "review(Doc doc)")
                + choice("{REJECT, APPROVE}", CHOICE_ELIGIBILITY, "canReview(Doc doc)"));
        sources.put("fx.Consumer", CONSUMER);

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.success(), result.describe());
        assertEquals(List.of("chosen APPROVE after REJECT [the review approves] DRAFT", "Applied APPROVE PUBLISHED"),
                result.load("fx.Consumer").getMethod("run").invoke(null));
    }

    /**
     * The minimal fixture with three states, the identifiers {@code PUBLISH}, {@code REJECT} and {@code APPROVE}, the
     * facade of {@link #DOCS} with {@code members} after its operation, the host of {@link #HOST}, and a
     * {@code Verdict} an input may take.
     */
    static Map<String, String> choosing(String members) {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("external.Verdict", "package external; public record Verdict(boolean accepted) { }");
        sources.put("fx.DocStatus", "package fx;\npublic enum DocStatus { DRAFT, PUBLISHED, REJECTED }\n");
        sources.put("fx.DocTransition", "package fx;\npublic enum DocTransition { PUBLISH, REJECT, APPROVE }\n");
        sources.put("fx.DocActions", HOST);
        sources.put("fx.Docs", DOCS.formatted(members));
        return sources;
    }

    /** A choice of {@code candidates}, declared as {@code declaration} under {@code result}. */
    static String choice(String candidates, String result, String declaration) {
        return "    @DocsGraph.Choice(" + candidates + ")\n    " + result + " " + declaration + ";\n";
    }

    private static int occurrences(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }
}
