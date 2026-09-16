package io.github.graphmaquina.dsl;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.RETOUCH;
import static io.github.graphmaquina.fixtures.DocTransition.SEND_BACK;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
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
import io.github.graphmaquina.fixtures.DocTransition;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Structural validation of a machine as the DSL assembles it: exits, reachability, cycles, routes and watches. */
class MachineValidationTest {

    static final class Publish implements TransitionAction<Doc, DocStatus, DocTransition> {
        @Override public ActionOutcome process(TransitionContext<Doc, DocStatus, DocTransition> ctx) {
            return ActionOutcome.ok();
        }
    }

    private static GraphBuilder<Doc, DocStatus, DocTransition> policies(GraphBuilder<Doc, DocStatus, DocTransition> builder) {
        return builder.statePolicy(DRAFT, "a draft is submitted or discarded")
                .statePolicy(REVIEW, "in review it is published or sent back")
                .statePolicy(PUBLISHED, "published only archives");
    }

    private static GraphBuilder<Doc, DocStatus, DocTransition> base() {
        return policies(StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW))
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).action(new Publish()).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition();
    }

    private static InvalidGraphException failing(GraphBuilder<Doc, DocStatus, DocTransition> builder) {
        return assertThrows(InvalidGraphException.class, builder::build);
    }

    @Test
    void validBaseGraphStillBuilds() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = base().build();
        assertEquals(DRAFT, graph.initial());
        assertEquals(4, graph.transitions().size());
    }

    @Test
    void sg202StateWithoutExitMustBeTerminalAndTerminalMustNotExit() {
        InvalidGraphException notMarked = failing(policies(StateGraph.define(DOCS).initial(DRAFT).allowsCycle(REVIEW))
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).action(new Publish()).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition());
        assertEquals(List.of("SG202", "SG701"), notMarked.codes());
        assertTrue(notMarked.problems().get(0).message().startsWith("ARCHIVED"));

        InvalidGraphException marked = failing(policies(StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).terminal(PUBLISHED).allowsCycle(REVIEW))
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).action(new Publish()).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition());
        assertEquals(List.of("SG202"), marked.codes());
        assertTrue(marked.problems().get(0).message().contains("PUBLISHED is terminal but declares [ARCHIVE]"));
    }

    @Test
    void sg203UnreachableState() {
        InvalidGraphException error = failing(policies(StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).terminal(PUBLISHED).allowsCycle(REVIEW))
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition());
        assertEquals(List.of("SG202", "SG203", "SG203"), error.codes());
        assertTrue(error.problems().get(1).message().contains("PUBLISHED is unreachable from DRAFT"));
        assertTrue(error.problems().get(2).message().contains("ARCHIVED is unreachable from DRAFT"));
    }

    @Test
    void sg204CycleWithoutAnyPermission() {
        InvalidGraphException cycle = failing(policies(StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED))
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).action(new Publish()).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition());
        assertEquals(List.of("SG204"), cycle.codes());
        assertTrue(cycle.problems().get(0).message().contains("[DRAFT, REVIEW, DRAFT]"));
    }

    @Test
    void sg204RejectsTheCyclesLeftBehindByAPartialPermission() {
        InvalidGraphException error = failing(policies(StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(DRAFT))
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).action(new Publish()).endTransition()
                .transition(RETOUCH, PUBLISHED, REVIEW).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition());
        assertEquals(List.of("SG204"), error.codes());
        assertTrue(error.problems().get(0).message().contains("[REVIEW, PUBLISHED, REVIEW]"));
    }

    @Test
    void anExplicitSelfLoopNeedsItsOwnPermission() {
        InvalidGraphException error = failing(policies(StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED))
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(RETOUCH, REVIEW, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).action(new Publish()).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition());
        assertEquals(List.of("SG204"), error.codes());
        assertTrue(error.problems().get(0).message().contains("[REVIEW, REVIEW]"));

        GraphDefinition<Doc, DocStatus, DocTransition> allowed =
                policies(StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW))
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(RETOUCH, REVIEW, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).action(new Publish()).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        assertEquals(REVIEW, allowed.findTransition(REVIEW, RETOUCH).orElseThrow().to());
    }

    @Test
    void aTerminalRejectsEvenItsOwnSelfLoop() {
        InvalidGraphException error = failing(policies(StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW).allowsCycle(ARCHIVED))
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).action(new Publish()).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .transition(RETOUCH, ARCHIVED, ARCHIVED).endTransition());
        assertEquals(List.of("SG202"), error.codes());
        assertTrue(error.problems().get(0).message().contains("ARCHIVED is terminal but declares [RETOUCH]"));
    }

    @Test
    void aStateWhoseOnlyExitIsItsSelfLoopStaysActive() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph =
                policies(StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED).allowsCycle(PUBLISHED))
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).action(new Publish()).endTransition()
                .transition(RETOUCH, PUBLISHED, PUBLISHED).endTransition()
                .build();
        assertEquals(List.of(PUBLISHED), List.of(graph.outgoingFrom(PUBLISHED).get(0).to()));
        assertTrue(graph.terminals().contains(ARCHIVED));
    }

    @Test
    void sg205DeadAllowsCycle() {
        InvalidGraphException error = failing(policies(StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(PUBLISHED))
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).action(new Publish()).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition());
        assertEquals(List.of("SG205"), error.codes());
        assertTrue(error.problems().get(0).message().contains("unused cycle permission PUBLISHED"));
    }

    @Test
    void sg301OnFailureMustNameAnotherTransitionLeavingTheSameState() {
        InvalidGraphException sameKey = failing(policies(StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW))
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).action(new Publish()).onFailure(PUBLISH).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition());
        assertEquals(List.of("SG301"), sameKey.codes());

        InvalidGraphException missing = failing(policies(StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW))
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).action(new Publish()).onFailure(ARCHIVE).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition());
        assertEquals(List.of("SG301"), missing.codes());
        assertTrue(missing.problems().get(0).message().contains("no transition ARCHIVE leaves REVIEW"));

        GraphDefinition<Doc, DocStatus, DocTransition> ok = policies(StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW))
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).action(new Publish()).onFailure(SEND_BACK).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        assertEquals(Optional.of(SEND_BACK), ok.findTransition(REVIEW, PUBLISH).orElseThrow().onFailure());
    }

    /** The identity removes the old ambiguity: another edge may legitimately reach the very same destination. */
    @Test
    void aFailureRouteMayShareTheDestinationOfTheTransitionItRecovers() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = policies(StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW))
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).action(new Publish()).onFailure(RETOUCH).endTransition()
                .transition(RETOUCH, REVIEW, PUBLISHED).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        assertEquals(PUBLISHED, graph.findTransition(REVIEW, RETOUCH).orElseThrow().to());
    }

    @Test
    void sg401AndSg402OnWatches() {
        InvalidGraphException noSuchTransition = failing(policies(StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW))
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).action(new Publish()).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(DRAFT, DOCS, (Doc d) -> List.of(), (Doc d) -> Optional.empty())
                    .when(WatchCondition.allIn(REVIEW)).execute(PUBLISH)
                    .endWatch());
        assertEquals(List.of("SG401"), noSuchTransition.codes());

        InvalidGraphException onTerminal = failing(policies(StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW))
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).action(new Publish()).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(ARCHIVED, DOCS, (Doc d) -> List.of(), (Doc d) -> Optional.empty())
                    .when(WatchCondition.allIn(REVIEW)).execute(ARCHIVE)
                    .endWatch());
        assertEquals(List.of("SG401", "SG402"), onTerminal.codes());
    }

    @Test
    void sg701EveryStateNeedsAPolicyAndSg702ForbidsRejectingWhatItDeclares() {
        InvalidGraphException missing = failing(StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).action(new Publish()).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition());
        assertEquals(List.of("SG701", "SG701"), missing.codes());
        assertTrue(missing.problems().get(0).message().contains("REVIEW declares no rejection policy"));

        InvalidGraphException conflict = failing(policies(StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED))
                .reject(REVIEW, PUBLISH, "not yet")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).action(new Publish()).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition());
        assertEquals(List.of("SG702"), conflict.codes());
        assertTrue(conflict.problems().get(0).message().contains("REVIEW both declares and rejects PUBLISH"));
    }
}
