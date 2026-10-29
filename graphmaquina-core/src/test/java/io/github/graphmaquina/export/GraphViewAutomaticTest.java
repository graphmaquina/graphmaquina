package io.github.graphmaquina.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.TransitionGuard;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.Keys;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The position of an automatic edge in the order of its state, when the {@code automaticFrom} of the definition is
 * written by hand and lists an edge as another instance, or does not list it at all.
 */
class GraphViewAutomaticTest {

    private static final TransitionGuard<Doc, DocStatus, DocTransition> OPEN =
            (GuardContext<Doc, DocStatus, DocTransition> ctx) -> GuardResult.pass();

    /** DRAFT holds two automatic edges, SUBMIT before DISCARD, and a manual one; REVIEW publishes. */
    private static GraphDefinition<Doc, DocStatus, DocTransition> docs() {
        return StateGraph.define(Keys.DOCS).initial(DocStatus.DRAFT).terminal(DocStatus.ARCHIVED)
                .statePolicy(DocStatus.DRAFT, "a draft is submitted or discarded")
                .statePolicy(DocStatus.REVIEW, "a review is published")
                .statePolicy(DocStatus.PUBLISHED, "a publication is archived")
                .transition(DocTransition.DISCARD, DocStatus.DRAFT, DocStatus.ARCHIVED).automatic().endTransition()
                .transition(DocTransition.SUBMIT, DocStatus.DRAFT, DocStatus.REVIEW).automatic()
                    .guard("open", OPEN).endTransition()
                .transition(DocTransition.RETOUCH, DocStatus.DRAFT, DocStatus.PUBLISHED).endTransition()
                .transition(DocTransition.PUBLISH, DocStatus.REVIEW, DocStatus.PUBLISHED).endTransition()
                .transition(DocTransition.ARCHIVE, DocStatus.PUBLISHED, DocStatus.ARCHIVED).endTransition()
                .automaticOrder(DocStatus.DRAFT, List.of(DocTransition.SUBMIT, DocTransition.DISCARD))
                .build();
    }

    /** The definition with the automatic edges of DRAFT answered by {@code listed}, whatever the graph declares. */
    @SuppressWarnings("unchecked")
    private static GraphDefinition<Doc, DocStatus, DocTransition> listing(
            GraphDefinition<Doc, DocStatus, DocTransition> real,
            List<TransitionSpec<Doc, DocStatus, DocTransition>> listed) {
        return (GraphDefinition<Doc, DocStatus, DocTransition>) Proxy.newProxyInstance(
                GraphViewAutomaticTest.class.getClassLoader(), new Class<?>[] {GraphDefinition.class},
                (Object proxy, Method method, Object[] args) -> {
                    if (method.getName().equals("automaticFrom") && args[0] == DocStatus.DRAFT) {
                        return listed;
                    }
                    return method.invoke(real, args);
                });
    }

    /** An edge equal to {@code spec} in everything but its destination, so it is another instance, not equal to it. */
    private static TransitionSpec<Doc, DocStatus, DocTransition> lookalike(
            GraphDefinition<Doc, DocStatus, DocTransition> real, DocTransition transition) {
        TransitionSpec<Doc, DocStatus, DocTransition> spec =
                real.findTransition(DocStatus.DRAFT, transition).orElseThrow();
        return new TransitionSpec<>(spec.from(), spec.transition(), DocStatus.PUBLISHED, spec.action(), spec.guards(),
                spec.onFailure(), spec.failsOn(), spec.timeout(), spec.afterCommit(), spec.recoveryRules(),
                spec.automatic(), spec.onFailureContinues());
    }

    private static TransitionView edge(GraphView view, String transition) {
        return view.transitions().stream()
                .filter((TransitionView candidate) -> candidate.from().equals("DRAFT")
                        && candidate.transition().equals(transition))
                .findFirst().orElseThrow();
    }

    @Test
    void anEdgeListedAsAnotherInstanceKeepsItsPlaceInTheOrder() {
        GraphDefinition<Doc, DocStatus, DocTransition> real = docs();
        TransitionSpec<Doc, DocStatus, DocTransition> submit = lookalike(real, DocTransition.SUBMIT);
        assertFalse(submit.equals(real.findTransition(DocStatus.DRAFT, DocTransition.SUBMIT).orElseThrow()));
        GraphView view = GraphView.of(listing(real, List.of(submit, lookalike(real, DocTransition.DISCARD))));
        assertEquals(Optional.of(1), edge(view, "SUBMIT").automaticPosition());
        assertEquals(Optional.of(2), edge(view, "DISCARD").automaticPosition());
    }

    @Test
    void anEdgeTheOrderDoesNotListGetsNoNumberAndNeverZero() {
        GraphDefinition<Doc, DocStatus, DocTransition> real = docs();
        GraphView view = GraphView.of(listing(real,
                List.of(lookalike(real, DocTransition.SUBMIT), lookalike(real, DocTransition.RETOUCH))));
        assertEquals(Optional.of(1), edge(view, "SUBMIT").automaticPosition());
        assertEquals(Optional.empty(), edge(view, "DISCARD").automaticPosition());
        assertTrue(edge(view, "DISCARD").automatic());
        String rendered = GraphvizExporter.render(view);
        assertTrue(rendered.contains("[label=\"DISCARD [auto]\"]"), rendered);
        assertFalse(rendered.contains("[auto 0]"), rendered);
    }
}
