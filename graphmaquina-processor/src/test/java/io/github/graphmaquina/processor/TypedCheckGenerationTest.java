package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What the generated companion does with the checks a facade declares: each becomes a public method that refuses
 * a null argument, as an operation does, and asks the state machine for the eligibility of the operation it checks,
 * with the input when the check has one. A check is no operation: it adds no edge and owns no identifier.
 *
 * <p>The cases that run code compile a real consumer against the companion the processor wrote, so what they read
 * is what the facade answers.</p>
 */
class TypedCheckGenerationTest {

    /** The result of a check over the machine of {@link TypedFixtures#minimal()}, as its facade imports it. */
    private static final String ELIGIBILITY = "Eligibility<Doc, DocStatus, DocTransition>";

    /**
     * The facade of {@link #verdicts()}: three states, and a check of {@code PUBLISH}, whose guard reads the
     * {@code Verdict} the operation takes. The policy of {@code REVIEW} gives a reason to the identifiers it has no
     * edge for.
     */
    private static final String VERDICT_DOCS = """
            package fx;
            import external.Verdict;
            import io.github.graphmaquina.annotation.GenerateGraphApi;
            import io.github.graphmaquina.api.Eligibility;
            import io.github.graphmaquina.api.Machine;
            import io.github.graphmaquina.api.Outcome;
            import static fx.DocStatus.*;
            import static fx.DocTransition.*;
            @GenerateGraphApi
            @DocsGraph.Definition(initial = DRAFT, terminals = PUBLISHED,
                state = DocRefs.Status.class, id = DocRefs.Id.class)
            @DocsGraph.StatePolicy(state = DRAFT, otherwise = "publish before finishing")
            @DocsGraph.StatePolicy(state = REVIEW, otherwise = "a review decides")
            @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
            @DocsGraph.Transition(id = APPROVE, from = REVIEW, to = PUBLISHED)
            public interface Docs extends Machine<Doc, DocStatus, DocTransition> {
                @DocsGraph.Transition(id = PUBLISH, from = DRAFT, to = PUBLISHED,
                    action = DocActionsRefs.Publish.class, guards = DocActionsRefs.Accepted.class)
                Outcome<Doc, DocStatus, DocTransition> publish(Doc doc, Verdict value);
                @DocsGraph.Check(id = PUBLISH)
                Eligibility<Doc, DocStatus, DocTransition> canPublish(Doc doc, Verdict value);
            }
            """;

    /** The host of {@link #verdicts()}: its guard accepts an accepted verdict only, and its action says it ran. */
    private static final String VERDICT_HOST = """
            package fx;
            import external.Verdict;
            import io.github.graphmaquina.annotation.GraphAction;
            import io.github.graphmaquina.annotation.GraphGuard;
            import io.github.graphmaquina.api.ActionOutcome;
            import io.github.graphmaquina.api.GuardContext;
            import io.github.graphmaquina.api.GuardResult;
            import io.github.graphmaquina.api.TransitionContext;
            public final class DocActions {
                public boolean ran;
                @GraphGuard public GuardResult accepted(GuardContext<Doc, DocStatus, DocTransition> ctx) {
                    return ctx.input(Verdict.class).filter(Verdict::accepted).isPresent()
                            ? GuardResult.pass() : GuardResult.reject("not accepted");
                }
                @GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus, DocTransition> ctx) {
                    ran = true;
                    return ActionOutcome.ok();
                }
            }
            """;

