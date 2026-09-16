package io.github.graphmaquina.dsl;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.RETOUCH;
import static io.github.graphmaquina.fixtures.DocTransition.SEND_BACK;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.graph.DirectedEdge;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GraphBuilderTest {

    static final class Publish implements TransitionAction<Doc, DocStatus, DocTransition> {
        @Override
        public ActionOutcome process(TransitionContext<Doc, DocStatus, DocTransition> ctx) {
            return ActionOutcome.ok();
        }
    }

    static GraphBuilder<Doc, DocStatus, DocTransition> base() {
        return StateGraph.define(DOCS)
                .initial(DRAFT)
                .terminal(ARCHIVED)
                .allowsCycle(REVIEW)
                .statePolicy(DRAFT, "a draft only goes to review")
                .statePolicy(REVIEW, "in review it is only published or sent back")
                .statePolicy(PUBLISHED, "published only archives")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).action(new Publish()).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition();
    }

    @Test
    void buildsOnlyTheDeclaredEdges() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = base().build();
        assertEquals(DRAFT, graph.initial());
        assertEquals(Set.of(ARCHIVED), graph.terminals());
        assertEquals(4, graph.transitions().size());
        assertEquals(TransitionKind.MOVE, graph.findTransition(DRAFT, SUBMIT).orElseThrow().kind());
        assertEquals(TransitionKind.ACTION, graph.findTransition(REVIEW, PUBLISH).orElseThrow().kind());
        assertEquals("Publish", graph.findTransition(REVIEW, PUBLISH).orElseThrow().action().orElseThrow().name());
        assertTrue(graph.findTransition(DRAFT, PUBLISH).isEmpty());
        assertEquals("a draft only goes to review", graph.policy(DRAFT).reasonFor(PUBLISH));
        assertEquals(List.of(PUBLISH, SEND_BACK),
                graph.outgoingFrom(REVIEW).stream()
                        .map((TransitionSpec<Doc, DocStatus, DocTransition> spec) -> spec.transition()).toList());
        assertEquals(List.of(), graph.outgoingFrom(ARCHIVED));
        assertFalse(graph.hasAsync());
        assertEquals(Optional.empty(), graph.watch(REVIEW));
    }

    @Test
    void theGraphHoldsEveryStateAndEveryDeclaredEdge() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = base().build();
        assertEquals(List.of(DRAFT, REVIEW, PUBLISHED, ARCHIVED), graph.graph().nodes());
        assertEquals(4, graph.graph().edges().size());
        assertEquals(List.of(new DirectedEdge<>(DRAFT, SUBMIT, REVIEW)), graph.graph().outgoing(DRAFT));
        assertEquals(Set.of(REVIEW), graph.graph().topology().successors(DRAFT));
    }

    /** Public order is origin index, then identifier index, then destination index. */
    @Test
    void edgesAreOrderedByOriginIdentifierAndDestination() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(RETOUCH, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .build();
        assertEquals(List.of("DRAFT/SUBMIT", "DRAFT/RETOUCH", "REVIEW/PUBLISH", "REVIEW/SEND_BACK", "PUBLISHED/ARCHIVE"),
                graph.transitions().stream()
                        .map((TransitionSpec<Doc, DocStatus, DocTransition> spec) -> spec.key().toString()).toList());
    }

    @Test
    void toStringSummarisesWithoutMaterialisingPairs() {
        assertEquals("Doc/DocStatus[4 states, 4 transitions]", base().build().toString());
    }

    @Test
    void explicitNameWins() {
        GraphDefinition<Doc, DocStatus, DocTransition> named = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED)
                    .action("Publish", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ActionOutcome.ok())
                    .endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        assertEquals("Publish", named.findTransition(REVIEW, PUBLISH).orElseThrow().action().orElseThrow().name());
    }

    @Test
    void buildIsSingleUse() {
        GraphBuilder<Doc, DocStatus, DocTransition> builder = base();
        builder.build();
        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void rejectsNullArguments() {
        NullPointerException error = assertThrows(NullPointerException.class, () -> StateGraph.define(null));
        assertTrue(error.getMessage().startsWith("key"), error.getMessage());
        assertTrue(assertThrows(NullPointerException.class, () -> base().transition(SUBMIT, DRAFT, null))
                .getMessage().startsWith("to"));
        assertTrue(assertThrows(NullPointerException.class, () -> base().statePolicy(DRAFT, null))
                .getMessage().startsWith("otherwise"));
        assertTrue(assertThrows(NullPointerException.class, () -> base().reject(DRAFT, null, "why"))
                .getMessage().startsWith("transition"));
    }

    @Test
    void afterCommitIsStoredOnEveryKindOfEdge() {
        List<String> seen = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .afterCommit("noteReview", (Doc doc) -> seen.add("review")).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED)
                    .action("publish", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ActionOutcome.ok())
                    .afterCommit("notePublished", (Doc doc) -> seen.add("published")).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        assertEquals("noteReview", graph.findTransition(DRAFT, SUBMIT).orElseThrow().afterCommit().orElseThrow().name());
        assertEquals("notePublished",
                graph.findTransition(REVIEW, PUBLISH).orElseThrow().afterCommit().orElseThrow().name());
        assertTrue(graph.findTransition(PUBLISHED, ARCHIVE).orElseThrow().afterCommit().isEmpty());
        graph.findTransition(DRAFT, SUBMIT).orElseThrow().afterCommit().orElseThrow()
                .run(new Doc("1", DRAFT), new CommitContext<>(DRAFT, SUBMIT, REVIEW, TransitionKind.MOVE, false));
        assertEquals(List.of("review"), seen);
    }

    @Test
    void afterCommitTwiceOnTheSameEdgeIsAnError() {
        assertThrows(IllegalStateException.class, () -> StateGraph.define(DOCS)
                .initial(DRAFT)
                .transition(SUBMIT, DRAFT, REVIEW).afterCommit("a", (Doc doc) -> { })
                .afterCommit("b", (Doc doc) -> { }));
    }
}
