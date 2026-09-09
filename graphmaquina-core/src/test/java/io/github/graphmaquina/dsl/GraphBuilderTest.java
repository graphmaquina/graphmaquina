package io.github.graphmaquina.dsl;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.EdgeKind;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GraphBuilderTest {

    static final class Publish implements TransitionAction<Doc, DocStatus> {
        @Override
        public ActionOutcome process(TransitionContext<Doc, DocStatus> ctx) {
            return ActionOutcome.ok();
        }
    }

    static GraphBuilder<Doc, DocStatus> base() {
        return StateGraph.define(DOCS)
                .initial(DRAFT)
                .terminal(ARCHIVED)
                .allowsCycle(REVIEW)
                .from(DRAFT).to(REVIEW).move().stay().forbidOthers("a draft only goes to review")
                .from(REVIEW).to(PUBLISHED).action(new Publish()).to(DRAFT).move().stay()
                             .forbidOthers("in review it is only published or sent back")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("published only archives")
                .from(ARCHIVED).stay().forbidOthers("archived is final");
    }

    @Test
    void buildsTheCompleteMatrix() {
        GraphDefinition<Doc, DocStatus> graph = base().build();
        assertEquals(DRAFT, graph.initial());
        assertEquals(Set.of(ARCHIVED), graph.terminals());
        assertEquals(EdgeKind.MOVE, graph.edge(DRAFT, REVIEW).kind());
        assertEquals(EdgeKind.STAY, graph.edge(DRAFT, DRAFT).kind());
        assertEquals(EdgeKind.ACTION, graph.edge(REVIEW, PUBLISHED).kind());
        assertEquals("Publish", graph.edge(REVIEW, PUBLISHED).action().orElseThrow().name());
        assertEquals(EdgeKind.FORBID, graph.edge(DRAFT, PUBLISHED).kind());
        assertEquals("a draft only goes to review", graph.edge(DRAFT, PUBLISHED).reason());
        assertEquals(Set.of(PUBLISHED, DRAFT), graph.allowedFrom(REVIEW));
        assertEquals(Set.of(), graph.allowedFrom(ARCHIVED));
        assertFalse(graph.hasAsync());
        assertEquals(Optional.empty(), graph.watch(REVIEW));
    }

    @Test
    void graphHasOnlyMovingArcs() {
        GraphDefinition<Doc, DocStatus> graph = base().build();
        assertEquals(4, graph.graph().edges().size());
        assertEquals(Set.of(REVIEW), graph.graph().successors(DRAFT));
    }

    @Test
    void rendersTheMatrix() {
        String expected = String.join("\n",
                "          | DRAFT     REVIEW    PUBLISHED ARCHIVED",
                "DRAFT     | stay      move      -         -",
                "REVIEW    | move      stay      Publish   -",
                "PUBLISHED | -         -         stay      move",
                "ARCHIVED  | -         -         -         stay",
                "");
        assertEquals(expected, base().build().render());
        assertEquals(expected, base().build().toString());
    }

    @Test
    void explicitNameWinsAndLambdaWithoutNameIsSG106() {
        GraphDefinition<Doc, DocStatus> named = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
                .from(DRAFT).to(REVIEW).move().stay().forbidOthers("x")
                .from(REVIEW).to(PUBLISHED).action("Publish", (TransitionContext<Doc, DocStatus> ctx) -> ActionOutcome.ok())
                             .to(DRAFT).move().stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x")
                .build();
        assertEquals("Publish", named.edge(REVIEW, PUBLISHED).action().orElseThrow().name());

        InvalidGraphException error = assertThrows(InvalidGraphException.class, () -> StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
                .from(DRAFT).to(REVIEW).move().stay().forbidOthers("x")
                .from(REVIEW).to(PUBLISHED).action((TransitionContext<Doc, DocStatus> ctx) -> ActionOutcome.ok())
                             .to(DRAFT).move().stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x")
                .build());
        assertEquals(java.util.List.of("SG106"), error.codes());
    }

    @Test
    void buildIsSingleUse() {
        GraphBuilder<Doc, DocStatus> builder = base();
        builder.build();
        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void rejectsNullArguments() {
        NullPointerException error = assertThrows(NullPointerException.class, () -> StateGraph.define(null));
        assertTrue(error.getMessage().startsWith("key"), error.getMessage());
        assertTrue(assertThrows(NullPointerException.class, () -> base().from(DRAFT).to(null)).getMessage().startsWith("target"));
        assertTrue(assertThrows(NullPointerException.class, () -> base().from(DRAFT).forbidOthers(null)).getMessage().startsWith("reason"));
    }

    @Test
    void afterCommitIsStoredOnActionAndMoveCells() {
        List<String> seen = new ArrayList<>();
        GraphDefinition<Doc, DocStatus> graph = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .from(DRAFT).to(REVIEW).move().afterCommit("noteReview", (Doc doc) -> seen.add("review")).stay().forbidOthers("x")
                .from(REVIEW).to(PUBLISHED).action("publish", (TransitionContext<Doc, DocStatus> ctx) -> ActionOutcome.ok())
                        .afterCommit("notePublished", (Doc doc) -> seen.add("published")).stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x")
                .build();
        assertEquals("noteReview", graph.edge(DRAFT, REVIEW).afterCommit().orElseThrow().name());
        assertEquals("notePublished", graph.edge(REVIEW, PUBLISHED).afterCommit().orElseThrow().name());
        assertTrue(graph.edge(PUBLISHED, ARCHIVED).afterCommit().isEmpty());
        graph.edge(DRAFT, REVIEW).afterCommit().orElseThrow().hook().accept(new Doc("1", DRAFT));
        assertEquals(List.of("review"), seen);
    }

    @Test
    void afterCommitTwiceOnTheSameCellIsAnError() {
        assertThrows(IllegalStateException.class, () -> StateGraph.define(DOCS)
                .initial(DRAFT)
                .from(DRAFT).to(REVIEW).move().afterCommit("a", (Doc doc) -> { }).afterCommit("b", (Doc doc) -> { }));
    }
}