    /**
     * The consumer of {@link #verdicts()}: it asks the facade what it can answer and reports what it saw, one line
     * for each question, so a test reads the whole exchange at once.
     */
    private static final String VERDICT_CONSUMER = """
            package fx;
            import external.Verdict;
            import io.github.graphmaquina.api.Eligibility;
            import io.github.graphmaquina.runtime.StateMachines;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.function.Supplier;
            public final class Consumer {
                public static List<String> run() {
                    DocActions actions = new DocActions();
                    Docs docs = new DocsRuntime(StateMachines.from(DocsRuntime.graph(actions))
                            .state(Doc::status, Doc::status).build());
                    Doc draft = new Doc();
                    Doc reviewed = new Doc();
                    reviewed.status(DocStatus.REVIEW);
                    List<String> seen = new ArrayList<>();
                    seen.add(describe(docs.canPublish(draft, new Verdict(true)), draft));
                    seen.add(describe(docs.canPublish(draft, new Verdict(false)), draft));
                    seen.add(describe(docs.canPublish(reviewed, new Verdict(true)), reviewed));
                    seen.add(refused(() -> docs.canPublish(null, new Verdict(true))));
                    seen.add(refused(() -> docs.canPublish(draft, null)));
                    seen.add(draft.status() + " " + reviewed.status() + " ran=" + actions.ran);
                    return seen;
                }
                private static String describe(Eligibility<Doc, DocStatus, DocTransition> answer, Doc doc) {
                    String of = " of the same entity=" + (answer.entity() == doc);
                    return switch (answer) {
                        case Eligibility.Eligible<Doc, DocStatus, DocTransition> eligible ->
                                "eligible " + eligible.from() + " " + eligible.transition() + of;
                        case Eligibility.Ineligible<Doc, DocStatus, DocTransition> refusal ->
                                "ineligible " + refusal.kind() + " [" + refusal.reason() + "] "
                                        + refusal.guard().orElse("-") + " " + refusal.from() + of;
                    };
                }
                private static String refused(Supplier<Eligibility<Doc, DocStatus, DocTransition>> call) {
                    try {
                        call.get();
                        return "answered";
                    } catch (NullPointerException refusal) {
                        return "NPE " + refusal.getMessage();
                    }
                }
            }
            """;

    /** The host of {@link #primitive()}: its guard keeps the input it was handed, which only a check passes. */
    private static final String INPUT_HOST = """
            package fx;
            import io.github.graphmaquina.annotation.GraphAction;
            import io.github.graphmaquina.annotation.GraphGuard;
            import io.github.graphmaquina.api.ActionOutcome;
            import io.github.graphmaquina.api.GuardContext;
            import io.github.graphmaquina.api.GuardResult;
            import io.github.graphmaquina.api.TransitionContext;
            public final class DocActions {
                public Object seen;
                @GraphGuard public GuardResult observe(GuardContext<Doc, DocStatus, DocTransition> ctx) {
                    seen = ctx.input(Integer.class);
                    return GuardResult.pass();
                }
                @GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus, DocTransition> ctx) {
                    return ActionOutcome.ok();
                }
            }
            """;

    /** The consumer of {@link #primitive()}: it checks a document with an {@code int}, reports what the guard saw. */
    private static final String INPUT_CONSUMER = """
            package fx;
            import io.github.graphmaquina.runtime.StateMachines;
            public final class Consumer {
                public static Object run() {
                    DocActions actions = new DocActions();
                    Docs docs = new DocsRuntime(StateMachines.from(DocsRuntime.graph(actions))
                            .state(Doc::status, Doc::status).build());
                    docs.canPublish(new Doc(), 7);
                    return actions.seen;
                }
            }
            """;

    @Test
    void generatedCheckDelegatesToEligibility() {
        Compilation.Result result = Compilation.compileTyped(withCheck("publish(Doc doc)", "canPublish(Doc doc)"));

        assertTrue(result.success(), result.describe());
        String generated = result.generated("fx.DocsRuntime");
        assertTrue(generated.contains("public Eligibility<Doc, DocStatus, DocTransition> canPublish(Doc doc)"),
                generated);
        assertTrue(generated.contains("Objects.requireNonNull(doc, \"doc\");"), generated);
        assertTrue(generated.contains("return stateMachine.eligibility(doc, DocTransition.PUBLISH);"), generated);
        assertTrue(generated.contains("Asks whether {@code DocTransition.PUBLISH} would be admitted for {@code doc}"),
                generated);
        assertTrue(generated.contains("without running it"), generated);
    }

    @Test
    void generatedCheckWithInputPassesIt() {
        Compilation.Result result = Compilation.compileTyped(withCheck("publish(Doc doc, external.Verdict value)",
                "canPublish(Doc doc, external.Verdict value)"));

        assertTrue(result.success(), result.describe());
        String generated = result.generated("fx.DocsRuntime");
        assertTrue(generated.contains("canPublish(Doc doc, external.Verdict value)"), generated);
        assertTrue(generated.contains("Objects.requireNonNull(value, \"value\");"), generated);
        assertTrue(generated.contains("return stateMachine.eligibility(doc, DocTransition.PUBLISH, value);"),
                generated);
    }

