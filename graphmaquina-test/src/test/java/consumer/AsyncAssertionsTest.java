package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.test.AsyncAssertions;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.TransitionFailureAssert;
import io.github.graphmaquina.test.fixtures.TestDomain.Entity;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/**
 * Contract of the bounded waits. The budget belongs to the test: when it expires the failure says the test
 * stopped waiting and the stage is left untouched, so a wait can never be mistaken for the engine's own
 * deadline. Every mismatch is read off the fields of the {@link GraphAssertionError}, not off its type.
 */
class AsyncAssertionsTest {

    private static final Duration BUDGET = Duration.ofMillis(50);

    private static Outcome.Applied<Entity, State> applied(Entity entity) {
        return new Outcome.Applied<>(entity, State.NEW, State.READY, List.of(), List.of());
    }

    // --- await: the stage carried an outcome ----------------------------------------------------------

    @Test
    void awaitHandsTheOutcomeToTheTypedView() {
        Entity entity = new Entity("e", State.NEW);
        CompletableFuture<Outcome<Entity, State>> stage = CompletableFuture.completedFuture(applied(entity));
        AsyncAssertions.await(stage, Duration.ofSeconds(2))
                .isApplied().movedTo(State.READY).hasEntitySameAs(entity);
    }

    @Test
    void awaitAcceptsAStageOfASubtypeOfOutcome() {
        Entity entity = new Entity("e", State.NEW);
        CompletableFuture<Outcome.Applied<Entity, State>> stage =
                CompletableFuture.completedFuture(applied(entity));
        AsyncAssertions.<Entity, State>await(stage, Duration.ofSeconds(2)).isApplied().movedTo(State.READY);
    }

