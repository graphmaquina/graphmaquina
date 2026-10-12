package consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import io.github.graphmaquina.test.fixtures.TestDomain.Id;
import io.github.graphmaquina.test.fixtures.TestDomain.State;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Contract of the bounded waits. The budget belongs to the test: when it expires the failure says the test
 * stopped waiting and the stage is left untouched, so a wait can never be mistaken for the engine's own
 * deadline. Every mismatch is read off the fields of the {@link GraphAssertionError}, not off its type.
 */
class AsyncAssertionsTest {

    private static final Duration BUDGET = Duration.ofMillis(50);

    private static Outcome.Applied<Entity, State, Id> applied(Entity entity) {
        return new Outcome.Applied<>(entity, State.NEW, Id.PREPARE, State.READY, List.of(), List.of());
    }

    // --- await: the stage carried an outcome ----------------------------------------------------------

    @Test
    void awaitHandsTheOutcomeToTheTypedView() {
        Entity entity = new Entity("e", State.NEW);
        CompletableFuture<Outcome<Entity, State, Id>> stage = CompletableFuture.completedFuture(applied(entity));
        AsyncAssertions.await(stage, Duration.ofSeconds(2))
                .isApplied().movedTo(State.READY).hasEntitySameAs(entity);
    }

    @Test
    void awaitAcceptsAStageOfASubtypeOfOutcome() {
        Entity entity = new Entity("e", State.NEW);
        CompletableFuture<Outcome.Applied<Entity, State, Id>> stage =
                CompletableFuture.completedFuture(applied(entity));
        AsyncAssertions.<Entity, State, Id>await(stage, Duration.ofSeconds(2)).isApplied().movedTo(State.READY);
    }

