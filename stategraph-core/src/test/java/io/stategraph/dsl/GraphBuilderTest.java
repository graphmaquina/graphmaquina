package io.stategraph.dsl;

import static io.stategraph.fixtures.DocStatus.ARCHIVED;
import static io.stategraph.fixtures.DocStatus.DRAFT;
import static io.stategraph.fixtures.DocStatus.PUBLISHED;
import static io.stategraph.fixtures.DocStatus.REVIEW;
import static io.stategraph.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.stategraph.api.ActionOutcome;
import io.stategraph.api.EdgeKind;
import io.stategraph.api.GraphDefinition;
import io.stategraph.api.TransitionAction;
import io.stategraph.api.TransitionContext;
import io.stategraph.error.InvalidGraphException;
import io.stategraph.fixtures.Doc;
import io.stategraph.fixtures.DocStatus;
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
        assertEquals("key", error.getMessage());
        assertEquals("target", assertThrows(NullPointerException.class, () -> base().from(DRAFT).to(null)).getMessage());
        assertEquals("reason", assertThrows(NullPointerException.class, () -> base().from(DRAFT).forbidOthers(null)).getMessage());
    }
}