    /**
     * Every type of input an operation may take is the type of its check, written the way the operation writes it. A
     * primitive one cannot be null, so it is not checked.
     */
    @ParameterizedTest
    @ValueSource(strings = {"external.Verdict", "external.Types.Nested", "String", "java.util.List<external.Verdict>",
            "java.util.List<? extends external.Verdict>", "external.Verdict[]", "int[]"})
    void eachReferenceInputTypeIsCheckedForNull(String type) {
        Compilation.Result result = Compilation.compileTyped(withCheck("publish(Doc doc, " + type + " value)",
                "canPublish(Doc doc, " + type + " value)"));

        assertTrue(result.success(), type + "\n" + result.describe());
        String generated = result.generated("fx.DocsRuntime");
        assertTrue(generated.contains("Objects.requireNonNull(value, \"value\");"), generated);
        assertTrue(generated.contains("return stateMachine.eligibility(doc, DocTransition.PUBLISH, value);"),
                generated);
    }

    /** The input is passed boxed, as {@code execute} passes it, and the guard of the operation reads it back. */
    @Test
    void primitiveInputIsPassedBoxedWithoutNullCheck() throws Exception {
        Compilation.Result result = Compilation.compileTyped(primitive());

        assertTrue(result.success(), result.describe());
        String generated = result.generated("fx.DocsRuntime");
        assertTrue(generated.contains("canPublish(Doc doc, int value)"), generated);
        assertFalse(generated.contains("requireNonNull(value"), generated);
        assertTrue(generated.contains("return stateMachine.eligibility(doc, DocTransition.PUBLISH, value);"),
                generated);
        assertEquals(Optional.of(7), result.load("fx.Consumer").getMethod("run").invoke(null));
    }

    /** A parameter the companion already names takes a new name, and the message still gives the declared one. */
    @Test
    void checkParametersFollowTheNamingRulesOfOperations() {
        Compilation.Result result = Compilation.compileTyped(withCheck("publish(Doc doc)",
                "canPublish(Doc stateMachine)"));

        assertTrue(result.success(), result.describe());
        String generated = result.generated("fx.DocsRuntime");
        assertTrue(generated.contains("canPublish(Doc stateMachine2)"), generated);
        assertTrue(generated.contains("Objects.requireNonNull(stateMachine2, \"stateMachine\");"), generated);
        assertTrue(generated.contains("return stateMachine.eligibility(stateMachine2, DocTransition.PUBLISH);"),
                generated);
    }

