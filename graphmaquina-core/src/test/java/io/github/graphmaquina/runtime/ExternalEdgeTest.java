package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocMachines;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import org.junit.jupiter.api.Test;

/**
 * An external edge is one another system takes: it counts for the structure, so the state it enters is reachable,
 * while this machine never takes it, so a request or a check of its identifier answers as if the state had no edge
 * for it, with the state's policy.
 */
class ExternalEdgeTest {

    private static final String PUBLISHED_POLICY = "the archive retires a publication";

    private static GraphDefinition<Doc, DocStatus, DocTransition> graph() {
        return StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted")
                .statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, PUBLISHED_POLICY)
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).external().endTransition()
                .build();
    }

    @Test
    void anExternalEdgeMakesItsTargetReachable() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = graph();

        assertTrue(graph.findTransition(PUBLISHED, ARCHIVE).orElseThrow().external());
    }

    @Test
    void executeOfAnExternalEdgeIsNoTransition() {
        StateMachine<Doc, DocStatus, DocTransition> machine = DocMachines.registry(graph()).of(DOCS);
        Doc doc = new Doc("1", PUBLISHED);

        Outcome.Rejected<Doc, DocStatus, DocTransition> rejected =
                machine.execute(doc, ARCHIVE).asRejected().orElseThrow();

        assertEquals(RejectionKind.NO_TRANSITION, rejected.kind());
        assertEquals(PUBLISHED_POLICY, rejected.reason());
        assertEquals(PUBLISHED, doc.status());
    }

    @Test
    void aCheckOfAnExternalEdgeIsNoTransition() {
        StateMachine<Doc, DocStatus, DocTransition> machine = DocMachines.registry(graph()).of(DOCS);

        Eligibility.Ineligible<Doc, DocStatus, DocTransition> refused =
                machine.eligibility(new Doc("1", PUBLISHED), ARCHIVE).asIneligible().orElseThrow();

        assertEquals(RejectionKind.NO_TRANSITION, refused.kind());
        assertEquals(PUBLISHED_POLICY, refused.reason());
    }

    @Test
    void anEntityTheOtherSystemMovedAnswersFromItsNewState() {
        StateMachine<Doc, DocStatus, DocTransition> machine = DocMachines.registry(graph()).of(DOCS);

        Outcome.Rejected<Doc, DocStatus, DocTransition> rejected =
                machine.execute(new Doc("1", ARCHIVED), SUBMIT).asRejected().orElseThrow();

        assertEquals("terminal state ARCHIVED accepts no transitions", rejected.reason());
    }
}
