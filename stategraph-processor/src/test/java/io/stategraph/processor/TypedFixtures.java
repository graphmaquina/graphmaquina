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
}