    /** The companion imports the result, so an entity of the same simple name is the one it writes qualified. */
    @Test
    void anEntityNamedLikeTheResultIsWrittenQualified() {
        Map<String, String> sources = new LinkedHashMap<>();
        TypedFixtures.minimal().forEach((String name, String text) -> sources.put(
                name.equals("fx.Doc") ? "fx.Eligibility" : name,
                text.replaceAll("\\bDoc\\b", "Eligibility").replace("DocRefs", "EligibilityRefs")));
        sources.put("fx.Docs", sources.get("fx.Docs").replace("publish(Eligibility doc);",
                "publish(Eligibility doc);\n    @DocsGraph.Check(id = PUBLISH)\n"
                        + "    io.github.graphmaquina.api.Eligibility<fx.Eligibility, DocStatus, DocTransition>"
                        + " canPublish(Eligibility doc);"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertTrue(result.success(), result.describe());
        assertTrue(result.generated("fx.DocsRuntime").contains("public Eligibility<fx.Eligibility, DocStatus,"
                + " DocTransition> canPublish(fx.Eligibility doc)"), result.generated("fx.DocsRuntime"));
    }

    /**
     * A check owns no identifier, so the operation it checks keeps it: no {@code SG703}. It opens no edge either,
     * and the companion executes the transition once, in the operation, and asks about it once, in the check.
     */
    @Test
    void aValidCheckCompilesAndIsNoOperation() {
        Compilation.Result result = Compilation.compileTyped(withCheck("publish(Doc doc)", "canPublish(Doc doc)"));

        assertTrue(result.success(), result.describe());
        assertEquals(List.of(), result.errors(), result.describe());
        String generated = result.generated("fx.DocsRuntime");
        assertEquals(1, occurrences(generated, "stateMachine.execute("), generated);
        assertEquals(1, occurrences(generated, "stateMachine.eligibility("), generated);
        assertEquals(1, occurrences(generated, ".action(\"publish\""), generated);
    }

    /** The check of an operation of a state that holds a sub-machine is no exit: it draws no {@code SG804}. */
    @Test
    void aCheckOfAnOperationInABoundStateDrawsNoExitIdentifierClash() {
        Compilation.Result result = Compilation.compileTyped(bound(TypedSubmachineTest.BOUND));

        assertTrue(result.success(), result.describe());
        assertEquals(List.of(), result.errors(), result.describe());
    }

    /** The exits are validated as before: the operation that executes one is the only error, its check adds none. */
    @Test
    void aCheckAddsNoExitIdentifierClashToTheOperationThatExecutesAnExit() {
        Compilation.Result result = Compilation.compileTyped(bound("""
                @DocsPages.Submachine(state = REVIEW, child = DocRefs.Current.class, parent = PageRefs.Doc.class,
                    exits = @DocsPages.Exit(on = PageStatus.DONE, transition = PUBLISH))
                """));

        assertEquals(List.of("[" + DiagnosticCode.SG804
                + "] Docs.REVIEW: PUBLISH is an exit of its sub-machine and cannot be the operation"
                + " publish"), result.errors(), result.describe());
    }

    /**
     * The compiled consumer asks the generated facade, each time over a document of its own: an eligible one, one a
     * guard refuses with its reason and the guard's name, one whose state has no edge for the identifier and answers
     * with the reason of its policy, and a null entity and a null input, each refused with a
     * {@code NullPointerException} that names its parameter. No check changes a state or runs an action.
     */
    @Test
    void consumerSeesEachVerdictThroughTheFacade() throws Exception {
        Compilation.Result result = Compilation.compileTyped(verdicts());

        assertTrue(result.success(), result.describe());
        assertEquals(List.of(
                "eligible DRAFT PUBLISH of the same entity=true",
                "ineligible GUARD [not accepted] accepted DRAFT of the same entity=true",
                "ineligible NO_TRANSITION [a review decides] - REVIEW of the same entity=true",
                "NPE doc",
                "NPE value",
                "DRAFT REVIEW ran=false"), result.load("fx.Consumer").getMethod("run").invoke(null));
    }

    /**
     * The minimal fixture with {@code operation} in place of its own and {@code check} declared after it, the check
     * of {@code PUBLISH}, over the payload types an operation may take. The facade names the result in full, as it
     * would import it.
     */
    static Map<String, String> withCheck(String operation, String check) {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("external.Verdict", "package external; public record Verdict(boolean accepted) { }");
        sources.put("external.Types", "package external; public final class Types { public record Nested(int n) { } }");
        sources.put("fx.Docs", sources.get("fx.Docs")
                .replace("import io.github.graphmaquina.api.Machine;",
                        "import io.github.graphmaquina.api.Eligibility;\nimport io.github.graphmaquina.api.Machine;")
                .replace("Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);\n",
                        "Outcome<Doc, DocStatus, DocTransition> " + operation + ";\n"
                                + "    @DocsGraph.Check(id = PUBLISH)\n    " + ELIGIBILITY + " " + check + ";\n"));
        return sources;
    }

    /** The fixture of {@link #primitiveInputIsPassedBoxedWithoutNullCheck}: an operation and a check over an int. */
    private static Map<String, String> primitive() {
        Map<String, String> sources = withCheck("publish(Doc doc, int value)", "canPublish(Doc doc, int value)");
        sources.put("fx.Docs", sources.get("fx.Docs").replace("action = DocActionsRefs.Publish.class)",
                "action = DocActionsRefs.Publish.class, guards = DocActionsRefs.Observe.class)"));
        sources.put("fx.DocActions", INPUT_HOST);
        sources.put("fx.Consumer", INPUT_CONSUMER);
        return sources;
    }

    /** Three states, an operation with a guard that reads a {@code Verdict}, its check, and a consumer. */
    private static Map<String, String> verdicts() {
        Map<String, String> sources = TypedFixtures.minimal();
        sources.put("external.Verdict", "package external; public record Verdict(boolean accepted) { }");
        sources.put("fx.DocStatus", "package fx;\npublic enum DocStatus { DRAFT, REVIEW, PUBLISHED }\n");
        sources.put("fx.DocTransition", "package fx;\npublic enum DocTransition { SUBMIT, APPROVE, PUBLISH }\n");
        sources.put("fx.DocActions", VERDICT_HOST);
        sources.put("fx.Docs", VERDICT_DOCS);
        sources.put("fx.Consumer", VERDICT_CONSUMER);
        return sources;
    }

    /**
     * The sub-machine fixture with {@code header} as its sub-machine and a check of {@code PUBLISH} after the
     * operation that executes it.
     */
    private static Map<String, String> bound(String header) {
        Map<String, String> sources = TypedSubmachineTest.bound(header);
        String operation = "Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);\n";
        sources.put("fx.Docs", sources.get("fx.Docs").replace(operation, operation
                + "    @DocsGraph.Check(id = PUBLISH)\n    io.github.graphmaquina.api." + ELIGIBILITY
                + " canPublish(Doc doc);\n"));
        return sources;
    }

    private static int occurrences(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }
}
