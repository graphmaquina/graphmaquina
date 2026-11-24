package io.github.graphmaquina.api;

import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SEND_BACK;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocMachines;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code isApplied()} is true for an applied outcome only, and {@code refusedByGuard()} of a choice check that admits
 * no candidate says whether a guard refused at least one of them, rather than the state's policy alone.
 */
class OutcomeConveniencesTest {

    private static StateMachine<Doc, DocStatus, DocTransition> machine() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT)
                .terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded")
                .statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW)
                .guard("ready", (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                        "held".equals(ctx.entity().id()) ? GuardResult.reject("the doc is held") : GuardResult.pass())
                .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED)
                .action("fail", (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                        ActionOutcome.failed("the archive is closed"))
                .onFailure(SEND_BACK)
                .endTransition()
                .transition(SEND_BACK, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
        return DocMachines.registry(graph).of(DOCS);
    }

    @Test
    void isAppliedIsTrueOnlyForApplied() {
        StateMachine<Doc, DocStatus, DocTransition> machine = machine();

        assertTrue(machine.execute(new Doc("1", DRAFT), SUBMIT).isApplied());
        assertFalse(machine.execute(new Doc("held", DRAFT), SUBMIT).isApplied());
        assertFalse(machine.execute(new Doc("2", DRAFT), DISCARD).isApplied());
        assertFalse(machine.reevaluate(new Doc("3", DRAFT)).isApplied());
    }

    @Test
    void theFourStatusesAreTheOnesTheVariantsGive() {
        StateMachine<Doc, DocStatus, DocTransition> machine = machine();

        assertEquals(OutcomeStatus.REJECTED, machine.execute(new Doc("held", DRAFT), SUBMIT).status());
        assertEquals(OutcomeStatus.FAILED, machine.execute(new Doc("2", DRAFT), DISCARD).status());
        assertEquals(OutcomeStatus.IGNORED, machine.reevaluate(new Doc("3", DRAFT)).status());
    }

    @Test
    void refusedByGuardSeesAGuardRejection() {
        ChoiceEligibility.NoneAdmitted<Doc, DocStatus, DocTransition> none = machine()
                .chooseEligibility(new Doc("held", DRAFT), List.of(PUBLISH, SUBMIT)).asNoneAdmitted().orElseThrow();

        assertTrue(none.refusedByGuard());
    }

    @Test
    void refusedByGuardIsFalseForPolicyOnly() {
        ChoiceEligibility.NoneAdmitted<Doc, DocStatus, DocTransition> none = machine()
                .chooseEligibility(new Doc("1", DRAFT), List.of(PUBLISH, ARCHIVE)).asNoneAdmitted().orElseThrow();

        assertFalse(none.refusedByGuard());
    }
}
