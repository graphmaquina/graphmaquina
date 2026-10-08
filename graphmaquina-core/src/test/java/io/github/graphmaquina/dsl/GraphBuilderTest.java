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
import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.AsyncTransitionGuard;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionGuard;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.api.WatchCondition;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.RecoveryFixtures.PublicationFailures;
import io.github.graphmaquina.graph.DirectedEdge;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The graph that {@code StateGraph.define} builds from valid declarations (edges, states, policies and their order,
 * action names, afterCommit hooks, null arguments), and the refusals of a stage used after {@code endTransition()} or
 * {@code build()}.
 */
class GraphBuilderTest {

    /** A synchronous action that always succeeds, named after its class when no name is given. */
    static final class Publish implements TransitionAction<Doc, DocStatus, DocTransition> {
        @Override
        public ActionOutcome process(TransitionContext<Doc, DocStatus, DocTransition> ctx) {
            return ActionOutcome.ok();
        }
    }

    private static final AsyncTransitionAction<Doc, DocStatus, DocTransition> SLOW =
            (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                    CompletableFuture.completedFuture(ActionOutcome.ok());

    private static final TransitionGuard<Doc, DocStatus, DocTransition> PASS =
            (GuardContext<Doc, DocStatus, DocTransition> ctx) -> GuardResult.pass();

    private static final AsyncTransitionGuard<Doc, DocStatus, DocTransition> ASYNC_PASS =
            (GuardContext<Doc, DocStatus, DocTransition> ctx) -> CompletableFuture.completedFuture(GuardResult.pass());

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

    /** endTransition() turns the draft into its edge once: a later call on that stage could only be lost. */
    @Test
    void aClosedEdgeRefusesEveryFurtherCall() {
        GraphBuilder<Doc, DocStatus, DocTransition> builder = StateGraph.define(DOCS).initial(DRAFT);
        TransitionBuilder<Doc, DocStatus, DocTransition> submit = builder.transition(SUBMIT, DRAFT, REVIEW);
        submit.endTransition();
        IllegalStateException twice = assertThrows(IllegalStateException.class, submit::endTransition);
        assertEquals("endTransition() was already called on DRAFT -SUBMIT-> REVIEW in the graph Doc/DocStatus",
                twice.getMessage());
        List<Executable> late = List.of(
                () -> submit.action(new Publish()),
                () -> submit.action("publish", new Publish()),
                () -> submit.asyncAction(SLOW),
                () -> submit.asyncAction("slow", SLOW),
                () -> submit.guard(PASS),
                () -> submit.guard("pass", PASS),
                () -> submit.asyncGuard(ASYNC_PASS),
                () -> submit.asyncGuard("pass", ASYNC_PASS),
                () -> submit.onFailure(RETOUCH),
                () -> submit.failsOn(IOException.class),
                () -> submit.recover(PublicationFailures.Unavailable.class, RETOUCH),
                () -> submit.recover(PublicationFailures.Unavailable.class, RETOUCH, IOException.class),
                () -> submit.recover(PublicationFailures.Unavailable.class, RETOUCH, List.of(IOException.class)),
                () -> submit.timeout(Duration.ofSeconds(1)),
                () -> submit.afterCommit("audit", (Doc doc) -> { }),
                () -> submit.afterCommitWith("audit",
                        (Doc doc, CommitContext<DocStatus, DocTransition> commit) -> { }));
        for (Executable call : late) {
            assertEquals(twice.getMessage(), assertThrows(IllegalStateException.class, call).getMessage());
        }
    }

    /** build() reads every declaration once: after it, a declaration on any stage could only be lost. */
    @Test
    void everyStageRefusesDeclarationsAfterBuild() {
        GraphBuilder<Doc, DocStatus, DocTransition> builder = base();
        WatchBuilder<Doc, DocStatus, DocTransition, Doc, DocStatus, DocTransition> watch =
                builder.watch(REVIEW, DOCS, (Doc doc) -> List.of(), (Doc doc) -> Optional.empty());
        WatchRuleBuilder<Doc, DocStatus, DocTransition, Doc, DocStatus, DocTransition> rule =
                watch.when(WatchCondition.allIn(PUBLISHED));
        rule.execute(PUBLISH).endWatch();
        builder.build();
        List<Executable> onTheGraph = List.of(
                () -> builder.initial(REVIEW),
                () -> builder.terminal(PUBLISHED),
                () -> builder.allowsCycle(DRAFT),
                () -> builder.statePolicy(ARCHIVED, "archived"),
                () -> builder.reject(DRAFT, PUBLISH, "not yet"),
                () -> builder.transition(RETOUCH, DRAFT, ARCHIVED),
                () -> builder.watch(DRAFT, DOCS, (Doc doc) -> List.of(), (Doc doc) -> Optional.empty()),
                builder::build);
        for (Executable call : onTheGraph) {
            assertEquals("build() was already called on the graph Doc/DocStatus",
                    assertThrows(IllegalStateException.class, call).getMessage());
        }
        List<Executable> onTheWatch = List.of(
                () -> watch.when(WatchCondition.anyIn(ARCHIVED)),
                () -> watch.when("stalled", (Doc doc, Collection<? extends Doc> peers,
                        PeerView<Doc, DocStatus, DocTransition> view) -> true),
                () -> rule.execute(SEND_BACK));
        for (Executable call : onTheWatch) {
            assertEquals("build() was already called on the graph Doc/DocStatus, so the watch of REVIEW can no longer"
                    + " change", assertThrows(IllegalStateException.class, call).getMessage());
        }
    }

    /** A draft still open when build() fails is refused too: retrying it would only feed a spent builder. */
    @Test
    void anOpenEdgeRefusesEveryCallOnceBuildHasFailed() {
        GraphBuilder<Doc, DocStatus, DocTransition> builder = base();
        TransitionBuilder<Doc, DocStatus, DocTransition> retouch = builder.transition(RETOUCH, DRAFT, ARCHIVED);
        assertEquals(List.of("SG507"), assertThrows(InvalidGraphException.class, builder::build).codes());
        for (Executable call : List.<Executable>of(() -> retouch.guard(PASS), retouch::endTransition)) {
            assertEquals("build() was already called on the graph Doc/DocStatus, so DRAFT -RETOUCH-> ARCHIVED can no"
                    + " longer change", assertThrows(IllegalStateException.class, call).getMessage());
        }
        assertEquals("build() was already called on the graph Doc/DocStatus",
                assertThrows(IllegalStateException.class, builder::build).getMessage());
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
