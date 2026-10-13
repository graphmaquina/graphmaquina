package io.github.graphmaquina.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Month;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * The checks {@link TransitionSpec} makes when an edge is built, its recovery rules included, and the kind, key and
 * asynchrony it derives from its parts.
 */
class TransitionSpecTest {

    private static final ActionRef<String, DayOfWeek, Month> SYNC =
            new ActionRef.Sync<>("A", (TransitionContext<String, DayOfWeek, Month> ctx) -> ActionOutcome.ok());
    private static final ActionRef<String, DayOfWeek, Month> ASYNC =
            new ActionRef.Async<>("B", (TransitionContext<String, DayOfWeek, Month> ctx) ->
                    CompletableFuture.completedFuture(ActionOutcome.ok()));

    /** A failure kind for the recovery rules below. */
    record Unavailable() implements FailureKind { }

    /** A second failure kind. */
    record Rejected() implements FailureKind { }

    private static TransitionSpec<String, DayOfWeek, Month> spec(Optional<ActionRef<String, DayOfWeek, Month>> action,
                                                                 List<GuardRef<String, DayOfWeek, Month>> guards,
                                                                 Optional<Month> onFailure, Optional<Duration> timeout) {
        return new TransitionSpec<>(DayOfWeek.MONDAY, Month.JANUARY, DayOfWeek.TUESDAY, action, guards, onFailure,
                List.of(), timeout, Optional.empty());
    }

    private static TransitionSpec<String, DayOfWeek, Month> withRules(
            Optional<ActionRef<String, DayOfWeek, Month>> action, List<Class<? extends Throwable>> failsOn,
            List<RecoveryRule<Month>> rules) {
        return new TransitionSpec<>(DayOfWeek.MONDAY, Month.JANUARY, DayOfWeek.TUESDAY, action, List.of(),
                Optional.empty(), failsOn, Optional.empty(), Optional.empty(), rules);
    }

    private static RecoveryRule<Month> rule(Class<? extends FailureKind> kind, Month transition,
                                            List<Class<? extends Throwable>> failsOn) {
        return new RecoveryRule<>(kind, transition, failsOn);
    }

    @Test
    void anEdgeWithoutAnActionIsAMoveAndKeepsItsKey() {
        TransitionSpec<String, DayOfWeek, Month> move = spec(Optional.empty(), List.of(), Optional.empty(), Optional.empty());
        assertEquals(TransitionKind.MOVE, move.kind());
        assertEquals(new TransitionKey<>(DayOfWeek.MONDAY, Month.JANUARY), move.key());
        assertEquals(TransitionKind.ACTION, spec(Optional.of(SYNC), List.of(), Optional.empty(), Optional.empty()).kind());
    }

