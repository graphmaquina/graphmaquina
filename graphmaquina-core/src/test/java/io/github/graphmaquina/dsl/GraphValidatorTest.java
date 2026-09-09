package io.github.graphmaquina.dsl;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.WatchCondition;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GraphValidatorTest {

    static final class Publish implements TransitionAction<Doc, DocStatus> {
        @Override public ActionOutcome process(TransitionContext<Doc, DocStatus> ctx) { return ActionOutcome.ok(); }
    }

    private static InvalidGraphException failing(GraphBuilder<Doc, DocStatus> builder) {
        return assertThrows(InvalidGraphException.class, builder::build);
    }

    @Test
    void validBaseGraphStillBuilds() {
        GraphDefinition<Doc, DocStatus> graph = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
                .from(DRAFT).to(REVIEW).move().stay().forbidOthers("x")
                .from(REVIEW).to(PUBLISHED).action(new Publish()).to(DRAFT).move().stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x")
                .build();
        assertEquals(DRAFT, graph.initial());
    }

    @Test
    void sg202StateWithoutExitMustBeTerminalAndTerminalMustNotExit() {
        InvalidGraphException notMarked = failing(StateGraph.define(DOCS)
                .initial(DRAFT).allowsCycle(REVIEW)
                .from(DRAFT).to(REVIEW).move().stay().forbidOthers("x")
                .from(REVIEW).to(PUBLISHED).action(new Publish()).to(DRAFT).move().stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x"));
        assertEquals(List.of("SG202"), notMarked.codes());
        assertTrue(notMarked.problems().get(0).message().startsWith("ARCHIVED"));

        InvalidGraphException marked = failing(StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).terminal(PUBLISHED).allowsCycle(REVIEW)
                .from(DRAFT).to(REVIEW).move().stay().forbidOthers("x")
                .from(REVIEW).to(PUBLISHED).action(new Publish()).to(DRAFT).move().stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x"));
        assertEquals(List.of("SG202"), marked.codes());
        assertTrue(marked.problems().get(0).message().contains("PUBLISHED is terminal but exits to [ARCHIVED]"));
    }

    @Test
    void sg203UnreachableState() {
        InvalidGraphException error = failing(StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).terminal(PUBLISHED).allowsCycle(REVIEW)
                .from(DRAFT).to(REVIEW).move().stay().forbidOthers("x")
                .from(REVIEW).to(DRAFT).move().stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x"));
        assertEquals(List.of("SG202", "SG203", "SG203"), error.codes());
        assertTrue(error.problems().get(1).message().contains("PUBLISHED is unreachable from DRAFT"));
        assertTrue(error.problems().get(2).message().contains("ARCHIVED is unreachable from DRAFT"));
    }

    @Test
    void sg204CycleWithoutAllowsCycleAndSelfLoopAction() {
        InvalidGraphException cycle = failing(StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .from(DRAFT).to(REVIEW).move().stay().forbidOthers("x")
                .from(REVIEW).to(PUBLISHED).action(new Publish()).to(DRAFT).move().stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x"));
        assertEquals(List.of("SG204"), cycle.codes());
        assertTrue(cycle.problems().get(0).message().contains("DRAFT → REVIEW → DRAFT"));

        InvalidGraphException selfLoop = failing(StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .from(DRAFT).to(REVIEW).move().to(DRAFT).action("Retouch", (TransitionContext<Doc, DocStatus> ctx) -> ActionOutcome.ok())
                            .forbidOthers("x")
                .from(REVIEW).to(PUBLISHED).action(new Publish()).stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x"));
        assertEquals(List.of("SG204"), selfLoop.codes());
        assertTrue(selfLoop.problems().get(0).message().contains("DRAFT → DRAFT"));
    }

    @Test
    void sg205DeadAllowsCycle() {
        InvalidGraphException error = failing(StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(PUBLISHED)
                .from(DRAFT).to(REVIEW).move().stay().forbidOthers("x")
                .from(REVIEW).to(PUBLISHED).action(new Publish()).stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x"));
        assertEquals(List.of("SG205"), error.codes());
    }

    @Test
    void sg301OnFailureMustPointToATransitableCellOtherThanTo() {
        InvalidGraphException sameAsTo = failing(StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
                .from(DRAFT).to(REVIEW).move().stay().forbidOthers("x")
                .from(REVIEW).to(PUBLISHED).action(new Publish()).onFailure(PUBLISHED).to(DRAFT).move().stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x"));
        assertEquals(List.of("SG301"), sameAsTo.codes());

        InvalidGraphException toForbid = failing(StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
                .from(DRAFT).to(REVIEW).move().stay().forbidOthers("x")
                .from(REVIEW).to(PUBLISHED).action(new Publish()).onFailure(ARCHIVED).to(DRAFT).move().stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x"));
        assertEquals(List.of("SG301"), toForbid.codes());
        assertTrue(toForbid.problems().get(0).message().contains("REVIEW → ARCHIVED is FORBID"));

        GraphDefinition<Doc, DocStatus> ok = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
                .from(DRAFT).to(REVIEW).move().stay().forbidOthers("x")
                .from(REVIEW).to(PUBLISHED).action(new Publish()).onFailure(DRAFT).to(DRAFT).move().stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x")
                .build();
        assertEquals(Optional.of(DRAFT), ok.edge(REVIEW, PUBLISHED).onFailure());
    }

    @Test
    void sg401AndSg402OnWatches() {
        InvalidGraphException toForbid = failing(StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
                .from(DRAFT).to(REVIEW).move().stay()
                    .watches(DOCS, (Doc d) -> List.of(), (Doc d) -> Optional.empty())
                        .when(WatchCondition.allIn(REVIEW)).goTo(PUBLISHED)
                    .forbidOthers("x")
                .from(REVIEW).to(PUBLISHED).action(new Publish()).to(DRAFT).move().stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay().forbidOthers("x"));
        assertEquals(List.of("SG401"), toForbid.codes());

        InvalidGraphException onTerminal = failing(StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
                .from(DRAFT).to(REVIEW).move().stay().forbidOthers("x")
                .from(REVIEW).to(PUBLISHED).action(new Publish()).to(DRAFT).move().stay().forbidOthers("x")
                .from(PUBLISHED).to(ARCHIVED).move().stay().forbidOthers("x")
                .from(ARCHIVED).stay()
                    .watches(DOCS, (Doc d) -> List.of(), (Doc d) -> Optional.empty())
                        .when(WatchCondition.allIn(REVIEW)).goTo(ARCHIVED)
                    .forbidOthers("x"));
        assertEquals(List.of("SG401", "SG402"), onTerminal.codes());
    }
}
