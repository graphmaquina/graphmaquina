package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An after-commit hook takes the entity alone or the entity and a {@code CommitContext}. The companion registers
 * the first with {@code afterCommit} and the second with {@code afterCommitWith}, and the second receives the
 * origin, the destination, the kind and the terminality of each committed transition. A second parameter that is no
 * {@code CommitContext}, a result that is not void and extra parameters are refused as {@code SG604} where the hook
 * is exported; a context of other enums, a raw or wildcard one and two marked declarations that both fit are
 * refused as {@code SG505} where it is bound.
 */
class CommitHookTest {

    private static final String CONTEXTUAL = "Doc doc, CommitContext<DocStatus, DocTransition> context";

    @Test
    void generatedHooksReceiveEachCommittedTransitionAndItsGraphTerminality() throws Exception {
        Compilation.Result result = Compilation.compileTyped(fixture(CONTEXTUAL, """
                calls.add(context.from() + ":" + context.to() + ":" + context.kind()
                        + ":" + context.isTerminal() + ":" + doc.status());
                """));

        assertTrue(result.success(), result.describe());
        assertEquals("[DRAFT:REVIEW:MOVE:false:REVIEW, REVIEW:PUBLISHED:ACTION:true:PUBLISHED]", run(result));
    }

    @Test
    void preservesTheEntityOnlySignature() throws Exception {
        Compilation.Result result = Compilation.compileTyped(fixture("Doc doc", "calls.add(doc.status().toString());"));
        assertTrue(result.success(), result.describe());
        String runtime = result.generated("fx.DocsRuntime");
        assertTrue(runtime.contains(".afterCommit(\"notified\", (doc) -> docActions.notified(doc))"), runtime);
        assertEquals("[REVIEW, PUBLISHED]", run(result));
    }

