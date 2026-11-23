package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocGraphs.docs;
import static io.github.graphmaquina.fixtures.DocGraphs.plain;
import static io.github.graphmaquina.fixtures.DocMachines.registry;
import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static io.github.graphmaquina.fixtures.LogLines.lines;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AutomaticChain;
import io.github.graphmaquina.api.CallOrigin;
import io.github.graphmaquina.api.CallRequest;
import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.IgnoreReason;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.ItemTransition;
import io.github.graphmaquina.fixtures.LibraryLog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * {@code choose(entity, candidates)} is one root call that takes the caller's candidates in order, gives each the
 * admission an execute of it would get, and runs the first one admitted as that execute would. When none is admitted
 * it ends {@code Ignored} with {@code NO_CANDIDATE_ADMITTED} and every rejection, never {@code Rejected}. A bad list is
 * refused before the call starts. The doc fixture shows each case.
 */
class ChoiceTest {

    /** A guard named {@code name} that rejects every call with {@code reason}. */
    private static UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> refusedBy(String name,
                                                                                              String reason) {
        return (TransitionBuilder<Doc, DocStatus, DocTransition> edge) -> edge.guard(name,
                (GuardContext<Doc, DocStatus, DocTransition> ctx) -> GuardResult.reject(reason));
    }

    /** The doc machine over {@code graph}, whose state setter counts its writes in {@code writes}. */
    private static StateMachine<Doc, DocStatus, DocTransition> counted(
            GraphDefinition<Doc, DocStatus, DocTransition> graph, AtomicInteger writes) {
        return registry(graph, (Doc doc, DocStatus status) -> {
            writes.incrementAndGet();
            doc.setStatus(status);
        }).of(DOCS);
    }

    /** SUBMIT is refused by a guard, so the next candidate, DISCARD, runs as the root step of its execute. */
    @Test
    void theFirstAdmittedCandidateRuns() {
        Doc doc = new Doc("1", DRAFT);
        Outcome<Doc, DocStatus, DocTransition> outcome = registry(docs(refusedBy("ready", "the draft is not ready")))
                .of(DOCS).choose(doc, List.of(SUBMIT, DISCARD));
        Outcome.Applied<Doc, DocStatus, DocTransition> applied = outcome.asApplied().orElseThrow();
        assertEquals(List.of(DRAFT, DISCARD, ARCHIVED), List.of(applied.from(), applied.transition(), applied.to()));
        assertEquals(Optional.of(DISCARD), outcome.requestedTransition());
        assertEquals(1, outcome.records().size());
        assertEquals(ARCHIVED, doc.status());
    }

