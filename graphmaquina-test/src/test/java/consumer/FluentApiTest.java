package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.test.ActionOutcomeAssert;
import io.github.graphmaquina.test.EntityStateAssert;
import io.github.graphmaquina.test.GraphAssert;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.GraphAssertions;
import io.github.graphmaquina.test.GuardResultAssert;
import io.github.graphmaquina.test.InvalidGraphAssert;
import io.github.graphmaquina.test.OutcomeAssert;
import io.github.graphmaquina.test.TransitionFailureAssert;
import io.github.graphmaquina.test.fixtures.TestDomain;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Contract of {@link GraphAssertions}. The facade adds no behaviour of its own, so every case here reads
 * the subject back through the view it returned: the overload that was chosen, the subject it was handed
 * and the argument checks it must not swallow. A null subject reaches the view untouched, because the view
 * is what turns it into an inspectable assertion failure.
 */
class FluentApiTest {

    private static final GraphDefinition<Entity, State> GRAPH = TestDomain.graph(Duration.ofSeconds(1));

    @Test
    void facadePreservesFluentTypes() {
        Entity entity = new Entity("e", State.READY);
        Outcome.Applied<Entity, State> result =
                new Outcome.Applied<>(entity, State.NEW, State.READY, List.of(), List.of());
        GraphAssertions.assertThat(result).isApplied().from(State.NEW)
                .movedTo(State.READY).hasEntityState(Entity::state, State.READY);
        GraphAssertions.assertThat(TestDomain.graph(Duration.ofSeconds(1)))
                .edge(State.NEW, State.READY).isAction().hasActionNamed("prepare");
    }