    @Test
    void awaitReportsAnExceptionalCompletionAsADiscrepancy() {
        IllegalStateException boom = new IllegalStateException("engine down");
        CompletableFuture<Outcome<Entity, State>> stage = CompletableFuture.failedFuture(boom);
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> AsyncAssertions.await(stage, Duration.ofSeconds(2)));
        assertEquals("async test", error.context());
        assertEquals("outcome", error.property());
        assertEquals("normal result", error.expected());
        assertSame(boom, error.actual());
    }

    @Test
    void awaitReportsACancelledStageAsADiscrepancy() {
        CompletableFuture<Outcome<Entity, State>> stage = new CompletableFuture<>();
        assertTrue(stage.cancel(false));
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> AsyncAssertions.await(stage, Duration.ofSeconds(2)));
        assertEquals("outcome", error.property());
        assertEquals("normal result", error.expected());
        assertInstanceOf(CancellationException.class, error.actual());
    }

    @Test
    void anExpiredAwaitBudgetLeavesTheStageUntouched() {
        CompletableFuture<Outcome<Entity, State>> stage = new CompletableFuture<>();
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> AsyncAssertions.await(stage, BUDGET));
        assertEquals("async test", error.context());
        assertEquals("completion", error.property());
        assertEquals("within " + BUDGET, error.expected());
        assertEquals("test wait expired", error.actual());
        assertFalse(stage.isDone());
        assertFalse(stage.isCancelled());
        assertTrue(stage.complete(applied(new Entity("e", State.NEW))));
    }

    @Test
    void anInterruptedAwaitKeepsTheInterruptFlag() {
        CompletableFuture<Outcome<Entity, State>> stage = new CompletableFuture<>();
        Thread.currentThread().interrupt();
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> AsyncAssertions.await(stage, Duration.ofSeconds(30)));
        assertTrue(Thread.interrupted(), "the wait must leave the interrupt flag set");
        assertEquals("completion", error.property());
        assertEquals("completed", error.expected());
        assertEquals("test thread interrupted", error.actual());
        assertFalse(stage.isDone());
    }

    // --- awaitFailure: the stage carried a throwable ---------------------------------------------------

    @Test
    void awaitFailureUnwrapsTheExecutionExceptionOfItsOwnWait() {
        IllegalStateException boom = new IllegalStateException("engine down");
        CompletableFuture<Outcome<Entity, State>> stage = CompletableFuture.failedFuture(boom);
        TransitionFailureAssert failure = AsyncAssertions.awaitFailure(stage, Duration.ofSeconds(2));
        GraphAssertionError error = assertThrows(GraphAssertionError.class, failure::isAbort);
        assertSame(boom, error.actual());
    }

    @Test
    void awaitFailureAcceptsACancelledStageAsAnObservableFailure() {
        CompletableFuture<Outcome<Entity, State>> stage = new CompletableFuture<>();
        assertTrue(stage.cancel(false));
        TransitionFailureAssert failure = AsyncAssertions.awaitFailure(stage, Duration.ofSeconds(2));
        failure.hasNoCause();
        GraphAssertionError error = assertThrows(GraphAssertionError.class, failure::isAbort);
        assertInstanceOf(CancellationException.class, error.actual());
    }

    @Test
    void awaitFailureReportsAStageThatCompletedNormally() {
        Entity entity = new Entity("e", State.NEW);
        Outcome.Applied<Entity, State> outcome = applied(entity);
        CompletableFuture<Outcome<Entity, State>> stage = CompletableFuture.completedFuture(outcome);
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> AsyncAssertions.awaitFailure(stage, Duration.ofSeconds(2)));
        assertEquals("async test", error.context());
        assertEquals("failure", error.property());
        assertEquals("an exception", error.expected());
        assertSame(outcome, error.actual());
    }

    @Test
    void anExpiredAwaitFailureBudgetIsTheTestsOwnFailureAndLeavesTheStageUntouched() {
        CompletableFuture<Outcome<Entity, State>> stage = new CompletableFuture<>();
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> AsyncAssertions.awaitFailure(stage, BUDGET));
        assertEquals("completion", error.property());
        assertEquals("within " + BUDGET, error.expected());
        assertEquals("test wait expired", error.actual());
        assertFalse(stage.isDone());
        assertFalse(stage.isCancelled());
    }

    @Test
    void anInterruptedAwaitFailureKeepsTheInterruptFlag() {
        CompletableFuture<Outcome<Entity, State>> stage = new CompletableFuture<>();
        Thread.currentThread().interrupt();
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> AsyncAssertions.awaitFailure(stage, Duration.ofSeconds(30)));
        assertTrue(Thread.interrupted(), "the wait must leave the interrupt flag set");
        assertEquals("completion", error.property());
        assertEquals("completed", error.expected());
        assertEquals("test thread interrupted", error.actual());
        assertFalse(stage.isDone());
    }

    // --- the null subject, one case per public wait ----------------------------------------------------

    @Test
    void awaitOfANullStageFailsAsAnAssertion() {
        CompletionStage<Outcome<Entity, State>> missing = null;
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> AsyncAssertions.await(missing, Duration.ofSeconds(2)));
        assertEquals("async test", error.context());
        assertEquals("completion", error.property());
        assertEquals("a stage to await", error.expected());
        assertNull(error.actual());
    }

    @Test
    void awaitFailureOfANullStageFailsAsAnAssertion() {
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> AsyncAssertions.awaitFailure(null, Duration.ofSeconds(2)));
        assertEquals("async test", error.context());
        assertEquals("failure", error.property());
        assertEquals("a stage to await", error.expected());
        assertNull(error.actual());
    }

    // --- the budget is an argument of the test, so a bad one is a programming error --------------------

    @Test
    void aNullBudgetIsRejectedBeforeTheStageIsEvenGuarded() {
        assertThrows(NullPointerException.class, () -> AsyncAssertions.await(null, null));
        assertThrows(NullPointerException.class, () -> AsyncAssertions.awaitFailure(null, null));
    }

    @Test
    void aBudgetThatIsNotPositiveIsRejected() {
        CompletableFuture<Outcome<Entity, State>> stage = new CompletableFuture<>();
        assertEquals("the budget must be positive, not PT0S",
                assertThrows(IllegalArgumentException.class,
                        () -> AsyncAssertions.await(stage, Duration.ZERO)).getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> AsyncAssertions.await(stage, Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> AsyncAssertions.awaitFailure(stage, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> AsyncAssertions.awaitFailure(stage, Duration.ofSeconds(-1)));
    }

    @Test
    void aBudgetThatDoesNotFitInNanosecondsIsRejected() {
        CompletableFuture<Outcome<Entity, State>> stage = new CompletableFuture<>();
        Duration tooLong = Duration.ofSeconds(Long.MAX_VALUE);
        assertInstanceOf(ArithmeticException.class,
                assertThrows(IllegalArgumentException.class,
                        () -> AsyncAssertions.await(stage, tooLong)).getCause());
        assertThrows(IllegalArgumentException.class,
                () -> AsyncAssertions.awaitFailure(stage, tooLong));
    }
}