    /** The guards of every candidate tried, and the action of the one that runs, read the choice's input. */
    @Test
    void theInputReachesTheGuardsAndTheAction() {
        List<String> seen = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("ready",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            seen.add("SUBMIT guard " + ctx.input());
                            return GuardResult.reject("the draft is not ready");
                        }),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard
                        .guard("unused", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            seen.add("DISCARD guard " + ctx.input());
                            return GuardResult.pass();
                        })
                        .action("Discard", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            seen.add("DISCARD action " + ctx.input());
                            return ActionOutcome.ok();
                        }));
        Outcome<Doc, DocStatus, DocTransition> outcome =
                registry(graph).of(DOCS).choose(new Doc("1", DRAFT), List.of(SUBMIT, DISCARD), "note");
        assertEquals(OutcomeCode.APPLIED, outcome.code());
        assertEquals(List.of("SUBMIT guard Optional[note]", "DISCARD guard Optional[note]",
                "DISCARD action Optional[note]"), seen);
    }

    @Test
    void noCandidateAdmittedIsIgnoredWithEveryRejectionInOrder() {
        AtomicInteger writes = new AtomicInteger();
        Doc doc = new Doc("1", DRAFT);
        Outcome.Ignored<Doc, DocStatus, DocTransition> ignored = counted(docs(
                refusedBy("ready", "the draft is not ready"), refusedBy("kept", "the draft is kept")), writes)
                .choose(doc, List.of(SUBMIT, DISCARD)).asIgnored().orElseThrow();
        assertEquals(List.of(IgnoreReason.NO_CANDIDATE_ADMITTED, OutcomeCode.NO_CANDIDATE_ADMITTED, DRAFT),
                List.of(ignored.reason(), ignored.code(), ignored.from()));
        assertEquals(List.of(
                new Eligibility.Ineligible<>(doc, DRAFT, SUBMIT, "the draft is not ready", RejectionKind.GUARD,
                        Optional.of("ready")),
                new Eligibility.Ineligible<>(doc, DRAFT, DISCARD, "the draft is kept", RejectionKind.GUARD,
                        Optional.of("kept"))), ignored.rejections());
        assertEquals(Optional.of("no candidate was admitted: SUBMIT (GUARD ready): the draft is not ready;"
                + " DISCARD (GUARD kept): the draft is kept"), ignored.explanation());
        assertEquals(List.of(Optional.empty(), List.of(), FlowView.empty()),
                List.of(ignored.requestedTransition(), ignored.records(), ignored.flow()));
        assertEquals(List.of(DRAFT, 0), List.of(doc.status(), writes.get()));
    }

    /**
     * A candidate that no edge keys from the state is refused as execute's step refuses it, with the reason of the
     * state's policy, and the next candidate is tried: a state that no candidate leaves admits none.
     */
    @Test
    void aCandidateWithoutAnEdgeFromTheStateIsRefusedWithThePolicyReason() {
        StateMachine<Doc, DocStatus, DocTransition> machine = registry(plain()).of(DOCS);
        Doc review = new Doc("1", REVIEW);
        Outcome.Ignored<Doc, DocStatus, DocTransition> ignored =
                machine.choose(review, List.of(SUBMIT, DISCARD)).asIgnored().orElseThrow();
        assertEquals(List.of(
                new Eligibility.Ineligible<>(review, REVIEW, SUBMIT, "a review is published",
                        RejectionKind.NO_TRANSITION, Optional.empty()),
                new Eligibility.Ineligible<>(review, REVIEW, DISCARD, "a review is published",
                        RejectionKind.NO_TRANSITION, Optional.empty())), ignored.rejections());
        Doc draft = new Doc("2", DRAFT);
        assertEquals(Optional.of(SUBMIT), machine.choose(draft, List.of(PUBLISH, SUBMIT)).requestedTransition());
        assertEquals(List.of(REVIEW, REVIEW), List.of(review.status(), draft.status()));
    }

    /** The action of the admitted candidate fails and its route is taken: the outcome is that candidate's failure. */
    @Test
    void anAdmittedCandidateWhoseActionFailsByARouteIsFailedWithThatCandidate() {
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Submit", (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                                ActionOutcome.failed("the reviewers are away"))
                        .onFailure(DISCARD));
        Doc doc = new Doc("1", DRAFT);
        Outcome<Doc, DocStatus, DocTransition> outcome = registry(graph).of(DOCS).choose(doc, List.of(PUBLISH, SUBMIT));
        Outcome.Failed<Doc, DocStatus, DocTransition> failed = outcome.asFailed().orElseThrow();
        assertEquals(Optional.of(SUBMIT), outcome.requestedTransition());
        assertEquals(List.of(SUBMIT, REVIEW, DISCARD, ARCHIVED),
                List.of(failed.transition(), failed.to(), failed.failureTransition(), failed.movedTo()));
        assertEquals("the reviewers are away", failed.reason());
        assertEquals(ARCHIVED, doc.status());
    }

    /**
     * DRAFT -SUBMIT-> REVIEW, then REVIEW -PUBLISH-> PUBLISHED, automatic, under {@code chain}; ARCHIVE and DISCARD as
     * in the doc fixture.
     */
    private static GraphDefinition<Doc, DocStatus, DocTransition> publishing(AutomaticChain chain) {
        return StateGraph.define(DOCS).automaticChain(chain).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded")
                .statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).automatic().endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    /** The size of the automatic steps of {@code outcome} and the state {@code doc} is left in. */
    private static String chained(Outcome<Doc, DocStatus, DocTransition> outcome, Doc doc) {
        return outcome.automaticSteps().size() + " " + doc.status();
    }

    /**
     * The commit of a choice chains its entity as the commit of an execute of the chosen identifier does: on an
     * AFTER_COMMIT machine it takes the automatic edge that follows, and on an ON_ADVANCE one, whose entities chain
     * only after an advance, it takes none.
     */
    @Test
    void aChoiceCommitChainsAsAnOperationDoes() {
        List<String> chains = new ArrayList<>();
        for (AutomaticChain chain : AutomaticChain.values()) {
            StateMachine<Doc, DocStatus, DocTransition> machine = registry(publishing(chain)).of(DOCS);
            Doc chosen = new Doc("1", DRAFT);
            Doc executed = new Doc("2", DRAFT);
            chains.add(chain + " " + chained(machine.choose(chosen, List.of(PUBLISH, SUBMIT)), chosen) + " / "
                    + chained(machine.execute(executed, SUBMIT), executed));
        }
        assertEquals(List.of("AFTER_COMMIT 1 PUBLISHED / 1 PUBLISHED", "ON_ADVANCE 0 REVIEW / 0 REVIEW"), chains);
    }

    /** The class and the message of what {@code call} throws. */
    private static String refusal(Executable call) {
        RuntimeException refused = assertThrows(RuntimeException.class, call);
        return refused.getClass().getSimpleName() + ": " + refused.getMessage();
    }

    /** Candidate lists that no call accepts, by what is wrong with each. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<String, List<DocTransition>> badLists() {
        Map<String, List<DocTransition>> lists = new LinkedHashMap<>();
        lists.put("empty", List.of());
        lists.put("duplicate", List.of(SUBMIT, DISCARD, SUBMIT));
        lists.put("null element", Arrays.asList(SUBMIT, null));
        lists.put("other catalogue", (List) List.of(SUBMIT, ItemTransition.PACK));
        return lists;
    }

    /**
     * An empty list, a repeated candidate, a null one, a candidate of another catalogue, which only raw types pass,
     * and a null input are refused before the call or the check starts, so nothing is logged.
     */
    @Test
    void badCandidateListsAreRefusedBeforeTheCall() {
        List<String> refusals = new ArrayList<>();
        try (MachineRegistry registry = registry(plain()); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            StateMachine<Doc, DocStatus, DocTransition> machine = registry.of(DOCS);
            Doc doc = new Doc("1", DRAFT);
            for (Map.Entry<String, List<DocTransition>> bad : badLists().entrySet()) {
                refusals.add(bad.getKey() + " " + refusal(() -> machine.choose(doc, bad.getValue())) + " / "
                        + refusal(() -> machine.chooseEligibility(doc, bad.getValue(), "note")));
            }
            refusals.add("null input " + refusal(() -> machine.choose(doc, List.of(SUBMIT), null)) + " / "
                    + refusal(() -> machine.chooseEligibility(doc, List.of(SUBMIT), null)));
            refusals.add("null entity " + refusal(() -> machine.choose(null, List.of(SUBMIT))) + " / "
                    + refusal(() -> machine.chooseEligibility(null, List.of(SUBMIT))));
            refusals.add("null list " + refusal(() -> machine.choose(doc, null)) + " / "
                    + refusal(() -> machine.chooseEligibility(doc, null)));
            assertEquals(List.of(), log.records());
            assertEquals(DRAFT, doc.status());
        }
        assertEquals(List.of(
                "empty IllegalArgumentException: candidates must not be empty"
                        + " / IllegalArgumentException: candidates must not be empty",
                "duplicate IllegalArgumentException: candidates lists SUBMIT twice"
                        + " / IllegalArgumentException: candidates lists SUBMIT twice",
                "null element NullPointerException: candidates contains a null element"
                        + " / NullPointerException: candidates contains a null element",
                "other catalogue IllegalArgumentException: transition must belong to"
                        + " io.github.graphmaquina.fixtures.DocTransition: PACK"
                        + " / IllegalArgumentException: transition must belong to"
                        + " io.github.graphmaquina.fixtures.DocTransition: PACK",
                "null input NullPointerException: input is marked non-null but is null"
                        + " / NullPointerException: input is marked non-null but is null",
                "null entity NullPointerException: entity is marked non-null but is null"
                        + " / NullPointerException: entity is marked non-null but is null",
                "null list NullPointerException: candidates is marked non-null but is null"
                        + " / NullPointerException: candidates is marked non-null but is null"), refusals);
    }

    /** A guard that makes a choice is refused as one that executes, and the entity it asked about does not move. */
    @Test
    void aChoiceFromAGuardIsReentrant() {
        List<String> refusals = new ArrayList<>();
        Doc other = new Doc("2", DRAFT);
        List<StateMachine<Doc, DocStatus, DocTransition>> machine = new ArrayList<>();
        machine.add(registry(docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("asks",
                (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                    refusals.add(refusal(() -> machine.get(0).choose(other, List.of(SUBMIT))));
                    return GuardResult.pass();
                }))).of(DOCS));
        assertEquals(OutcomeCode.APPLIED, machine.get(0).choose(new Doc("1", DRAFT), List.of(SUBMIT)).code());
        assertEquals(1, refusals.size());
        assertTrue(refusals.get(0).startsWith("ReentrantTransitionException: "), refusals.get(0));
        assertEquals(DRAFT, other.status());
    }

    /** The observers hear one call, a choice of the application, which requests no identifier. */
    @Test
    void theCallRecordsAChooseRequest() {
        ObserverTest.Recorder recorder = new ObserverTest.Recorder();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(plain()).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .observer(recorder)
                .build()) {
            registry.of(DOCS).choose(new Doc("1", DRAFT), List.of(PUBLISH, SUBMIT));
        }
        assertEquals(List.of("started Doc/DocStatus choose", "completed Doc/DocStatus choose APPLIED"),
                recorder.events);
        MachineCall call = recorder.calls.get(0);
        assertEquals(List.of(CallRequest.CHOOSE, CallOrigin.APPLICATION, Optional.empty()),
                List.of(call.request(), call.origin(), call.transition()));
    }

    /**
     * The start line names the candidates in order, and the call names the candidate it admitted once that
     * candidate's step returned; a choice that admits none ends with the rejections in its reason.
     */
    @Test
    void theLogNamesTheCandidatesAndTheAdmittedOne() {
        try (MachineRegistry registry = registry(plain()); LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            registry.of(DOCS).choose(new Doc("1", DRAFT), List.of(PUBLISH, SUBMIT));
            registry.of(DOCS).choose(new Doc("2", REVIEW), List.of(SUBMIT, DISCARD));
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus choose PUBLISH,SUBMIT",
                    "FINE call=A candidate SUBMIT admitted",
                    "FINE call=A committed Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW depth=0",
                    "FINE call=A end code=APPLIED state=REVIEW entity=Doc/DocStatus#1 records=1 starts=0 reactions=0"
                            + " aborted=0 automatic=0 elapsed=<us>",
                    "FINE call=B start Doc/DocStatus choose SUBMIT,DISCARD",
                    "FINE call=B end code=NO_CANDIDATE_ADMITTED state=REVIEW entity=Doc/DocStatus#2 records=0"
                            + " starts=0 reactions=0 aborted=0 automatic=0 reason=\"no candidate was admitted:"
                            + " SUBMIT (NO_TRANSITION): a review is published; DISCARD (NO_TRANSITION): a review is"
                            + " published\" elapsed=<us>"), lines(log));
        }
    }
}
