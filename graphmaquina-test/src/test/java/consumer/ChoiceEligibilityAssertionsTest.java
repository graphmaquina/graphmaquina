package consumer;

import static consumer.GraphAssertionErrors.assertFailure;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ChoiceEligibility;
import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.runtime.StateMachines;
import io.github.graphmaquina.test.ChoiceEligibilityAssert;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.GraphAssertions;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Contract of {@link ChoiceEligibilityAssert}. Every expectation gets a match and a mismatch, each mismatch is read off
 * the fields of the {@link GraphAssertionError} and off its rendered message, an expectation that only one variant can
 * meet fails as an assertion on the variant on the other one, the refusals are read from the component of the variant
 * that holds them, and a null subject fails inspectably.
 */
class ChoiceEligibilityAssertionsTest {

    private static final String CONTEXT = "choice eligibility";

    // --- the variant -------------------------------------------------------------------------------

    @Test
    void aChosenAnswerChoseItsCandidate() {
        ChoiceEligibilityAssert<Entity, State, Id> view = ChoiceEligibilityAssert.assertThat(chosen());
        assertSame(view, view.chose(Id.FINISH));
    }

    @Test
    void anotherCandidateIsNotTheOneChosen() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> ChoiceEligibilityAssert.assertThat(chosen()).chose(Id.PREPARE)),
                CONTEXT, "chosen", Id.PREPARE, Id.FINISH,
                "choice eligibility: chosen expected <PREPARE> but was <FINISH>");
    }

    @Test
    void anAnswerThatAdmittedNoneChoseNothing() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> ChoiceEligibilityAssert.assertThat(noneAdmitted()).chose(Id.FINISH)),
                CONTEXT, "variant", "Chosen", "NoneAdmitted",
                "choice eligibility: variant expected <Chosen> but was <NoneAdmitted>");
    }

    @Test
    void anAnswerThatAdmittedNoneAdmittedNone() {
        ChoiceEligibilityAssert<Entity, State, Id> view = ChoiceEligibilityAssert.assertThat(noneAdmitted());
        assertSame(view, view.admittedNone());
    }

    @Test
    void aChosenAnswerAdmittedOne() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> ChoiceEligibilityAssert.assertThat(chosen()).admittedNone()),
                CONTEXT, "variant", "NoneAdmitted", "Chosen",
                "choice eligibility: variant expected <NoneAdmitted> but was <Chosen>");
    }

    // --- what both answers report ------------------------------------------------------------------

    @Test
    void theStateTheCheckReadIsReportedByBothAnswers() {
        ChoiceEligibilityAssert<Entity, State, Id> view = ChoiceEligibilityAssert.assertThat(chosen());
        assertSame(view, view.from(State.READY));
        ChoiceEligibilityAssert.assertThat(noneAdmitted()).from(State.READY);
    }

    @Test
    void anotherStateIsNotTheStateTheCheckRead() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> ChoiceEligibilityAssert.assertThat(chosen()).from(State.NEW)),
                CONTEXT, "from", State.NEW, State.READY, "choice eligibility: from expected <NEW> but was <READY>");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> ChoiceEligibilityAssert.assertThat(noneAdmitted()).from(State.DONE)),
                CONTEXT, "from", State.DONE, State.READY, "choice eligibility: from expected <DONE> but was <READY>");
    }

    // --- the refusals ------------------------------------------------------------------------------

    @Test
    void theRefusalsOfAChosenAnswerAreThoseBeforeItsCandidate() {
        ChoiceEligibilityAssert.assertThat(chosen()).rejections().single()
                .hasNoTransition().hasReason("finish only").transition(Id.PREPARE);
        ChoiceEligibilityAssert.assertThat(firstChosen()).rejections().hasSize(0);
    }

    @Test
    void theRefusalsOfAChosenAnswerAreNamedAfterItsRejectedComponent() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> ChoiceEligibilityAssert.assertThat(chosen()).rejections().hasSize(2)),
                CONTEXT + ".rejected", "size", 2, 1, "choice eligibility.rejected: size expected <2> but was <1>");
    }

    @Test
    void theRefusalsOfAnAnswerThatAdmittedNoneAreEveryCandidates() {
        ChoiceEligibilityAssert.assertThat(noneAdmitted()).rejections().hasSize(2).atIndex(0)
                .isRejectedByGuardNamed("paid").hasReason("not paid").transition(Id.FINISH);
        ChoiceEligibilityAssert.assertThat(noneAdmitted()).rejections().atIndex(1)
                .hasNoTransition().hasReason("finish only").transition(Id.RECOVER);
    }

    @Test
    void theRefusalsOfAnAnswerThatAdmittedNoneAreNamedAfterItsRejectionsComponent() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> ChoiceEligibilityAssert.assertThat(noneAdmitted()).rejections().single()),
                CONTEXT + ".rejections", "size", 1, 2, "choice eligibility.rejections: size expected <1> but was <2>");
    }

    @Test
    void everyExpectationReturnsTheViewSoThatAChainKeepsItsTypes() {
        ChoiceEligibilityAssert<Entity, State, Id> view = ChoiceEligibilityAssert.assertThat(noneAdmitted());
        assertSame(view, view.admittedNone().from(State.READY));
    }

    // --- the facade --------------------------------------------------------------------------------

    @Test
    void theFacadeHandsAChoiceEligibilityToItsOwnView() {
        ChoiceEligibility<Entity, State, Id> answer = chosen();
        ChoiceEligibilityAssert<Entity, State, Id> view = GraphAssertions.assertThat(answer);
        view.chose(Id.FINISH).from(State.READY);
        assertFailure(assertThrows(GraphAssertionError.class, () -> GraphAssertions.assertThat(answer).admittedNone()),
                CONTEXT, "variant", "NoneAdmitted", "Chosen");
    }

    // --- null subjects and null expectations -------------------------------------------------------

    @ParameterizedTest
    @MethodSource("expectationsWithTheirNullSubjectFailure")
    void aNullSubjectFailsAsAnAssertionOnTheFirstExpectation(
            Consumer<ChoiceEligibilityAssert<Entity, State, Id>> expectation, String property, Object expected) {
        ChoiceEligibility<Entity, State, Id> none = null;
        ChoiceEligibilityAssert<Entity, State, Id> view = ChoiceEligibilityAssert.assertThat(none);
        assertFailure(assertThrows(GraphAssertionError.class, () -> expectation.accept(view)),
                CONTEXT, property, expected, null);
    }

    static Stream<Arguments> expectationsWithTheirNullSubjectFailure() {
        return Stream.of(
                Arguments.of(named("chose", (ChoiceEligibilityAssert<Entity, State, Id> view) ->
                        view.chose(Id.FINISH)), "variant", "Chosen"),
                Arguments.of(named("admittedNone", ChoiceEligibilityAssert::admittedNone), "variant", "NoneAdmitted"),
                Arguments.of(named("from", (ChoiceEligibilityAssert<Entity, State, Id> view) ->
                        view.from(State.NEW)), "from", State.NEW),
                Arguments.of(named("rejections", ChoiceEligibilityAssert::rejections), "rejections",
                        "an answer to read"));
    }

    @Test
    void aNullExpectedIsAnArgumentErrorEvenWhenTheSubjectIsNull() {
        ChoiceEligibility<Entity, State, Id> none = null;
        ChoiceEligibilityAssert<Entity, State, Id> view = ChoiceEligibilityAssert.assertThat(none);
        assertThrows(NullPointerException.class, () -> view.chose(null));
        assertThrows(NullPointerException.class, () -> view.from(null));
    }

    // --- against the real engine -------------------------------------------------------------------

    @Test
    void anAnswerOfTheEngineNamesTheFirstAdmittedCandidateAndTheRefusalsBeforeIt() {
        Entity entity = new Entity("e", State.NEW);
        GraphAssertions.assertThat(engine().chooseEligibility(entity, List.of(Id.FINISH, Id.PREPARE)))
                .chose(Id.PREPARE).from(State.NEW)
                .rejections().single().hasNoTransition().hasReason("prepare first").transition(Id.FINISH);
    }

    @Test
    void anAnswerOfTheEngineThatAdmitsNoneListsEveryRefusalInOrder() {
        Entity entity = new Entity("e", State.READY);
        ChoiceEligibilityAssert<Entity, State, Id> view =
                GraphAssertions.assertThat(engine().chooseEligibility(entity, List.of(Id.FINISH, Id.RECOVER)));
        view.admittedNone().from(State.READY).rejections().hasSize(2).atIndex(0)
                .isRejectedByGuardNamed("paid").hasReason("not paid").transition(Id.FINISH);
        view.rejections().atIndex(1).hasNoTransition().hasReason("finish only").transition(Id.RECOVER);
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private static StateMachine<Entity, State, Id> engine() {
        GraphDefinition<Entity, State, Id> graph = StateGraph.define(TestDomain.KEY)
                .initial(State.NEW).terminal(State.DONE).terminal(State.FAILED)
                .statePolicy(State.NEW, "prepare first")
                .statePolicy(State.READY, "finish only")
                .transition(Id.PREPARE, State.NEW, State.READY).endTransition()
                .transition(Id.RECOVER, State.NEW, State.FAILED).endTransition()
                .transition(Id.FINISH, State.READY, State.DONE)
                    .guard("paid", (GuardContext<Entity, State, Id> context) ->
                            GuardResult.reject("not paid"))
                    .endTransition()
                .build();
        return StateMachines.from(graph).state(Entity::state, Entity::setState).build();
    }

    private static Named<Consumer<ChoiceEligibilityAssert<Entity, State, Id>>> named(
            String name, Consumer<ChoiceEligibilityAssert<Entity, State, Id>> expectation) {
        return Named.of(name, expectation);
    }

    /** {@code FINISH} chosen from {@code READY}, once {@code PREPARE}, which no edge keys there, was refused. */
    private static ChoiceEligibility<Entity, State, Id> chosen() {
        Entity entity = new Entity("e", State.READY);
        return new ChoiceEligibility.Chosen<>(entity, State.READY, Id.FINISH, List.of(
                new Eligibility.Ineligible<>(entity, State.READY, Id.PREPARE, "finish only",
                        RejectionKind.NO_TRANSITION, Optional.empty())));
    }

    /** {@code PREPARE} chosen from {@code NEW}, as the first candidate. */
    private static ChoiceEligibility<Entity, State, Id> firstChosen() {
        return new ChoiceEligibility.Chosen<>(new Entity("e", State.NEW), State.NEW, Id.PREPARE, List.of());
    }

    /** From {@code READY}, the guard {@code paid} refused {@code FINISH} and no edge keys {@code RECOVER}. */
    private static ChoiceEligibility<Entity, State, Id> noneAdmitted() {
        Entity entity = new Entity("e", State.READY);
        return new ChoiceEligibility.NoneAdmitted<>(entity, State.READY, List.of(
                new Eligibility.Ineligible<>(entity, State.READY, Id.FINISH, "not paid", RejectionKind.GUARD,
                        Optional.of("paid")),
                new Eligibility.Ineligible<>(entity, State.READY, Id.RECOVER, "finish only",
                        RejectionKind.NO_TRANSITION, Optional.empty())));
    }
}