    @Test
    void awaitReportsAnExceptionalCompletionAsADiscrepancy() {
        IllegalStateException boom = new IllegalStateException("engine down");
        CompletableFuture<Outcome<Entity, State, Id>> stage = CompletableFuture.failedFuture(boom);
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> AsyncAssertions.await(stage, Duration.ofSeconds(2)));
        assertEquals("async test", error.context());
        assertEquals("outcome", error.property());
        assertEquals("normal result", error.expected());
        assertSame(boom, error.actual());
    }

    /** The throwable is the reason the check failed, so a test report must be able to print where it was thrown. */
    @Test
    void awaitChainsTheThrowableItReportsAsTheCauseOfTheFailure() {
        IllegalStateException boom = new IllegalStateException("engine down");
        CompletableFuture<Outcome<Entity, State, Id>> failed = CompletableFuture.failedFuture(boom);
        assertSame(boom, assertThrows(GraphAssertionError.class,
                () -> AsyncAssertions.await(failed, Duration.ofSeconds(2))).getCause());
        CompletableFuture<Outcome<Entity, State, Id>> cancelled = new CompletableFuture<>();
        assertTrue(cancelled.cancel(false));
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> AsyncAssertions.await(cancelled, Duration.ofSeconds(2)));
        assertSame(error.actual(), error.getCause());
    }

    @Test
    void awaitReportsACancelledStageAsADiscrepancy() {
        CompletableFuture<Outcome<Entity, State, Id>> stage = new CompletableFuture<>();
        assertTrue(stage.cancel(false));
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> AsyncAssertions.await(stage, Duration.ofSeconds(2)));
        assertEquals("outcome", error.property());
        assertEquals("normal result", error.expected());
        assertInstanceOf(CancellationException.class, error.actual());
    }

    @Test
    void anExpiredAwaitBudgetLeavesTheStageUntouched() {
        CompletableFuture<Outcome<Entity, State, Id>> stage = new CompletableFuture<>();
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
        CompletableFuture<Outcome<Entity, State, Id>> stage = new CompletableFuture<>();
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
        CompletableFuture<Outcome<Entity, State, Id>> stage = CompletableFuture.failedFuture(boom);
        TransitionFailureAssert failure = AsyncAssertions.awaitFailure(stage, Duration.ofSeconds(2));
        GraphAssertionError error = assertThrows(GraphAssertionError.class, failure::isAbort);
        assertSame(boom, error.actual());
    }

    @Test
    void awaitFailureAcceptsACancelledStageAsAnObservableFailure() {
        CompletableFuture<Outcome<Entity, State, Id>> stage = new CompletableFuture<>();
        assertTrue(stage.cancel(false));
        TransitionFailureAssert failure = AsyncAssertions.awaitFailure(stage, Duration.ofSeconds(2));
        failure.hasNoCause();
        GraphAssertionError error = assertThrows(GraphAssertionError.class, failure::isAbort);
        assertInstanceOf(CancellationException.class, error.actual());
    }

    @Test
    void awaitFailureReportsAStageThatCompletedNormally() {
        Entity entity = new Entity("e", State.NEW);
        Outcome.Applied<Entity, State, Id> outcome = applied(entity);
        CompletableFuture<Outcome<Entity, State, Id>> stage = CompletableFuture.completedFuture(outcome);
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> AsyncAssertions.awaitFailure(stage, Duration.ofSeconds(2)));
        assertEquals("async test", error.context());
        assertEquals("failure", error.property());
        assertEquals("an exception", error.expected());
        assertSame(outcome, error.actual());
    }

    @Test
    void anExpiredAwaitFailureBudgetIsTheTestsOwnFailureAndLeavesTheStageUntouched() {
        CompletableFuture<Outcome<Entity, State, Id>> stage = new CompletableFuture<>();
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
        CompletableFuture<Outcome<Entity, State, Id>> stage = new CompletableFuture<>();
        Thread.currentThread().interrupt();
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> AsyncAssertions.awaitFailure(stage, Duration.ofSeconds(30)));
        assertTrue(Thread.interrupted(), "the wait must leave the interrupt flag set");
        assertEquals("completion", error.property());
        assertEquals("completed", error.expected());
        assertEquals("test thread interrupted", error.actual());
        assertFalse(stage.isDone());
    }

    // --- a stage that cannot be converted to a future ---------------------------------------------------

    /**
     * {@code CompletionStage#toCompletableFuture} lets a stage refuse the conversion. The engine waits on any
     * stage through its own callbacks, and so do these waits: such a stage is awaited, not crashed on.
     */
    @Test
    void awaitReadsAStageThatCannotBeConvertedToAFuture() {
        Entity entity = new Entity("e", State.NEW);
        CompletionStage<Outcome<Entity, State, Id>> stage =
                isolated(CompletableFuture.completedFuture(applied(entity)));
        assertThrows(UnsupportedOperationException.class, stage::toCompletableFuture);
        AsyncAssertions.await(stage, Duration.ofSeconds(2))
                .isApplied().movedTo(State.READY).hasEntitySameAs(entity);
    }

    @Test
    void awaitReportsTheFailureOfAStageThatCannotBeConvertedToAFuture() {
        IllegalStateException boom = new IllegalStateException("engine down");
        CompletionStage<Outcome<Entity, State, Id>> failed = isolated(CompletableFuture.failedFuture(boom));
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> AsyncAssertions.await(failed, Duration.ofSeconds(2)));
        assertEquals("outcome", error.property());
        assertEquals("normal result", error.expected());
        assertSame(boom, error.actual());
        CompletableFuture<Outcome<Entity, State, Id>> cancelledInside = new CompletableFuture<>();
        assertTrue(cancelledInside.cancel(false));
        GraphAssertionError cancelled = assertThrows(GraphAssertionError.class,
                () -> AsyncAssertions.await(isolated(cancelledInside), Duration.ofSeconds(2)));
        assertInstanceOf(CancellationException.class, cancelled.actual());
    }

    @Test
    void awaitFailureHandsOnTheCauseOfAStageThatCannotBeConvertedToAFuture() {
        IllegalStateException boom = new IllegalStateException("engine down");
        CompletionStage<Outcome<Entity, State, Id>> failed = isolated(CompletableFuture.failedFuture(boom));
        TransitionFailureAssert failure = AsyncAssertions.awaitFailure(failed, Duration.ofSeconds(2));
        assertSame(boom, assertThrows(GraphAssertionError.class, failure::isAbort).actual());
        CompletableFuture<Outcome<Entity, State, Id>> cancelledInside = new CompletableFuture<>();
        assertTrue(cancelledInside.cancel(false));
        AsyncAssertions.awaitFailure(isolated(cancelledInside), Duration.ofSeconds(2)).hasNoCause();
        CompletionStage<Outcome<Entity, State, Id>> completed =
                isolated(CompletableFuture.completedFuture(applied(new Entity("e", State.NEW))));
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> AsyncAssertions.awaitFailure(completed, Duration.ofSeconds(2)));
        assertEquals("an exception", error.expected());
    }

    @Test
    void anExpiredBudgetLeavesAStageThatCannotBeConvertedToAFutureUntouched() {
        CompletableFuture<Outcome<Entity, State, Id>> inside = new CompletableFuture<>();
        GraphAssertionError error = assertThrows(GraphAssertionError.class,
                () -> AsyncAssertions.await(isolated(inside), BUDGET));
        assertEquals("test wait expired", error.actual());
        assertFalse(inside.isDone());
        assertFalse(inside.isCancelled());
        assertTrue(inside.complete(applied(new Entity("e", State.NEW))));
    }

    // --- a throwable whose message cannot be read -------------------------------------------------------

    /**
     * A stage that failed with a throwable whose message cannot be read, bare or inside a CompletionException, is
     * still reported as a discrepancy, which shows the throwable by its class and identity and chains it as its
     * cause.
     */
    @Test
    void awaitReportsAThrowableWhoseMessageCannotBeRead() {
        UnreadableFailure failure = new UnreadableFailure();
        for (CompletableFuture<Outcome<Entity, State, Id>> stage : failedWith(failure)) {
            GraphAssertionError error = assertThrows(GraphAssertionError.class,
                    () -> AsyncAssertions.await(stage, Duration.ofSeconds(2)));
            assertSame(failure, error.actual());
            assertSame(failure, error.getCause());
            assertEquals("async test: outcome expected <normal result> but was <" + shown(failure) + ">",
                    error.getMessage());
        }
    }

    /** A throwable whose message cannot be read, bare or inside a CompletionException, is handed on. */
    @Test
    void awaitFailureHandsOnAThrowableWhoseMessageCannotBeRead() {
        UnreadableFailure failure = new UnreadableFailure();
        for (CompletableFuture<Outcome<Entity, State, Id>> stage : failedWith(failure)) {
            TransitionFailureAssert handedOn = AsyncAssertions.awaitFailure(stage, Duration.ofSeconds(2));
            assertSame(failure, assertThrows(GraphAssertionError.class, handedOn::isAbort).actual());
        }
    }

    /**
     * The callback a wait leaves on a stage reads nothing of the throwable, so the thread that completes the stage
     * with one whose message cannot be read, while the wait is pending, returns normally, and the wait hands that
     * throwable on.
     */
    @Test
    void completingAWaitedStageWithAThrowableWhoseMessageCannotBeReadThrowsNothing() throws Exception {
        UnreadableFailure failure = new UnreadableFailure();
        CompletableFuture<Outcome<Entity, State, Id>> stage = new CompletableFuture<>();
        AtomicReference<Object> ended = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                ended.set(AsyncAssertions.awaitFailure(stage, Duration.ofSeconds(10)));
            } catch (Throwable thrown) {
                ended.set(thrown);
            }
        });
        waiter.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (stage.getNumberOfDependents() == 0 && System.nanoTime() - deadline < 0) {
            Thread.sleep(1);
        }
        assertEquals(1, stage.getNumberOfDependents(), "the wait did not register its callback");
        assertTrue(assertDoesNotThrow(() -> stage.completeExceptionally(failure)));
        waiter.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(waiter.isAlive(), "the wait did not end");
        TransitionFailureAssert handedOn = assertInstanceOf(TransitionFailureAssert.class, ended.get());
        assertSame(failure, assertThrows(GraphAssertionError.class, handedOn::isAbort).actual());
    }

    // --- the null subject, one case per public wait ----------------------------------------------------

    @Test
    void awaitOfANullStageFailsAsAnAssertion() {
        CompletionStage<Outcome<Entity, State, Id>> missing = null;
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
        CompletableFuture<Outcome<Entity, State, Id>> stage = new CompletableFuture<>();
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
        CompletableFuture<Outcome<Entity, State, Id>> stage = new CompletableFuture<>();
        Duration tooLong = Duration.ofSeconds(Long.MAX_VALUE);
        assertInstanceOf(ArithmeticException.class,
                assertThrows(IllegalArgumentException.class,
                        () -> AsyncAssertions.await(stage, tooLong)).getCause());
        assertThrows(IllegalArgumentException.class,
                () -> AsyncAssertions.awaitFailure(stage, tooLong));
    }

    /** A failure whose message cannot be read, as one built from a detached lazy entity's field. */
    private static final class UnreadableFailure extends RuntimeException {
        @Override
        public String getMessage() {
            throw new IllegalStateException("the message cannot be read");
        }
    }

    /** Two stages that failed with {@code failure}: bare, and inside a CompletionException. */
    private static List<CompletableFuture<Outcome<Entity, State, Id>>> failedWith(Throwable failure) {
        CompletableFuture<Outcome<Entity, State, Id>> wrapped = new CompletableFuture<>();
        wrapped.completeExceptionally(new CompletionException("wrapped", failure));
        return List.of(CompletableFuture.failedFuture(failure), wrapped);
    }

    /** How a failure report shows {@code failure}: its class and identity, and what printing it threw. */
    private static String shown(UnreadableFailure failure) {
        return UnreadableFailure.class.getName() + "@" + Integer.toHexString(System.identityHashCode(failure))
                + " (toString() threw java.lang.IllegalStateException: the message cannot be read)";
    }

    /** A stage that answers only through its own callbacks, as the interface allows: it refuses the conversion. */
    @SuppressWarnings("unchecked")
    private static <T> CompletionStage<T> isolated(CompletableFuture<T> inside) {
        return (CompletionStage<T>) Proxy.newProxyInstance(AsyncAssertionsTest.class.getClassLoader(),
                new Class<?>[] {CompletionStage.class}, (Object proxy, Method method, Object[] arguments) -> {
                    if (method.getName().equals("toCompletableFuture")) {
                        throw new UnsupportedOperationException("this stage does not interoperate");
                    }
                    try {
                        return method.invoke(inside, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}
