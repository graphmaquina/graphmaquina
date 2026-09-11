package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommitHookTest {

    @Test
    void generatedHooksReceiveEachCommittedCellAndItsGraphTerminality() throws Exception {
        Compilation.Result result = Compilation.compileTyped(fixture("CommitContext<DocStatus> context", """
                calls.add(context.from() + ":" + context.to() + ":" + context.kind()
                        + ":" + context.isTerminal() + ":" + doc.status());
                """));

        assertTrue(result.success(), result.describe());
        assertEquals("[DRAFT:REVIEW:MOVE:false:REVIEW, REVIEW:PUBLISHED:ACTION:true:PUBLISHED]",
                result.load("fx.Probe").getMethod("run").invoke(null));
    }

    @Test
    void preservesTheEntityOnlySignature() throws Exception {
        Map<String, String> sources = fixture("CommitContext<DocStatus> context", "calls.add(doc.status().toString());");
        sources.put("fx.DocActions", sources.get("fx.DocActions").replace(", CommitContext<DocStatus> context", ""));
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result.describe());
        assertEquals("[REVIEW, PUBLISHED]", result.load("fx.Probe").getMethod("run").invoke(null));
    }

    @Test
    void rejectsAnUnrelatedSecondParameterAtExport() {
        Compilation.Result result = Compilation.compileTyped(fixture("String context", ""));
        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG604"), result.describe());
    }

    @Test
    void rejectsAContextForAnotherStateEnumAtBinding() {
        Map<String, String> sources = fixture("CommitContext<OtherStatus> context", "");
        sources.put("fx.OtherStatus", "package fx; public enum OtherStatus { OPEN, CLOSED }");
        Compilation.Result result = Compilation.compileTyped(sources);
        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG505"), result.describe());
    }

    @Test
    void rejectsAnAmbiguousFamilyWithBothMarkedSignatures() {
        Map<String, String> sources = fixture("CommitContext<DocStatus> context", "");
        sources.put("fx.DocActions", sources.get("fx.DocActions").replace(
                "public final class DocActions {", """
                public final class DocActions {
                    @GraphHook public void notified(Doc doc) { }
                """));
        Compilation.Result result = Compilation.compileTyped(sources);
        assertFalse(result.success(), result.describe());
        assertTrue(result.hasError("SG505"), result.describe());
    }

    @Test
    void resolvesAnInheritedGenericHookAgainstItsConcreteHost() throws Exception {
        Map<String, String> sources = fixture("CommitContext<DocStatus> context", "calls.add(context.to().name());");
        String base = sources.get("fx.DocActions")
                .replace("public final class DocActions {", "public class BaseActions<E, S extends Enum<S>> {")
                .replace("TransitionContext<Doc, DocStatus>", "TransitionContext<E, S>")
                .replace("Doc doc", "E doc").replace("CommitContext<DocStatus>", "CommitContext<S>");
        sources.put("fx.BaseActions", base);
        sources.put("fx.DocActions", """
                package fx;
                @io.github.graphmaquina.annotation.GraphBindings
                public final class DocActions extends BaseActions<Doc, DocStatus> { }
                """);
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result.describe());
        assertEquals("[REVIEW, PUBLISHED]", result.load("fx.Probe").getMethod("run").invoke(null));
    }

    @Test
    void invokesAStaticHook() throws Exception {
        Map<String, String> sources = fixture("CommitContext<DocStatus> context", "calls.add(context.to().name());");
        sources.put("fx.DocActions", sources.get("fx.DocActions")
                .replace("public final List", "public static final List")
                .replace("public void notified", "public static void notified"));
        sources.put("fx.Probe", sources.get("fx.Probe").replace("actions.calls", "DocActions.calls"));
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result.describe());
        assertEquals("[REVIEW, PUBLISHED]", result.load("fx.Probe").getMethod("run").invoke(null));
    }

    @Test
    void disambiguatesTheEntityAndContextLambdaParameterNames() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        fixture("CommitContext<DocStatus> context", "calls.add(context.to().name());")
                .forEach((name, source) -> sources.put(name.replace("Doc", "Commit"), source.replace("Doc", "Commit")));
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result.describe());
        assertEquals("[REVIEW, PUBLISHED]", result.load("fx.Probe").getMethod("run").invoke(null));
    }

    @Test
    void rejectsRawAndWildcardContextsAtBinding() {
        for (String parameter : new String[] {"CommitContext context", "CommitContext<?> context"}) {
            Compilation.Result result = Compilation.compileTyped(fixture(parameter, ""));
            assertFalse(result.success(), result.describe());
            assertTrue(result.hasError("SG505"), result.describe());
        }
    }

    @Test
    void rejectsNonVoidAndExtraParametersAtExport() {
        Map<String, String> nonVoid = fixture("CommitContext<DocStatus> context", "return true;");
        nonVoid.put("fx.DocActions", nonVoid.get("fx.DocActions").replace("void notified", "boolean notified"));
        Compilation.Result returned = Compilation.compileTyped(nonVoid);
        assertFalse(returned.success(), returned.describe());
        assertTrue(returned.hasError("SG604"), returned.describe());
        Compilation.Result extra = Compilation.compileTyped(fixture("CommitContext<DocStatus> context, String extra", ""));
        assertFalse(extra.success(), extra.describe());
        assertTrue(extra.hasError("SG604"), extra.describe());
    }

    private static Map<String, String> fixture(String parameter, String body) {
        Map<String, String> sources = TypedFixtures.graph("""
                @DocsGraph.Definition(initial = DRAFT, terminals = PUBLISHED,
                    state = DocRefs.Status.class, id = DocRefs.Id.class)
                @DocsGraph.Row(state = DRAFT, otherwise = "submit first")
                @DocsGraph.Row(state = REVIEW, otherwise = "publish next")
                """, """
                    @DocsGraph.Move(from = DRAFT, to = REVIEW, afterCommit = DocActionsRefs.Notified.class)
                    Outcome<Doc, DocStatus> submit(Doc doc);
                    @DocsGraph.Action(from = REVIEW, to = PUBLISHED, action = DocActionsRefs.Publish.class,
                        afterCommit = DocActionsRefs.Notified.class)
                    Outcome<Doc, DocStatus> publish(Doc doc);
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
                    @GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus> context) {
                        return ActionOutcome.ok();
                    }
                    @GraphHook public void notified(Doc doc, %s) { %s }
                }
                """.formatted(parameter, body));
        sources.put("fx.Probe", """
                package fx;
                import io.github.graphmaquina.runtime.StateMachines;
                public final class Probe {
                    public static String run() {
                        DocActions actions = new DocActions();
                        var machine = StateMachines.from(DocsRuntime.graph(actions))
                                .state(Doc::status, Doc::status).build();
                        Doc doc = new Doc();
                        machine.transitionTo(doc, DocStatus.REVIEW);
                        machine.transitionTo(doc, DocStatus.PUBLISHED);
                        return actions.calls.toString();
                    }
                }
                """);
        return sources;
    }
}
