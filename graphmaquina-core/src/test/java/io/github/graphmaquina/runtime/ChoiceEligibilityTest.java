package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocGraphs.docs;
import static io.github.graphmaquina.fixtures.DocGraphs.plain;
import static io.github.graphmaquina.fixtures.DocMachines.registry;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.ChoiceEligibility;
import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@code chooseEligibility(entity, candidates)} answers what {@code choose} would run without running anything: it
 * checks each candidate in order, as {@code eligibility} checks one, and answers {@code Chosen} at the first one
 * admitted, with the candidates refused before it, or {@code NoneAdmitted} with every refusal.
 */
class ChoiceEligibilityTest {

    /** A NO_TRANSITION refusal of {@code transition} for {@code doc} in {@code from}, for the reason given. */
    private static Eligibility.Ineligible<Doc, DocStatus, DocTransition> noEdge(Doc doc, DocStatus from,
                                                                               DocTransition transition,
                                                                               String reason) {
        return new Eligibility.Ineligible<>(doc, from, transition, reason, RejectionKind.NO_TRANSITION,
                Optional.empty());
    }

    @Test
    void chosenListsTheCandidatesRefusedBeforeIt() {
        Doc doc = new Doc("1", DRAFT);
        ChoiceEligibility<Doc, DocStatus, DocTransition> answer = registry(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("ready",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> GuardResult.reject("not ready"))))
                .of(DOCS).chooseEligibility(doc, List.of(PUBLISH, SUBMIT, DISCARD));
        assertEquals(new ChoiceEligibility.Chosen<>(doc, DRAFT, DISCARD, List.of(
                noEdge(doc, DRAFT, PUBLISH, "a draft is submitted or discarded"),
                new Eligibility.Ineligible<>(doc, DRAFT, SUBMIT, "not ready", RejectionKind.GUARD,
                        Optional.of("ready")))), answer);
        assertEquals(List.of(doc, DRAFT, Optional.of(DISCARD)), List.of(answer.entity(), answer.from(),
                answer.chosen()));
        assertEquals(List.of(Optional.of(answer), Optional.empty()),
                List.of(answer.asChosen(), answer.asNoneAdmitted()));
        assertEquals(List.of(), registry(plain()).of(DOCS).chooseEligibility(doc, List.of(SUBMIT)).asChosen()
                .orElseThrow().rejected());
    }

    @Test
    void noneAdmittedListsEveryRefusal() {
        Doc doc = new Doc("1", REVIEW);
        ChoiceEligibility<Doc, DocStatus, DocTransition> answer =
                registry(plain()).of(DOCS).chooseEligibility(doc, List.of(SUBMIT, DISCARD), "note");
        assertEquals(new ChoiceEligibility.NoneAdmitted<>(doc, REVIEW, List.of(
                noEdge(doc, REVIEW, SUBMIT, "a review is published"),
                noEdge(doc, REVIEW, DISCARD, "a review is published"))), answer);
        assertEquals(List.of(doc, REVIEW, Optional.empty()), List.of(answer.entity(), answer.from(),
                answer.chosen()));
        assertEquals(List.of(Optional.empty(), Optional.of(answer)),
                List.of(answer.asChosen(), answer.asNoneAdmitted()));
    }

    /** The guards run, with the check's input, and the action, the setter and the hop do not. */
    @Test
    void theCheckRunsNoAction() {
        List<String> seen = new ArrayList<>();
        AtomicInteger writes = new AtomicInteger();
        StateMachine<Doc, DocStatus, DocTransition> machine = registry(docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("ready", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            seen.add("guard " + ctx.input());
                            return GuardResult.pass();
                        })
                        .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            seen.add("action");
                            return ActionOutcome.ok();
                        })), (Doc doc, DocStatus status) -> {
                            writes.incrementAndGet();
                            doc.setStatus(status);
                        }).of(DOCS);
        Doc doc = new Doc("1", DRAFT);
        assertEquals(Optional.of(SUBMIT), machine.chooseEligibility(doc, List.of(SUBMIT), "note").chosen());
        assertEquals(List.of("guard Optional[note]"), seen);
        assertEquals(List.of(DRAFT, 0), List.of(doc.status(), writes.get()));
    }

    @Test
    void theRecordsRefuseNullsAndCopyTheirLists() {
        Doc doc = new Doc("1", REVIEW);
        List<Eligibility.Ineligible<Doc, DocStatus, DocTransition>> refusals =
                new ArrayList<>(List.of(noEdge(doc, REVIEW, SUBMIT, "a review is published")));
        ChoiceEligibility.Chosen<Doc, DocStatus, DocTransition> chosen =
                new ChoiceEligibility.Chosen<>(doc, REVIEW, PUBLISH, refusals);
        ChoiceEligibility.NoneAdmitted<Doc, DocStatus, DocTransition> none =
                new ChoiceEligibility.NoneAdmitted<>(doc, REVIEW, refusals);
        refusals.add(noEdge(doc, REVIEW, DISCARD, "a review is published"));
        assertEquals(List.of(1, 1), List.of(chosen.rejected().size(), none.rejections().size()));
        assertThrows(UnsupportedOperationException.class, () -> chosen.rejected().clear());
        assertThrows(UnsupportedOperationException.class, () -> none.rejections().clear());
        List<Eligibility.Ineligible<Doc, DocStatus, DocTransition>> withNull = Arrays.asList(refusals.get(0), null);
        assertThrows(NullPointerException.class, () -> new ChoiceEligibility.Chosen<>(doc, REVIEW, PUBLISH, withNull));
        assertThrows(NullPointerException.class, () -> new ChoiceEligibility.NoneAdmitted<>(doc, REVIEW, withNull));
        assertThrows(NullPointerException.class, () -> new ChoiceEligibility.Chosen<>(null, REVIEW, PUBLISH, refusals));
        assertThrows(NullPointerException.class, () -> new ChoiceEligibility.Chosen<>(doc, null, PUBLISH, refusals));
        assertThrows(NullPointerException.class, () -> new ChoiceEligibility.Chosen<>(doc, REVIEW, null, refusals));
        assertThrows(NullPointerException.class, () -> new ChoiceEligibility.Chosen<>(doc, REVIEW, PUBLISH, null));
        assertThrows(NullPointerException.class, () -> new ChoiceEligibility.NoneAdmitted<>(null, REVIEW, refusals));
        assertThrows(NullPointerException.class, () -> new ChoiceEligibility.NoneAdmitted<>(doc, null, refusals));
        assertThrows(NullPointerException.class, () -> new ChoiceEligibility.NoneAdmitted<>(doc, REVIEW, null));
    }

    @Test
    void noneAdmittedRefusesAnEmptyList() {
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> new ChoiceEligibility.NoneAdmitted<Doc, DocStatus, DocTransition>(new Doc("1", REVIEW), REVIEW,
                        List.of()));
        assertEquals("a NoneAdmitted answer lists the refusal of each candidate", empty.getMessage());
    }
}
