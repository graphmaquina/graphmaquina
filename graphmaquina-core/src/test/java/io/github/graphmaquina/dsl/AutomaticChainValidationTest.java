package io.github.graphmaquina.dsl;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.annotation.internal.DiagnosticCode;
import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AutomaticChain;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The {@code automaticChain} setting of the DSL over the doc machine: its default, what the definition keeps, the
 * machine that declares {@code ON_ADVANCE} without an automatic edge (SG110) and the continuing failure route of a
 * manual edge of such a machine (SG306). Every refusal is asserted as the only problem the build reports.
 */
class AutomaticChainValidationTest {

    /** A failure kind of this test's own. */
    record Declined() implements FailureKind { }

    private static final TransitionAction<Doc, DocStatus, DocTransition> SUCCEEDS =
            (TransitionContext<Doc, DocStatus, DocTransition> context) -> ActionOutcome.ok();

    /** DRAFT is initial, ARCHIVED terminal, and the three other states reject by policy. */
    private static GraphBuilder<Doc, DocStatus, DocTransition> docs() {
        return StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded")
                .statePolicy(REVIEW, "a review is published or sent back")
                .statePolicy(PUBLISHED, "a publication is archived");
    }

    /** No edge is automatic: SUBMIT takes a draft to REVIEW, PUBLISH to PUBLISHED, ARCHIVE to ARCHIVED. */
    private static GraphBuilder<Doc, DocStatus, DocTransition> allManual() {
        return docs()
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition();
    }

    /**
     * PUBLISH is automatic. SUBMIT, automatic or not, may fail into DISCARD, which leads to REVIEW, where the chain
     * goes on; the route is a recovery rule or an {@code onFailure} that continues.
     */
    private static GraphBuilder<Doc, DocStatus, DocTransition> withRoute(AutomaticChain chain,
            boolean submitIsAutomatic, boolean viaRecovery) {
        TransitionBuilder<Doc, DocStatus, DocTransition> submit = docs().automaticChain(chain)
                .transition(SUBMIT, DRAFT, REVIEW);
        if (submitIsAutomatic) {
            submit = submit.automatic();
        }
        submit = submit.action("submit", SUCCEEDS);
        submit = viaRecovery
                ? submit.recoverAndContinue(Declined.class, DISCARD)
                : submit.onFailureAndContinue(DISCARD);
        return submit.endTransition()
                .transition(DISCARD, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).automatic().endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition();
    }

    private static List<InvalidGraphException.Problem> problems(GraphBuilder<Doc, DocStatus, DocTransition> builder) {
        return assertThrows(InvalidGraphException.class, builder::build).problems();
    }

    @Test
    void theDefaultIsAfterCommit() {
        assertEquals(AutomaticChain.AFTER_COMMIT, allManual().build().automaticChain());
    }

    @Test
    void onAdvanceIsKeptOnTheDefinition() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph =
                withRoute(AutomaticChain.ON_ADVANCE, true, true).build();
        assertEquals(AutomaticChain.ON_ADVANCE, graph.automaticChain());
    }

    @Test
    void onAdvanceWithoutAnAutomaticEdgeIsRefused() {
        assertEquals(List.of(new InvalidGraphException.Problem(DiagnosticCode.SG110.name(),
                "automaticChain is ON_ADVANCE but the graph declares no automatic edge: declare one, or drop the "
                        + "setting")),
                problems(allManual().automaticChain(AutomaticChain.ON_ADVANCE)));
    }

    @Test
    void aContinuingRouteOnAManualEdgeOfAnOnAdvanceMachineIsRefused() {
        String message = "DRAFT -SUBMIT-> REVIEW: a failure route that continues the chain on a manual edge never "
                + "chains, since the machine chains only on advance";
        for (boolean viaRecovery : new boolean[] {true, false}) {
            assertEquals(List.of(new InvalidGraphException.Problem(DiagnosticCode.SG306.name(), message)),
                    problems(withRoute(AutomaticChain.ON_ADVANCE, false, viaRecovery)));
        }
    }

    @Test
    void aContinuingRouteOnAnAutomaticEdgeOfAnOnAdvanceMachineIsAccepted() {
        for (boolean viaRecovery : new boolean[] {true, false}) {
            GraphDefinition<Doc, DocStatus, DocTransition> graph =
                    withRoute(AutomaticChain.ON_ADVANCE, true, viaRecovery).build();
            assertEquals(AutomaticChain.ON_ADVANCE, graph.automaticChain());
        }
    }

    @Test
    void aContinuingRouteOnAManualEdgeOfAnAfterCommitMachineIsStillAccepted() {
        for (boolean viaRecovery : new boolean[] {true, false}) {
            GraphDefinition<Doc, DocStatus, DocTransition> graph =
                    withRoute(AutomaticChain.AFTER_COMMIT, false, viaRecovery).build();
            assertEquals(AutomaticChain.AFTER_COMMIT, graph.automaticChain());
        }
    }

    @Test
    void aSecondDeclarationThrows() {
        GraphBuilder<Doc, DocStatus, DocTransition> builder = allManual().automaticChain(AutomaticChain.AFTER_COMMIT);
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> builder.automaticChain(AutomaticChain.ON_ADVANCE));
        assertEquals("automaticChain is already declared", thrown.getMessage());
    }
}
