package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.DocGraphs.docs;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.EligibilityCheckException;
import io.github.graphmaquina.error.ReentrantTransitionException;
import io.github.graphmaquina.error.UninitializedStateException;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocGraphs;
import io.github.graphmaquina.fixtures.DocMachines;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.GuardProbe;
import io.github.graphmaquina.fixtures.OrderTransition;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * An eligibility check that cannot reach an answer throws an {@link EligibilityCheckException}, never a
 * {@link CallFailedException}: with the guard that threw, its stage's unwrapped failure, a null verdict, a wait past the
 * deadline or a null state, it names the state it read and the guard in flight when it knew them, and its cause. An
 * Error propagates as itself. A check is refused before it starts where no call may start, and while it runs no call
 * may start either.
 */
class EligibilityFailureTest {

    /** The doc machine of a registry of its own, whose SUBMIT edge {@code submit} shapes. */
    private static StateMachine<Doc, DocStatus, DocTransition> machine(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit) {
        return DocMachines.registry(docs(submit)).of(DOCS);
    }

    /** {@link #machine} with the asyncTimeout {@code timeout}, which an asynchronous guard needs. */
    private static StateMachine<Doc, DocStatus, DocTransition> timed(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit, Duration timeout) {
        return DocMachines.registry(docs(submit), timeout).of(DOCS);
    }

    /** What checking SUBMIT for a doc in DRAFT on {@code machine} throws. */
    private static EligibilityCheckException failure(StateMachine<Doc, DocStatus, DocTransition> machine) {
        return assertThrows(EligibilityCheckException.class, () -> machine.eligibility(new Doc("1", DRAFT), SUBMIT));
    }

    /** A machine whose guard {@code boom} throws {@code thrown}. */
    private static StateMachine<Doc, DocStatus, DocTransition> throwing(RuntimeException thrown) {
        return machine((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .guard("boom", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                    throw thrown;
                }));
    }

