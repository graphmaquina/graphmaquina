package consumer;

import static consumer.GraphAssertionErrors.assertFailure;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.Eligibility;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.runtime.StateMachines;
import io.github.graphmaquina.test.EligibilityAssert;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.GraphAssertions;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Contract of {@link EligibilityAssert}. Every expectation gets a match and a mismatch, each mismatch is read off the
 * fields of the {@link GraphAssertionError} and off its rendered message, an expectation that only an ineligible
 * answer can meet fails as an assertion on an eligible one, and a null subject fails inspectably.
 */
class EligibilityAssertionsTest {

    private static final String VARIANT_MISMATCH = "eligibility: variant expected <Ineligible> but was <Eligible>";

    // --- the variant -------------------------------------------------------------------------------

    @Test
    void anEligibleAnswerIsEligible() {
        EligibilityAssert<Entity, State, Id> view = EligibilityAssert.assertThat(eligible());
        assertSame(view, view.isEligible());
    }

    @Test
    void anIneligibleAnswerIsNotEligible() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> EligibilityAssert.assertThat(noEdge()).isEligible()),
                "eligibility", "variant", "Eligible", "Ineligible",
                "eligibility: variant expected <Eligible> but was <Ineligible>");
    }

    @Test
    void anIneligibleAnswerIsIneligible() {
        EligibilityAssert<Entity, State, Id> view = EligibilityAssert.assertThat(guardRejected("first"));
        assertSame(view, view.isIneligible());
    }

    @Test
    void anEligibleAnswerIsNotIneligible() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> EligibilityAssert.assertThat(eligible()).isIneligible()),
                "eligibility", "variant", "Ineligible", "Eligible", VARIANT_MISMATCH);
    }

    // --- the kind of the refusal -------------------------------------------------------------------

    @Test
    void anAnswerWithoutAnEdgeHasNoTransition() {
        EligibilityAssert<Entity, State, Id> view = EligibilityAssert.assertThat(noEdge());
        assertSame(view, view.hasNoTransition());
    }

    @Test
    void aGuardRefusalIsNotTheAbsenceOfAnEdge() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> EligibilityAssert.assertThat(guardRejected("first")).hasNoTransition()),
                "eligibility", "kind", RejectionKind.NO_TRANSITION, RejectionKind.GUARD,
                "eligibility: kind expected <NO_TRANSITION> but was <GUARD>");
    }

    @Test
    void aGuardRefusalNamesTheGuardThatRefused() {
        EligibilityAssert<Entity, State, Id> view = EligibilityAssert.assertThat(guardRejected("first"));
        assertSame(view, view.isRejectedByGuardNamed("first"));
    }

    @Test
    void anotherGuardNameIsNotTheGuardThatRefused() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> EligibilityAssert.assertThat(guardRejected("first")).isRejectedByGuardNamed("second")),
                "eligibility", "guard", Optional.of("second"), Optional.of("first"),
                "eligibility: guard expected <Optional[second]> but was <Optional[first]>");
    }

    @Test
    void theAbsenceOfAnEdgeIsNotAGuardRefusal() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> EligibilityAssert.assertThat(noEdge()).isRejectedByGuardNamed("first")),
                "eligibility", "kind", RejectionKind.GUARD, RejectionKind.NO_TRANSITION,
                "eligibility: kind expected <GUARD> but was <NO_TRANSITION>");
    }

    @Test
    void aDetachedSubmachineIsDetached() {
        EligibilityAssert<Entity, State, Id> view =
                EligibilityAssert.assertThat(ineligible("not held", RejectionKind.DETACHED));
        assertSame(view, view.isDetached());
    }

    @Test
    void theAbsenceOfAnEdgeIsNotDetachment() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> EligibilityAssert.assertThat(noEdge()).isDetached()),
                "eligibility", "kind", RejectionKind.DETACHED, RejectionKind.NO_TRANSITION,
                "eligibility: kind expected <DETACHED> but was <NO_TRANSITION>");
    }

    @Test
    void aReservedExitIsReserved() {
        EligibilityAssert<Entity, State, Id> view =
                EligibilityAssert.assertThat(ineligible("only the child ends it", RejectionKind.RESERVED_EXIT));
        assertSame(view, view.isReservedExit());
    }

    @Test
    void detachmentIsNotAReservedExit() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> EligibilityAssert.assertThat(ineligible("not held", RejectionKind.DETACHED)).isReservedExit()),
                "eligibility", "kind", RejectionKind.RESERVED_EXIT, RejectionKind.DETACHED,
                "eligibility: kind expected <RESERVED_EXIT> but was <DETACHED>");
    }

    // --- the reason --------------------------------------------------------------------------------

    @Test
    void theReasonIsComparedByValue() {
        EligibilityAssert<Entity, State, Id> view = EligibilityAssert.assertThat(noEdge());
        assertSame(view, view.hasReason("not ready"));
    }

    @Test
    void anotherReasonIsNotTheReason() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> EligibilityAssert.assertThat(noEdge()).hasReason("ready")),
                "eligibility", "reason", "ready", "not ready",
                "eligibility: reason expected <ready> but was <not ready>");
    }

    // --- what an eligible answer cannot meet -------------------------------------------------------

    @ParameterizedTest
    @MethodSource("expectationsOnlyAnIneligibleAnswerCanMeet")
    void anEligibleAnswerFailsAsAnAssertionNotAsACast(Consumer<EligibilityAssert<Entity, State, Id>> expectation) {
        EligibilityAssert<Entity, State, Id> view = EligibilityAssert.assertThat(eligible());
        assertFailure(assertThrows(GraphAssertionError.class, () -> expectation.accept(view)),
                "eligibility", "variant", "Ineligible", "Eligible", VARIANT_MISMATCH);
    }

    static Stream<Named<Consumer<EligibilityAssert<Entity, State, Id>>>> expectationsOnlyAnIneligibleAnswerCanMeet() {
        return Stream.of(
                named("hasNoTransition", EligibilityAssert::hasNoTransition),
                named("isRejectedByGuardNamed", (EligibilityAssert<Entity, State, Id> view) ->
                        view.isRejectedByGuardNamed("first")),
                named("isDetached", EligibilityAssert::isDetached),
                named("isReservedExit", EligibilityAssert::isReservedExit),
                named("hasReason", (EligibilityAssert<Entity, State, Id> view) -> view.hasReason("not ready")));
    }

    // --- what both answers report ------------------------------------------------------------------

    @Test
    void theStateTheCheckReadIsReportedByBothAnswers() {
        EligibilityAssert<Entity, State, Id> view = EligibilityAssert.assertThat(eligible());
        assertSame(view, view.from(State.NEW));
        EligibilityAssert.assertThat(noEdge()).from(State.NEW);
    }

    @Test
    void anotherStateIsNotTheStateTheCheckRead() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> EligibilityAssert.assertThat(eligible()).from(State.READY)),
                "eligibility", "from", State.READY, State.NEW,
                "eligibility: from expected <READY> but was <NEW>");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> EligibilityAssert.assertThat(noEdge()).from(State.DONE)),
                "eligibility", "from", State.DONE, State.NEW,
                "eligibility: from expected <DONE> but was <NEW>");
    }

    @Test
    void theIdentifierTheCheckAskedAboutIsReportedByBothAnswers() {
        EligibilityAssert<Entity, State, Id> view = EligibilityAssert.assertThat(eligible());
        assertSame(view, view.transition(Id.PREPARE));
        EligibilityAssert.assertThat(noEdge()).transition(Id.PREPARE);
    }

    @Test
    void anotherIdentifierIsNotTheOneTheCheckAskedAbout() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> EligibilityAssert.assertThat(eligible()).transition(Id.FINISH)),
                "eligibility", "transition", Id.FINISH, Id.PREPARE,
                "eligibility: transition expected <FINISH> but was <PREPARE>");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> EligibilityAssert.assertThat(noEdge()).transition(Id.ABANDON)),
                "eligibility", "transition", Id.ABANDON, Id.PREPARE,
                "eligibility: transition expected <ABANDON> but was <PREPARE>");
    }

    @Test
    void everyExpectationReturnsTheViewSoThatAChainKeepsItsTypes() {
        EligibilityAssert<Entity, State, Id> view = EligibilityAssert.assertThat(guardRejected("first"));
        assertSame(view, view.isIneligible().isRejectedByGuardNamed("first").hasReason("not ready")
                .from(State.NEW).transition(Id.PREPARE));
    }

    // --- the facade --------------------------------------------------------------------------------

    @Test
    void theFacadeHandsAnEligibilityToItsOwnView() {
        Eligibility<Entity, State, Id> answer = guardRejected("first");
        EligibilityAssert<Entity, State, Id> view = GraphAssertions.assertThat(answer);
        view.isIneligible().isRejectedByGuardNamed("first");
        assertFailure(assertThrows(GraphAssertionError.class, () -> GraphAssertions.assertThat(answer).isEligible()),
                "eligibility", "variant", "Eligible", "Ineligible");
    }

    // --- null subjects and null expectations -------------------------------------------------------

    @ParameterizedTest
    @MethodSource("expectationsWithTheirNullSubjectFailure")
    void aNullSubjectFailsAsAnAssertionOnTheFirstExpectation(
            Consumer<EligibilityAssert<Entity, State, Id>> expectation, String property, Object expected) {
        Eligibility<Entity, State, Id> none = null;
        EligibilityAssert<Entity, State, Id> view = EligibilityAssert.assertThat(none);
        assertFailure(assertThrows(GraphAssertionError.class, () -> expectation.accept(view)),
                "eligibility", property, expected, null);
    }

    static Stream<Arguments> expectationsWithTheirNullSubjectFailure() {
        return Stream.of(
                Arguments.of(named("isEligible", EligibilityAssert::isEligible), "variant", "Eligible"),
                Arguments.of(named("isIneligible", EligibilityAssert::isIneligible), "variant", "Ineligible"),
                Arguments.of(named("hasNoTransition", EligibilityAssert::hasNoTransition), "variant", "Ineligible"),
                Arguments.of(named("isRejectedByGuardNamed", (EligibilityAssert<Entity, State, Id> view) ->
                        view.isRejectedByGuardNamed("first")), "variant", "Ineligible"),
                Arguments.of(named("isDetached", EligibilityAssert::isDetached), "variant", "Ineligible"),
                Arguments.of(named("isReservedExit", EligibilityAssert::isReservedExit), "variant", "Ineligible"),
                Arguments.of(named("hasReason", (EligibilityAssert<Entity, State, Id> view) ->
                        view.hasReason("not ready")), "variant", "Ineligible"),
                Arguments.of(named("from", (EligibilityAssert<Entity, State, Id> view) -> view.from(State.NEW)),
                        "from", State.NEW),
                Arguments.of(named("transition", (EligibilityAssert<Entity, State, Id> view) ->
                        view.transition(Id.PREPARE)), "transition", Id.PREPARE));
    }

    @Test
    void aNullExpectedIsAnArgumentErrorEvenWhenTheSubjectIsNull() {
        Eligibility<Entity, State, Id> none = null;
        EligibilityAssert<Entity, State, Id> view = EligibilityAssert.assertThat(none);
        assertThrows(NullPointerException.class, () -> view.isRejectedByGuardNamed(null));
        assertThrows(NullPointerException.class, () -> view.hasReason(null));
        assertThrows(NullPointerException.class, () -> view.from(null));
        assertThrows(NullPointerException.class, () -> view.transition(null));
    }

    // --- against the real engine -------------------------------------------------------------------

    @Test
    void anAnswerOfTheEngineIsEligibleWhenNothingRefusesIt() {
        Entity entity = new Entity("e", State.NEW);
        GraphAssertions.assertThat(engine().eligibility(entity, Id.PREPARE))
                .isEligible().from(State.NEW).transition(Id.PREPARE);
    }

    @Test
    void anAnswerOfTheEngineNamesTheGuardThatRefuses() {
        Entity entity = new Entity("e", State.READY);
        GraphAssertions.assertThat(engine().eligibility(entity, Id.FINISH))
                .isIneligible().isRejectedByGuardNamed("paid").hasReason("not paid")
                .from(State.READY).transition(Id.FINISH);
    }

    @Test
    void anAnswerOfTheEngineSaysThatNoEdgeLeavesTheState() {
        Entity entity = new Entity("e", State.NEW);
        GraphAssertions.assertThat(engine().eligibility(entity, Id.FINISH))
                .hasNoTransition().hasReason("prepare first").from(State.NEW).transition(Id.FINISH);
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

    private static Named<Consumer<EligibilityAssert<Entity, State, Id>>> named(
            String name, Consumer<EligibilityAssert<Entity, State, Id>> expectation) {
        return Named.of(name, expectation);
    }

    private static Eligibility<Entity, State, Id> eligible() {
        return new Eligibility.Eligible<>(new Entity("e", State.NEW), State.NEW, Id.PREPARE);
    }

    private static Eligibility<Entity, State, Id> noEdge() {
        return ineligible("not ready", RejectionKind.NO_TRANSITION);
    }

    private static Eligibility<Entity, State, Id> guardRejected(String guard) {
        return new Eligibility.Ineligible<>(new Entity("e", State.NEW), State.NEW, Id.PREPARE, "not ready",
                RejectionKind.GUARD, Optional.of(guard));
    }

    private static Eligibility<Entity, State, Id> ineligible(String reason, RejectionKind kind) {
        return new Eligibility.Ineligible<>(new Entity("e", State.NEW), State.NEW, Id.PREPARE, reason, kind,
                Optional.empty());
    }
}