    @Test
    void onFailureAndFailsOnRequireAnAction() {
        assertThrows(IllegalArgumentException.class,
                () -> spec(Optional.empty(), List.of(), Optional.of(Month.MARCH), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new TransitionSpec<String, DayOfWeek, Month>(
                DayOfWeek.MONDAY, Month.JANUARY, DayOfWeek.TUESDAY, Optional.empty(), List.of(), Optional.empty(),
                List.of(IllegalStateException.class), Optional.empty(), Optional.empty()));
    }

    @Test
    void timeoutOnlyOnAsyncActionAndPositive() {
        assertThrows(IllegalArgumentException.class,
                () -> spec(Optional.of(SYNC), List.of(), Optional.empty(), Optional.of(Duration.ofSeconds(1))));
        assertThrows(IllegalArgumentException.class,
                () -> spec(Optional.of(ASYNC), List.of(), Optional.empty(), Optional.of(Duration.ZERO)));
        TransitionSpec<String, DayOfWeek, Month> ok =
                spec(Optional.of(ASYNC), List.of(), Optional.empty(), Optional.of(Duration.ofSeconds(1)));
        assertTrue(ok.isAsync());
    }

    @Test
    void asyncGuardMakesTheEdgeAsync() {
        GuardRef<String, DayOfWeek, Month> guard = new GuardRef.Async<>("G",
                (GuardContext<String, DayOfWeek, Month> ctx) -> CompletableFuture.completedFuture(GuardResult.pass()));
        assertTrue(spec(Optional.empty(), List.of(guard), Optional.empty(), Optional.empty()).isAsync());
        assertFalse(spec(Optional.empty(), List.of(), Optional.empty(), Optional.empty()).isAsync());
    }

    /** A self-loop is an ordinary edge: it needs no special kind and no special construction. */
    @Test
    void aSelfLoopIsAnOrdinaryEdge() {
        TransitionSpec<String, DayOfWeek, Month> loop = new TransitionSpec<>(DayOfWeek.MONDAY, Month.JANUARY,
                DayOfWeek.MONDAY, Optional.of(SYNC), List.of(), Optional.empty(), List.of(), Optional.empty(),
                Optional.empty());
        assertEquals(DayOfWeek.MONDAY, loop.to());
        assertEquals(TransitionKind.ACTION, loop.kind());
    }

    /**
     * Every edge may carry a hook: a plain move, an edge with a synchronous action and one with an asynchronous
     * action each keep the hook and their action. An edge built without a hook reports none.
     */
    @Test
    void afterCommitIsAllowedOnEveryEdge() {
        HookRef<String, DayOfWeek, Month> hook = HookRef.of("H", (String e) -> { });
        List<Optional<ActionRef<String, DayOfWeek, Month>>> actions = List.of(Optional.empty(), Optional.of(SYNC),
                Optional.of(ASYNC));
        for (Optional<ActionRef<String, DayOfWeek, Month>> action : actions) {
            TransitionSpec<String, DayOfWeek, Month> edge = new TransitionSpec<>(DayOfWeek.MONDAY, Month.JANUARY,
                    DayOfWeek.TUESDAY, action, List.of(), Optional.empty(), List.of(), Optional.empty(),
                    Optional.of(hook));
            assertSame(hook, edge.afterCommit().orElseThrow());
            assertEquals(action, edge.action());
        }
        assertTrue(spec(Optional.empty(), List.of(), Optional.empty(), Optional.empty()).afterCommit().isEmpty());
    }

    @Test
    void recoveryRulesRequireAnAction() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> withRules(
                Optional.empty(), List.of(), List.of(rule(Unavailable.class, Month.MARCH, List.of()))));
        assertEquals("MONDAY -JANUARY-> TUESDAY: recovery rules are only allowed with an action", error.getMessage());
    }

    @Test
    void aFailureKindDeclaredByTwoRulesIsRefused() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> withRules(
                Optional.of(SYNC), List.of(), List.of(rule(Unavailable.class, Month.MARCH, List.of()),
                        rule(Unavailable.class, Month.APRIL, List.of(IOException.class)))));
        assertEquals("MONDAY -JANUARY-> TUESDAY: failure kind " + Unavailable.class.getName()
                + " is declared by more than one recovery rule", error.getMessage());
    }

    @Test
    void aThrowableTypeDeclaredByTwoRulesOrByARuleAndFailsOnIsRefused() {
        String expected = "MONDAY -JANUARY-> TUESDAY: java.io.IOException is declared more than once across"
                + " recovery rules and failsOn";
        IllegalArgumentException twoRules = assertThrows(IllegalArgumentException.class, () -> withRules(
                Optional.of(SYNC), List.of(), List.of(rule(Unavailable.class, Month.MARCH, List.of(IOException.class)),
                        rule(Rejected.class, Month.APRIL, List.of(IOException.class)))));
        assertEquals(expected, twoRules.getMessage());
        IllegalArgumentException ruleAndFailsOn = assertThrows(IllegalArgumentException.class, () -> withRules(
                Optional.of(SYNC), List.of(IOException.class),
                List.of(rule(Unavailable.class, Month.MARCH, List.of(IOException.class)))));
        assertEquals(expected, ruleAndFailsOn.getMessage());
    }

    /** Only the same class twice is a repetition: a supertype and its subtype may sit in two rules and in failsOn. */
    @Test
    void aSupertypeAndItsSubtypesMayBeDeclaredAcrossRulesAndFailsOn() {
        List<RecoveryRule<Month>> rules = List.of(rule(Unavailable.class, Month.MARCH, List.of(IOException.class)),
                rule(Rejected.class, Month.APRIL, List.of(SocketTimeoutException.class)));
        TransitionSpec<String, DayOfWeek, Month> spec = withRules(Optional.of(SYNC),
                List.of(FileNotFoundException.class), rules);
        assertEquals(rules, spec.recoveryRules());
        assertEquals(List.of(FileNotFoundException.class), spec.failsOn());
    }

    /** failsOn keeps tolerating its own repetitions, and keeps listing only its own types. */
    @Test
    void failsOnMayRepeatItsOwnTypesWithRulesOrWithout() {
        List<Class<? extends Throwable>> repeated = List.of(IllegalStateException.class, IllegalStateException.class);
        assertEquals(repeated, withRules(Optional.of(SYNC), repeated, List.of()).failsOn());
        List<RecoveryRule<Month>> rules = List.of(rule(Unavailable.class, Month.MARCH, List.of(IOException.class)));
        TransitionSpec<String, DayOfWeek, Month> spec = withRules(Optional.of(SYNC), repeated, rules);
        assertEquals(repeated, spec.failsOn());
        assertEquals(rules, spec.recoveryRules());
    }

    @Test
    void theRulesAreCopiedAndExposedReadOnly() {
        RecoveryRule<Month> unavailable = rule(Unavailable.class, Month.MARCH, List.of());
        RecoveryRule<Month> rejected = rule(Rejected.class, Month.APRIL, List.of());
        List<RecoveryRule<Month>> source = new ArrayList<>(List.of(unavailable));
        TransitionSpec<String, DayOfWeek, Month> spec = withRules(Optional.of(SYNC), List.of(), source);
        source.add(rejected);
        assertEquals(List.of(unavailable), spec.recoveryRules());
        assertThrows(UnsupportedOperationException.class, () -> spec.recoveryRules().add(rejected));
    }

    @Test
    void theConstructorWithoutRulesDeclaresNone() {
        TransitionSpec<String, DayOfWeek, Month> plain = spec(Optional.of(SYNC), List.of(), Optional.empty(),
                Optional.empty());
        assertEquals(List.of(), plain.recoveryRules());
        assertEquals(withRules(Optional.of(SYNC), List.of(), List.of()), plain);
    }

    @Test
    void aNullRuleListOrRuleIsRefused() {
        NullPointerException list = assertThrows(NullPointerException.class,
                () -> withRules(Optional.of(SYNC), List.of(), null));
        assertTrue(list.getMessage().startsWith("recoveryRules"), list.getMessage());
        List<RecoveryRule<Month>> withNull = Arrays.asList(rule(Unavailable.class, Month.MARCH, List.of()), null);
        assertThrows(NullPointerException.class, () -> withRules(Optional.of(SYNC), List.of(), withNull));
        assertThrows(NullPointerException.class, () -> withRules(Optional.empty(), List.of(), withNull));
    }

    @Test
    void actionAndTimeoutValidationPrecedeDuplicateRuleValidation() {
        List<RecoveryRule<Month>> duplicateKinds = List.of(rule(Unavailable.class, Month.MARCH, List.of()),
                rule(Unavailable.class, Month.APRIL, List.of()));
        IllegalArgumentException withoutAction = assertThrows(IllegalArgumentException.class,
                () -> withRules(Optional.empty(), List.of(), duplicateKinds));
        assertEquals("MONDAY -JANUARY-> TUESDAY: recovery rules are only allowed with an action",
                withoutAction.getMessage());
        IllegalArgumentException invalidTimeout = assertThrows(IllegalArgumentException.class,
                () -> new TransitionSpec<>(DayOfWeek.MONDAY, Month.JANUARY, DayOfWeek.TUESDAY, Optional.of(SYNC),
                        List.of(), Optional.empty(), List.of(), Optional.of(Duration.ofSeconds(1)), Optional.empty(),
                        duplicateKinds));
        assertEquals("MONDAY -JANUARY-> TUESDAY: timeout is only allowed on an asynchronous action",
                invalidTimeout.getMessage());
    }
}