    /** A machine whose asynchronous guard {@code remote} completes its stage with {@code CompletionException(cause)}. */
    private static StateMachine<Doc, DocStatus, DocTransition> asyncFailing(Throwable cause) {
        return timed((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .asyncGuard("remote", (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                        CompletableFuture.<GuardResult>failedFuture(new CompletionException(cause))),
                Duration.ofSeconds(10));
    }

    /** A machine whose guard {@code silent} answers null. */
    private static StateMachine<Doc, DocStatus, DocTransition> nullVerdict() {
        return machine((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .guard("silent", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> null));
    }

    /** A machine whose asynchronous guard {@code stuck} never completes, under a deadline of 50 ms. */
    private static StateMachine<Doc, DocStatus, DocTransition> neverCompletes() {
        return timed((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .asyncGuard("stuck", (GuardContext<Doc, DocStatus, DocTransition> ctx) ->
                        new CompletableFuture<GuardResult>()),
                Duration.ofMillis(50));
    }

    /** A machine whose state getter answers null. */
    private static StateMachine<Doc, DocStatus, DocTransition> nullState() {
        return MachineRegistry.builder()
                .add(StateMachines.from(DocGraphs.plain()).state((Doc doc) -> (DocStatus) null, Doc::setStatus)
                        .identity(Doc::id))
                .build()
                .of(DOCS);
    }

    @Test
    void guardThatThrowsFailsTheCheck() {
        IllegalStateException boom = new IllegalStateException("boom");
        Doc doc = new Doc("1", DRAFT);
        EligibilityCheckException error = assertThrows(EligibilityCheckException.class,
                () -> throwing(boom).eligibility(doc, SUBMIT));
        assertSame(boom, error.getCause());
        assertEquals(Optional.of("boom"), error.guard());
        assertEquals(Optional.of(DRAFT), error.from());
        assertEquals(List.of(DOCS, doc, SUBMIT), List.of(error.key(), error.entity(), error.transition()));
        assertEquals("eligibility of SUBMIT in Doc/DocStatus DRAFT could not be decided: the guard boom threw"
                + " java.lang.IllegalStateException: boom", error.getMessage());
    }

    @Test
    void asyncGuardFailureIsUnwrapped() {
        IOException down = new IOException("down");
        EligibilityCheckException error = failure(asyncFailing(down));
        assertSame(down, error.getCause());
        assertEquals(Optional.of("remote"), error.guard());
        assertEquals(Optional.of(DRAFT), error.from());
    }

    @Test
    void nullVerdictFailsTheCheck() {
        EligibilityCheckException error = failure(nullVerdict());
        assertNull(error.getCause());
        assertEquals(Optional.of("silent"), error.guard());
        assertEquals("eligibility of SUBMIT in Doc/DocStatus DRAFT could not be decided: the guard silent returned"
                + " null", error.getMessage());
    }

    @Test
    void asyncTimeoutFailsTheCheck() {
        EligibilityCheckException error = failure(neverCompletes());
        assertInstanceOf(TimeoutException.class, error.getCause());
        assertEquals(Optional.of("stuck"), error.guard());
        assertEquals(Optional.of(DRAFT), error.from());
    }

    @Test
    void nullStateFailsTheCheck() {
        EligibilityCheckException error = failure(nullState());
        assertInstanceOf(UninitializedStateException.class, error.getCause());
        assertEquals(Optional.empty(), error.from());
        assertEquals(Optional.empty(), error.guard());
        assertTrue(error.getMessage().startsWith("eligibility of SUBMIT in Doc/DocStatus ? could not be decided:"
                + " reading the entity threw "), error.getMessage());
    }

    @Test
    void errorPropagatesAsItself() {
        AssertionError fatal = new AssertionError("fatal");
        StateMachine<Doc, DocStatus, DocTransition> m = machine(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("fatal", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw fatal;
                        }));
        assertSame(fatal, assertThrows(AssertionError.class, () -> m.eligibility(new Doc("1", DRAFT), SUBMIT)));
    }

    static Stream<Arguments> failingChecks() {
        return Stream.of(
                Arguments.of(Named.of("a guard that throws", throwing(new IllegalStateException("boom"))),
                        EligibilityCheckException.class),
                Arguments.of(Named.of("a stage that fails", asyncFailing(new IOException("down"))),
                        EligibilityCheckException.class),
                Arguments.of(Named.of("a null verdict", nullVerdict()), EligibilityCheckException.class),
                Arguments.of(Named.of("a wait past the deadline", neverCompletes()), EligibilityCheckException.class),
                Arguments.of(Named.of("a null state", nullState()), EligibilityCheckException.class),
                Arguments.of(Named.of("an Error", throwingError()), AssertionError.class));
    }

    /** A machine whose guard throws an AssertionError. */
    private static StateMachine<Doc, DocStatus, DocTransition> throwingError() {
        return machine((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .guard("fatal", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                    throw new AssertionError("fatal");
                }));
    }

    @ParameterizedTest
    @MethodSource("failingChecks")
    void checkIsNeverACallFailure(StateMachine<Doc, DocStatus, DocTransition> machine,
                                  Class<? extends Throwable> expected) {
        Throwable thrown = assertThrows(Throwable.class, () -> machine.eligibility(new Doc("1", DRAFT), SUBMIT));
        assertInstanceOf(expected, thrown);
        assertFalse(thrown instanceof CallFailedException);
    }

    @Test
    void checkFromAGuardIsReentrant() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        List<ReentrantTransitionException> caught = new ArrayList<>();
        Doc other = new Doc("2", DRAFT);
        self.set(machine((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .guard("asks", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                    caught.add(assertThrows(ReentrantTransitionException.class,
                            () -> self.get().eligibility(other, DISCARD)));
                    return GuardResult.pass();
                })));
        assertInstanceOf(Outcome.Applied.class, self.get().execute(new Doc("1", DRAFT), SUBMIT));
        assertEquals(1, caught.size());
        assertSame(other, caught.get(0).entity());
    }

    @Test
    void checkFromAnActionIsReentrant() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        List<ReentrantTransitionException> caught = new ArrayList<>();
        Doc other = new Doc("2", DRAFT);
        self.set(machine((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .action("asks", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                    caught.add(assertThrows(ReentrantTransitionException.class,
                            () -> self.get().eligibility(other, DISCARD, "input")));
                    return ActionOutcome.ok();
                })));
        assertInstanceOf(Outcome.Applied.class, self.get().execute(new Doc("1", DRAFT), SUBMIT));
        assertEquals(1, caught.size());
        assertSame(other, caught.get(0).entity());
    }

    @Test
    void checkFromAnObserverIsReentrant() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        List<ReentrantTransitionException> caught = new ArrayList<>();
        Doc other = new Doc("2", DRAFT);
        MachineObserver asking = new MachineObserver() {
            @Override
            public void onStarted(MachineCall call) {
                caught.add(assertThrows(ReentrantTransitionException.class,
                        () -> self.get().eligibility(other, DISCARD)));
            }
        };
        self.set(MachineRegistry.builder()
                .add(StateMachines.from(DocGraphs.plain()).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .observer(asking)
                .build()
                .of(DOCS));
        assertInstanceOf(Outcome.Applied.class, self.get().execute(new Doc("1", DRAFT), SUBMIT));
        assertEquals(1, caught.size());
        assertSame(other, caught.get(0).entity());
    }

    /** While a check runs, its guard can start neither a call nor another check; the one it lets through fails it. */
    @Test
    void executeFromAGuardDuringACheckIsReentrant() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> self = new AtomicReference<>();
        AtomicReference<ReentrantTransitionException> caught = new AtomicReference<>();
        Doc other = new Doc("2", DRAFT);
        self.set(machine((TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                .guard("moves", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                    assertThrows(ReentrantTransitionException.class, () -> self.get().eligibility(other, DISCARD));
                    caught.set(assertThrows(ReentrantTransitionException.class,
                            () -> self.get().execute(other, DISCARD)));
                    throw caught.get();
                })));
        EligibilityCheckException error = failure(self.get());
        assertSame(caught.get(), error.getCause());
        assertEquals(Optional.of("moves"), error.guard());
        assertEquals(DRAFT, other.status());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void refusalsBeforeStart() {
        GuardProbe<Doc, DocStatus, DocTransition> guard = new GuardProbe<>();
        StateMachine<Doc, DocStatus, DocTransition> m = machine(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("counts", guard));
        Doc doc = new Doc("1", DRAFT);
        assertEquals("entity is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> m.eligibility(null, SUBMIT)).getMessage());
        assertEquals("transition is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> m.eligibility(doc, null, "input")).getMessage());
        assertEquals("input is marked non-null but is null",
                assertThrows(NullPointerException.class, () -> m.eligibility(doc, SUBMIT, null)).getMessage());
        StateMachine raw = m;
        assertEquals("transition must belong to io.github.graphmaquina.fixtures.DocTransition: CHARGE",
                assertThrows(IllegalArgumentException.class,
                        () -> raw.eligibility(doc, OrderTransition.CHARGE)).getMessage());
        assertEquals(0, guard.calls);
    }
}
