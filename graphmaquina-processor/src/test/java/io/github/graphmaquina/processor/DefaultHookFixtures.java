package io.github.graphmaquina.processor;

import java.util.Map;

/** Source fixtures for the default afterCommit hook tests: a small Docs machine with parameterized hook declarations. */
final class DefaultHookFixtures {
    private DefaultHookFixtures() { }

    static Map<String, String> sources(String definitionHook, String actionHook) {
        Map<String, String> sources = TypedFixtures.graph("""
            @DocsGraph.Definition(initial = DRAFT, terminals = PUBLISHED, allowsCycle = REVIEW,
                state = DocRefs.Status.class, id = DocRefs.Id.class%s)
            @DocsGraph.StatePolicy(state = DRAFT, otherwise = "review first")
            @DocsGraph.StatePolicy(state = REVIEW, otherwise = "publish or return")
            @DocsGraph.Transition(id = RETURN, from = REVIEW, to = DRAFT)
            """.formatted(definitionHook), """
                @DocsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW)
                Outcome<Doc, DocStatus, DocTransition> submit(Doc doc);
                @DocsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED,
                    action = DocActionsRefs.Publish.class%s)
                Outcome<Doc, DocStatus, DocTransition> publish(Doc doc);
            """.formatted(actionHook));
        sources.put("fx.Recorder", """
            package fx;
            import io.github.graphmaquina.annotation.GraphHook;
            public final class Recorder {
                @GraphHook public void persist(Doc doc) { }
            }
            """);
        sources.put("fx.Library", """
            package fx;
            import io.github.graphmaquina.annotation.StateGraphs;
            import io.github.graphmaquina.api.Machines;
            @StateGraphs public interface Library extends Machines { Docs docs(); }
            """);
        return sources;
    }
}