    @Test
    void theOutcomeOverloadAssertsOverTheOutcomeItWasGiven() {
        Entity entity = new Entity("e", State.NEW);
        Outcome<Entity, State> ignored = new Outcome.Ignored<>(entity, State.NEW, State.NEW);
        OutcomeAssert<Entity, State> view = GraphAssertions.assertThat(ignored);
        assertSame(view, view.hasEntitySameAs(entity));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.from(State.READY)),
                "outcome", "from", State.READY, State.NEW);
    }

    @Test
    void theGraphOverloadAssertsOverTheDefinitionItWasGiven() {
        GraphAssert<Entity, State> view = GraphAssertions.assertThat(GRAPH);
        assertSame(view, view.hasInitial(State.NEW));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasInitial(State.DONE)),
                Entity.class.getName() + "/" + State.class.getName(), "initial", State.DONE, State.NEW);
    }

    @Test
    void theActionOutcomeOverloadAssertsOverTheValueItWasGiven() {
        ActionOutcomeAssert view = GraphAssertions.assertThat(ActionOutcome.failed("declined"));
        assertSame(view, view.hasReason("declined"));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.isOk()),
                "action outcome", "kind", "Ok", "Failed");
    }

    @Test
    void theGuardResultOverloadAssertsOverTheValueItWasGiven() {
        GuardResultAssert view = GraphAssertions.assertThat(GuardResult.reject("not ready"));
        assertSame(view, view.hasReason("not ready"));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.isPass()),
                "guard result", "kind", "Pass", "Reject");
    }

    @Test
    void theEntityOverloadReadsTheStateThroughTheGetter() {
        Entity entity = new Entity("e", State.READY);
        EntityStateAssert<Entity, State> view = GraphAssertions.assertThat(entity, Entity::state);
        assertSame(view, view.isIn(State.READY));
        entity.setState(State.DONE);
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.isIn(State.READY)),
                "entity", "state", State.READY, State.DONE);
    }

    @Test
    void anInvalidGraphBindsToTheDslView() {
        InvalidGraphException invalid = assertThrows(InvalidGraphException.class,
                () -> StateGraph.define(TestDomain.KEY).initial(State.NEW).build());
        InvalidGraphAssert view = GraphAssertions.assertThat(invalid);
        assertSame(view, view.hasCodesExactly("SG101"));
    }

    /**
     * The facade's own capture helper returns a {@link Throwable}, so it binds to the failure view; the DSL
     * view is reached from there by narrowing, and a subject of any other kind fails as an inspectable
     * assertion rather than as the {@link ClassCastException} a cast would raise.
     */
    @Test
    void aCapturedInvalidGraphIsReachedByNarrowingTheFailureView() {
        Throwable failure = GraphAssertions.captureFailure(
                () -> StateGraph.define(TestDomain.KEY).initial(State.NEW).build());
        GraphAssertions.assertThat(failure).isInvalidGraph().hasCodesExactly("SG101");
        IllegalStateException other = new IllegalStateException("boom");
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> GraphAssertions.assertThat(other).isInvalidGraph()),
                "failure", "type", InvalidGraphException.class, other);
    }

    @Test
    void anyOtherThrowableBindsToTheFailureView() {
        IllegalStateException cause = new IllegalStateException("underlying");
        TransitionFailureAssert view = GraphAssertions.assertThat(new RuntimeException("boom", cause));
        assertSame(view, view.hasCauseSameAs(cause));
        assertFailure(assertThrows(GraphAssertionError.class, () -> view.hasNoCause()),
                "failure", "cause", "no cause", cause);
    }

    @Test
    void captureFailureReturnsTheExceptionTheBodyThrew() {
        IllegalStateException thrown = new IllegalStateException("boom");
        assertSame(thrown, GraphAssertions.captureFailure(() -> {
            throw thrown;
        }));
    }

    @Test
    void captureFailureReportsABodyThatCompletedNormally() {
        assertFailure(assertThrows(GraphAssertionError.class, () -> GraphAssertions.captureFailure(() -> { })),
                "operation", "failure", "an exception", "completed normally");
    }

    @Test
    void aNullGetterStaysAnArgumentError() {
        Entity entity = new Entity("e", State.READY);
        NullPointerException error = assertThrows(NullPointerException.class,
                () -> GraphAssertions.assertThat(entity, (Function<Entity, State>) null));
        assertEquals("getter", error.getMessage());
    }

    // --- null subjects: the facade hands them on rather than raising an argument error ---------------

    @Test
    void aNullOutcomeFailsAsAnAssertion() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> GraphAssertions.assertThat((Outcome<Entity, State>) null).from(State.NEW)),
                "outcome", "from", State.NEW, null);
    }

    @Test
    void aNullDefinitionFailsAsAnAssertion() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> GraphAssertions.assertThat((GraphDefinition<Entity, State>) null).hasInitial(State.NEW)),
                "graph", "initial", State.NEW, null);
    }

    @Test
    void aNullActionOutcomeFailsAsAnAssertion() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> GraphAssertions.assertThat((ActionOutcome) null).isOk()),
                "action outcome", "kind", "Ok", null);
    }

    @Test
    void aNullGuardResultFailsAsAnAssertion() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> GraphAssertions.assertThat((GuardResult) null).isPass()),
                "guard result", "kind", "Pass", null);
    }

    @Test
    void aNullFailureFailsAsAnAssertion() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> GraphAssertions.assertThat((Throwable) null).hasNoCause()),
                "failure", "cause", "no cause", null);
    }

    @Test
    void aNullInvalidGraphFailsAsAnAssertion() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> GraphAssertions.assertThat((InvalidGraphException) null).hasProblemCount(1)),
                "invalid graph", "problem count", 1, null);
    }

    @Test
    void aNullEntityFailsAsAnAssertion() {
        assertFailure(assertThrows(GraphAssertionError.class,
                () -> GraphAssertions.assertThat((Entity) null, Entity::state).isIn(State.READY)),
                "entity", "state", State.READY, null);
    }

    private static void assertFailure(GraphAssertionError error, String context, String property,
                                      Object expected, Object actual) {
        assertEquals(context, error.context());
        assertEquals(property, error.property());
        assertEquals(expected, error.expected());
        assertEquals(actual, error.actual());
    }
}
