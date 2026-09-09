package io.stategraph.processor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Source fixtures written against the typed graph API, one entry per compilation unit. Everything lives in
 * package {@code fx}, and no entry names a generated type the pre-javac generator does not produce from these
 * very sources, so the fixture can be prepared and compiled exactly as a consumer build does.
 */
final class TypedFixtures {

    private TypedFixtures() { }

    /**
     * The smallest complete typed machine: a state enum, an entity exporting its state and its identity, an
     * action host and the machine that declares the graph with the vocabulary generated for it.
     */
    static Map<String, String> minimal() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocStatus", """
                package fx;
                public enum DocStatus { DRAFT, PUBLISHED }
                """);
        sources.put("fx.Doc", """
                package fx;
                import io.stategraph.annotation.GraphProperty;
                public final class Doc {
                    @GraphProperty private DocStatus status = DocStatus.DRAFT;
                    @GraphProperty public String id() { return "doc"; }
                    public DocStatus status() { return status; }
                    public void status(DocStatus value) { status = value; }
                }
                """);
        sources.put("fx.DocActions", """
                package fx;
                import io.stategraph.annotation.GraphAction;
                import io.stategraph.api.ActionOutcome;
                import io.stategraph.api.TransitionContext;
                public final class DocActions {
                    @GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus> context) {
                        return ActionOutcome.ok();
                    }
                }
                """);
        sources.put("fx.Docs", """
                package fx;
                import io.stategraph.api.Machine;
                import io.stategraph.api.Outcome;
                import io.stategraph.annotation.GenerateGraphApi;
                import static fx.DocStatus.*;
                @GenerateGraphApi
                @DocsGraph.Definition(initial = DRAFT, terminals = PUBLISHED,
                    state = DocRefs.Status.class, id = DocRefs.Id.class)
                @DocsGraph.Row(state = DRAFT, otherwise = "publish before finishing")
                @DocsGraph.Row(state = PUBLISHED, otherwise = "publication is final")
                public interface Docs extends Machine<Doc, DocStatus> {
                    @DocsGraph.Action(from = DRAFT, to = PUBLISHED, action = DocActionsRefs.Publish.class)
                    Outcome<Doc, DocStatus> publish(Doc doc);
                }
                """);
        return sources;
    }

    /**
     * The domain the structural fixtures vary: three states, the same entity, and one host exporting a
     * synchronous action, an asynchronous one, a guard and a hook, so a fixture only has to write its facade.
     */
    static Map<String, String> domain() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.DocStatus", """
                package fx;
                public enum DocStatus { DRAFT, REVIEW, PUBLISHED }
                """);
        sources.put("fx.Doc", """
                package fx;
                import io.stategraph.annotation.GraphProperty;
                public final class Doc {
                    @GraphProperty private DocStatus status = DocStatus.DRAFT;
                    @GraphProperty public String id() { return "doc"; }
                    public DocStatus status() { return status; }
                    public void status(DocStatus value) { status = value; }
                }
                """);
        sources.put("fx.DocActions", """
                package fx;
                import io.stategraph.annotation.GraphAction;
                import io.stategraph.annotation.GraphGuard;
                import io.stategraph.annotation.GraphHook;
                import io.stategraph.api.ActionOutcome;
                import io.stategraph.api.GuardContext;
                import io.stategraph.api.GuardResult;
                import io.stategraph.api.TransitionContext;
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.CompletionStage;
                public final class DocActions {
                    @GraphAction public ActionOutcome publish(TransitionContext<Doc, DocStatus> context) {
                        return ActionOutcome.ok();
                    }
                    @GraphAction public CompletionStage<ActionOutcome> archive(TransitionContext<Doc, DocStatus> context) {
                        return CompletableFuture.completedFuture(ActionOutcome.ok());
                    }
                    @GraphGuard public GuardResult approved(GuardContext<Doc, DocStatus> context) {
                        return GuardResult.pass();
                    }
                    @GraphHook public void notified(Doc doc) { }
                }
                """);
        return sources;
    }

    /** The domain with a facade carrying {@code header} over its declaration and {@code members} inside it. */
    static Map<String, String> graph(String header, String members) {
        Map<String, String> sources = domain();
        sources.put("fx.Docs", docs(header, members));
        return sources;
    }

    /**
     * One typed facade. The states are imported by name so the header reads as the declaration does, and the
     * vocabulary generated for it is always spelled {@code DocsGraph}.
     */
    static String docs(String header, String members) {
        return """
                package fx;
                import io.stategraph.annotation.GenerateGraphApi;
                import io.stategraph.annotation.Timeout;
                import io.stategraph.api.Machine;
                import io.stategraph.api.Outcome;
                import static fx.DocStatus.*;
                @GenerateGraphApi
                """ + header + "public interface Docs extends Machine<Doc, DocStatus> {\n" + members + "}\n";
    }
}