    @Test
    void deliversTheContextAfterAnAsynchronousActionCommits() throws Exception {
        Map<String, String> sources = fixture(CONTEXTUAL, "calls.add(context.to() + \":\" + context.kind() + \":\" + context.isTerminal());");
        sources.put("fx.DocActions", edit(sources.get("fx.DocActions"),
                "public ActionOutcome publish(TransitionContext<Doc, DocStatus, DocTransition> context) { return ActionOutcome.ok(); }",
                "public java.util.concurrent.CompletionStage<ActionOutcome> publish(TransitionContext<Doc, DocStatus, DocTransition> context) {"
                        + " return java.util.concurrent.CompletableFuture.completedFuture(ActionOutcome.ok()); }"));
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result.describe());
        String runtime = result.generated("fx.DocsRuntime");
        assertTrue(runtime.contains(".asyncAction(\"publish\""), runtime);
        assertEquals("[REVIEW:MOVE:false, PUBLISHED:ACTION:true]", run(result));
    }

    @Test
    void rejectsAnUnrelatedSecondParameterAtExport() {
        Compilation.Result result = Compilation.compileTyped(fixture("Doc doc, String context", ""));
        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG604"), result.describe());
    }

    @Test
    void rejectsAContextForAnotherStateEnumAtBinding() {
        Map<String, String> sources = fixture("Doc doc, CommitContext<OtherStatus, DocTransition> context", "");
        sources.put("fx.OtherStatus", "package fx; public enum OtherStatus { OPEN, CLOSED }");
        Compilation.Result result = Compilation.compileTyped(sources);
        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG505"), result.describe());
    }

    @Test
    void rejectsAnAmbiguousFamilyWithBothMarkedSignatures() {
        Map<String, String> sources = fixture(CONTEXTUAL, "");
        sources.put("fx.DocActions", edit(sources.get("fx.DocActions"), "public final class DocActions {", """
                public final class DocActions {
                    @GraphHook public void notified(Doc doc) { }
                """));
        Compilation.Result result = Compilation.compileTyped(sources);
        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG505"), result.describe());
    }

    @Test
    void resolvesAnInheritedGenericHookAgainstItsConcreteHost() throws Exception {
        Map<String, String> sources = fixture(CONTEXTUAL, "calls.add(context.to().name());");
        String base = sources.get("fx.DocActions");
        base = edit(base, "public final class DocActions {",
                "public class BaseActions<E, S extends Enum<S>, T extends Enum<T>> {");
        base = edit(base, "TransitionContext<Doc, DocStatus, DocTransition>", "TransitionContext<E, S, T>");
        base = edit(base, "Doc doc", "E doc");
        base = edit(base, "CommitContext<DocStatus, DocTransition>", "CommitContext<S, T>");
        sources.put("fx.BaseActions", base);
        sources.put("fx.DocActions", """
                package fx;
                @io.github.graphmaquina.annotation.GraphBindings
                public final class DocActions extends BaseActions<Doc, DocStatus, DocTransition> { }
                """);
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result.describe());
        assertEquals("[REVIEW, PUBLISHED]", run(result));
    }

    @Test
    void invokesAStaticHook() throws Exception {
        Map<String, String> sources = fixture(CONTEXTUAL, "calls.add(context.to().name());");
        String host = sources.get("fx.DocActions");
        host = edit(host, "public final List", "public static final List");
        host = edit(host, "public void notified", "public static void notified");
        sources.put("fx.DocActions", host);
        sources.put("fx.Probe", edit(sources.get("fx.Probe"), "actions.calls", "DocActions.calls"));
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result.describe());
        assertEquals("[REVIEW, PUBLISHED]", run(result));
    }

    @Test
    void disambiguatesTheEntityAndContextLambdaParameterNames() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        fixture(CONTEXTUAL, "calls.add(context.to().name());")
                .forEach((String name, String source) -> sources.put(name.replace("Doc", "Commit"), source.replace("Doc", "Commit")));
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result.describe());
        String runtime = result.generated("fx.CommitsRuntime");
        assertTrue(runtime.contains(".afterCommitWith(\"notified\", (commit, commit2) -> commitActions.notified(commit, commit2))"), runtime);
        assertEquals("[REVIEW, PUBLISHED]", run(result));
    }

    @Test
    void rejectsRawAndWildcardContextsAtBinding() {
        for (String parameters : new String[] {"Doc doc, CommitContext context", "Doc doc, CommitContext<?, ?> context"}) {
            Compilation.Result result = Compilation.compileTyped(fixture(parameters, ""));
            assertFalse(result.success(), result.describe());
            assertTrue(result.hasError("SG505"), result.describe());
        }
    }

    @Test
    void rejectsNonVoidAndExtraParametersAtExport() {
        Map<String, String> nonVoid = fixture(CONTEXTUAL, "return true;");
        nonVoid.put("fx.DocActions", edit(nonVoid.get("fx.DocActions"), "void notified", "boolean notified"));
        Compilation.Result returned = Compilation.compileTyped(nonVoid);
        assertFalse(returned.success(), returned.describe());
        assertTrue(returned.hasError("SG604"), returned.describe());
        Compilation.Result extra = Compilation.compileTyped(fixture(CONTEXTUAL + ", String extra", ""));
        assertFalse(extra.success(), extra.describe());
        assertTrue(extra.hasError("SG604"), extra.describe());
    }

    private static String run(Compilation.Result result) throws Exception {
        return (String) result.load("fx.Probe").getMethod("run").invoke(null);
    }

    /** Rewrites a fixture source and fails when the text to replace is no longer there, instead of silently testing something else. */
    private static String edit(String source, String target, String replacement) {
        assertTrue(source.contains(target), "fixture no longer contains: " + target);
        return source.replace(target, replacement);
    }

    private static Map<String, String> fixture(String parameters, String body) {
        Map<String, String> sources = TypedFixtures.graph("""
                @DocsGraph.Definition(initial = DRAFT, terminals = PUBLISHED,
                    state = DocRefs.Status.class, id = DocRefs.Id.class)
                @DocsGraph.StatePolicy(state = DRAFT, otherwise = "submit first")
                @DocsGraph.StatePolicy(state = REVIEW, otherwise = "publish next")
                """, """
                    @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW, afterCommit = DocActionsRefs.Notified.class)
                    Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                    @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class,
                        afterCommit = DocActionsRefs.Notified.class)
                    Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
                """);
        sources.put("fx.DocActions", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.annotation.GraphHook;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.CommitContext;
                import io.github.graphmaquina.api.TransitionContext;
                import java.util.ArrayList;
                import java.util.List;
                public final class DocActions {
                    public final List<String> calls = new ArrayList<>();
                    @GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus, DocTransition> context) { return ActionOutcome.ok(); }
                    @GraphHook public void notified(%s) { %s }
                }
                """.formatted(parameters, body));
        sources.put("fx.Probe", """
                package fx;
                import io.github.graphmaquina.api.StateMachine;
                import io.github.graphmaquina.runtime.StateMachines;
                import java.time.Duration;
                public final class Probe {
                    public static String run() {
                        DocActions actions = new DocActions();
                        StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(DocsRuntime.graph(actions))
                                .state(Doc::status, Doc::status).asyncTimeout(Duration.ofSeconds(5)).build();
                        Doc doc = new Doc();
                        machine.execute(doc, DocTransition.SUBMIT);
                        machine.execute(doc, DocTransition.PUBLISH);
                        return actions.calls.toString();
                    }
                }
                """);
        return sources;
    }
}
