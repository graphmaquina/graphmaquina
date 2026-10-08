package io.github.graphmaquina.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.AsyncTransitionAction;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.RecoveryRule;
import io.github.graphmaquina.api.TransitionAction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.TransitionSpec;
import io.github.graphmaquina.error.InvalidGraphException;
import io.github.graphmaquina.error.StateMachineException;
import io.github.graphmaquina.fixtures.RecoveryFixtures;
import io.github.graphmaquina.fixtures.RecoveryFixtures.InvalidResponseException;
import io.github.graphmaquina.fixtures.RecoveryFixtures.ProviderRejectedException;
import io.github.graphmaquina.fixtures.RecoveryFixtures.PublicationFailures;
import io.github.graphmaquina.fixtures.RecoveryFixtures.ServiceUnavailableException;
import io.github.graphmaquina.fixtures.RecoveryFixtures.Ticket;
import io.github.graphmaquina.fixtures.RecoveryFixtures.TicketChange;
import io.github.graphmaquina.fixtures.RecoveryFixtures.TicketState;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * What the DSL does with {@code recover(...)}: the rules it records on the edge, the list it copies at the call, the
 * calls it refuses at once, and the problems {@code build()} reports for them with their exact text. SG302, SG303,
 * SG304 and SG507 come from the edge's draft; SG301 from the shared outline validator.
 */
class RecoveryDslTest {

    private static final String WHERE = "READY -PUBLISH-> PUBLISHED";
    private static final String UNAVAILABLE =
            "io.github.graphmaquina.fixtures.RecoveryFixtures$PublicationFailures$Unavailable";
    private static final String NOT_A_KIND = " is not a failure kind; a failure kind is a named final class or record"
            + " that implements FailureKind and is not an interface, an enum, a local or anonymous class, or a"
            + " Throwable";
    private static final String RULE = "only throwable types foreign to the library, and never Throwable, Exception,"
            + " RuntimeException, an Error or a CancellationException, which are never domain failures, nor"
            + " CompletionException or ExecutionException, which are unwrapped before matching: declare the type of"
            + " their cause";

    /** A synchronous PUBLISH action that always succeeds: no test here runs it. */
    private static final TransitionAction<Ticket, TicketState, TicketChange> SUCCEEDS =
            (TransitionContext<Ticket, TicketState, TicketChange> context) -> ActionOutcome.ok();

    /** A failure kind of this test's own, shared by several rules and edges. */
    record Transport() implements FailureKind { }

    /** Not final, so not a failure kind. */
    static class Open implements FailureKind { }

    /** An interface is never a failure kind. */
    interface Family extends FailureKind { }

    /** An enum is never a failure kind. */
    enum Severity implements FailureKind { LOW }

    /** A throwable is never a failure kind, even a final one. */
    static final class Thrown extends RuntimeException implements FailureKind { }

    /** A cancellation all the same: refused like its parent. */
    static final class Cancelled extends CancellationException { }

    /** An enum that is not the machine's catalogue, with a constant named like one of its identifiers. */
    enum Foreign { SCHEDULE_RETRY }

    private static TransitionSpec<Ticket, TicketState, TicketChange> publishFrom(
            GraphDefinition<Ticket, TicketState, TicketChange> graph, TicketState from) {
        return graph.findTransition(from, TicketChange.PUBLISH).orElseThrow();
    }

    private static InvalidGraphException refused(
            UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> publish) {
        return assertThrows(InvalidGraphException.class, () -> RecoveryFixtures.graph(publish));
    }

    /**
     * The fixture's graph with PUBLISH declared from READY, configured by {@code fromReady}, and from RETRY_PENDING,
     * configured by {@code fromRetryPending}: one identifier with two origins.
     */
    private static GraphDefinition<Ticket, TicketState, TicketChange> twoOrigins(
            UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> fromReady,
            UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> fromRetryPending) {
        GraphBuilder<Ticket, TicketState, TicketChange> builder = StateGraph.define(RecoveryFixtures.KEY)
                .initial(TicketState.READY).terminal(TicketState.PUBLISHED).terminal(TicketState.CLOSED)
                .allowsCycle(TicketState.READY)
                .statePolicy(TicketState.READY, "publish the ticket")
                .statePolicy(TicketState.RETRY_PENDING, "retry the publication explicitly")
                .statePolicy(TicketState.REJECTED_BY_PROVIDER, "close the rejected ticket")
                .statePolicy(TicketState.INTEGRATION_FAILED, "close after manual review");
        fromReady.apply(builder.transition(TicketChange.PUBLISH, TicketState.READY, TicketState.PUBLISHED))
                .endTransition();
        fromRetryPending.apply(builder.transition(TicketChange.PUBLISH, TicketState.RETRY_PENDING,
                TicketState.PUBLISHED)).endTransition();
        return builder
                .transition(TicketChange.SCHEDULE_RETRY, TicketState.READY, TicketState.RETRY_PENDING).endTransition()
                .transition(TicketChange.RECORD_REJECTION, TicketState.READY, TicketState.REJECTED_BY_PROVIDER)
                        .endTransition()
                .transition(TicketChange.MARK_INTEGRATION_FAILED, TicketState.READY, TicketState.INTEGRATION_FAILED)
                        .endTransition()
                .transition(TicketChange.RETRY, TicketState.RETRY_PENDING, TicketState.READY).endTransition()
                .transition(TicketChange.CLOSE, TicketState.REJECTED_BY_PROVIDER, TicketState.CLOSED).endTransition()
                .transition(TicketChange.CLOSE, TicketState.INTEGRATION_FAILED, TicketState.CLOSED).endTransition()
                .build();
    }

    @Test
    void theExampleGraphKeepsItsRulesItsFailsOnAndItsOnFailure() {
        TransitionSpec<Ticket, TicketState, TicketChange> publish =
                publishFrom(RecoveryFixtures.exampleGraph(SUCCEEDS), TicketState.READY);
        assertEquals(List.of(
                        new RecoveryRule<>(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                List.of(ServiceUnavailableException.class)),
                        new RecoveryRule<>(PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION,
                                List.of(ProviderRejectedException.class))),
                publish.recoveryRules());
        assertEquals(List.of(InvalidResponseException.class), publish.failsOn());
        assertEquals(Optional.of(TicketChange.MARK_INTEGRATION_FAILED), publish.onFailure());

        AsyncTransitionAction<Ticket, TicketState, TicketChange> async =
                (TransitionContext<Ticket, TicketState, TicketChange> context) ->
                        CompletableFuture.completedFuture(ActionOutcome.ok());
        assertEquals(publish.recoveryRules(),
                publishFrom(RecoveryFixtures.exampleAsyncGraph(async), TicketState.READY).recoveryRules());
    }

    @Test
    void recordsEveryRuleInDeclarationOrder() {
        GraphDefinition<Ticket, TicketState, TicketChange> graph = RecoveryFixtures.graph(
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.action("publish", SUCCEEDS)
                        .recover(PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                ServiceUnavailableException.class)
                        .recover(Transport.class, TicketChange.SCHEDULE_RETRY,
                                List.of(IOException.class, ProviderRejectedException.class)));
        TransitionSpec<Ticket, TicketState, TicketChange> publish = publishFrom(graph, TicketState.READY);
        assertEquals(List.of(
                        new RecoveryRule<>(PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION,
                                List.of()),
                        new RecoveryRule<>(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                List.of(ServiceUnavailableException.class)),
                        new RecoveryRule<>(Transport.class, TicketChange.SCHEDULE_RETRY,
                                List.of(IOException.class, ProviderRejectedException.class))),
                publish.recoveryRules());
        assertEquals(List.of(), publish.failsOn());
        assertEquals(Optional.empty(), publish.onFailure());
    }

    /** Changing the caller's list after the call changes nothing, and the rules the edge keeps cannot change. */
    @Test
    void theListIsCopiedWhenTheRuleIsDeclared() {
        List<Class<? extends Throwable>> types = new ArrayList<>(List.of(ServiceUnavailableException.class));
        GraphDefinition<Ticket, TicketState, TicketChange> graph = RecoveryFixtures.graph(
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> {
                    TransitionBuilder<Ticket, TicketState, TicketChange> declared = publish.action("publish", SUCCEEDS)
                            .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY, types);
                    types.set(0, InvalidResponseException.class);
                    types.add(RuntimeException.class);
                    return declared;
                });
        TransitionSpec<Ticket, TicketState, TicketChange> publish = publishFrom(graph, TicketState.READY);
        RecoveryRule<TicketChange> rule = publish.recoveryRules().get(0);
        assertEquals(List.of(ServiceUnavailableException.class), rule.failsOn());
        assertThrows(UnsupportedOperationException.class, () -> rule.failsOn().add(IOException.class));
        assertThrows(UnsupportedOperationException.class, () -> publish.recoveryRules().clear());
    }

    /** A type that only failsOn repeats leads to a single route and stays accepted, with rules beside it. */
    @Test
    void failsOnMayStillRepeatATypeBesideRules() {
        TransitionSpec<Ticket, TicketState, TicketChange> spec = publishFrom(RecoveryFixtures.graph(
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.action("publish", SUCCEEDS)
                        .failsOn(InvalidResponseException.class).failsOn(InvalidResponseException.class)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                ServiceUnavailableException.class)
                        .onFailure(TicketChange.MARK_INTEGRATION_FAILED)), TicketState.READY);
        assertEquals(List.of(InvalidResponseException.class, InvalidResponseException.class), spec.failsOn());
        assertEquals(List.of(new RecoveryRule<>(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                List.of(ServiceUnavailableException.class))), spec.recoveryRules());
    }

    /** A supertype and its subtype may sit in different rules, or in a rule and failsOn, in either order. */
    @Test
    void aSupertypeAndItsSubtypeMayBelongToDifferentDeclarations() {
        List<UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>>> edges = List.of(
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.action("publish", SUCCEEDS)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY, IOException.class)
                        .recover(Transport.class, TicketChange.SCHEDULE_RETRY, SocketTimeoutException.class),
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.action("publish", SUCCEEDS)
                        .recover(Transport.class, TicketChange.SCHEDULE_RETRY, SocketTimeoutException.class)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY, IOException.class),
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.action("publish", SUCCEEDS)
                        .failsOn(SocketTimeoutException.class).onFailure(TicketChange.MARK_INTEGRATION_FAILED)
                        .recover(Transport.class, TicketChange.SCHEDULE_RETRY, IOException.class),
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.action("publish", SUCCEEDS)
                        .failsOn(IOException.class).onFailure(TicketChange.MARK_INTEGRATION_FAILED)
                        .recover(Transport.class, TicketChange.SCHEDULE_RETRY, SocketTimeoutException.class));
        for (UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> edge : edges) {
            assertFalse(publishFrom(RecoveryFixtures.graph(edge), TicketState.READY).recoveryRules().isEmpty());
        }
    }

    /** A kind belongs to no edge: two edges may each declare a rule of the same kind. */
    @Test
    void theSameKindMayRecoverOnTwoEdges() {
        GraphDefinition<Ticket, TicketState, TicketChange> graph = twoOrigins(
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.action("publish", SUCCEEDS)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY),
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.action("publish", SUCCEEDS)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.RETRY));
        assertEquals(List.of(new RecoveryRule<>(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                        List.of())),
                publishFrom(graph, TicketState.READY).recoveryRules());
        assertEquals(List.of(new RecoveryRule<>(PublicationFailures.Unavailable.class, TicketChange.RETRY, List.of())),
                publishFrom(graph, TicketState.RETRY_PENDING).recoveryRules());
    }

    @Test
    void sg301ARuleThatNamesItsOwnEdge() {
        InvalidGraphException error = refused(
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.action("publish", SUCCEEDS)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.PUBLISH));
        assertEquals(List.of(new InvalidGraphException.Problem("SG301",
                WHERE + " declares recovery PUBLISH, which is its own key")), error.problems());
    }

    /** Each origin is checked on its own, and two rules that share the missing identifier make one problem. */
    @Test
    void sg301WhenTheRecoveryLeavesOnlyOneOfTheOriginsOfTheIdentifier() {
        UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> rules =
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.action("publish", SUCCEEDS)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY)
                        .recover(PublicationFailures.Rejected.class, TicketChange.SCHEDULE_RETRY);
        InvalidGraphException error = assertThrows(InvalidGraphException.class, () -> twoOrigins(rules, rules));
        assertEquals(List.of(new InvalidGraphException.Problem("SG301", "RETRY_PENDING -PUBLISH-> PUBLISHED declares"
                        + " recovery SCHEDULE_RETRY, but no transition SCHEDULE_RETRY leaves RETRY_PENDING")),
                error.problems());
    }

    /** A rule and the onFailure that name the same identifier, which leaves nowhere, are two problems. */
    @Test
    void sg301WhenARuleAndTheOnFailureNameTheSameMissingIdentifier() {
        InvalidGraphException error = refused(
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.action("publish", SUCCEEDS)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.CLOSE)
                        .onFailure(TicketChange.CLOSE));
        assertEquals(List.of(
                        new InvalidGraphException.Problem("SG301", WHERE + " declares recovery CLOSE, but no"
                                + " transition CLOSE leaves READY"),
                        new InvalidGraphException.Problem("SG301", WHERE + " declares onFailure = CLOSE, but no"
                                + " transition CLOSE leaves READY")),
                error.problems());
    }

    @Test
    void sg302AForbiddenTypeInARule() {
        InvalidGraphException error = refused(
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.action("publish", SUCCEEDS)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                RuntimeException.class));
        assertEquals(List.of(new InvalidGraphException.Problem("SG302", WHERE + ": recover(" + UNAVAILABLE
                + ", SCHEDULE_RETRY) does not allow java.lang.RuntimeException; " + RULE)), error.problems());
        for (Class<? extends Throwable> type : List.of(Throwable.class, Exception.class, OutOfMemoryError.class,
                StateMachineException.class, CancellationException.class, Cancelled.class, CompletionException.class,
                ExecutionException.class)) {
            assertEquals(List.of("SG302"), refused((TransitionBuilder<Ticket, TicketState, TicketChange> publish) ->
                    publish.action("publish", SUCCEEDS).recover(PublicationFailures.Unavailable.class,
                            TicketChange.SCHEDULE_RETRY, List.of(ServiceUnavailableException.class, type))).codes(),
                    type.getName());
        }
    }

    /** Only a raw type lets a class that is not a throwable through, and both failsOn and a rule refuse it. */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void sg302AClassThatIsNotAThrowable() {
        List notThrowable = List.of(String.class);
        InvalidGraphException inARule = refused(
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.action("publish", SUCCEEDS)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY, notThrowable));
        assertEquals(List.of(new InvalidGraphException.Problem("SG302", WHERE + ": recover(" + UNAVAILABLE
                + ", SCHEDULE_RETRY) does not allow java.lang.String; " + RULE)), inARule.problems());
        Class rawType = String.class;
        InvalidGraphException inFailsOn = refused(
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.action("publish", SUCCEEDS)
                        .failsOn(rawType).onFailure(TicketChange.MARK_INTEGRATION_FAILED));
        assertEquals(List.of(new InvalidGraphException.Problem("SG302",
                WHERE + ": failsOn does not allow String; " + RULE)), inFailsOn.problems());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void sg303AClassThatIsNotAFailureKind() {
        InvalidGraphException open = refused(
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.action("publish", SUCCEEDS)
                        .recover(Open.class, TicketChange.SCHEDULE_RETRY));
        assertEquals(List.of(new InvalidGraphException.Problem("SG303", WHERE
                + ": io.github.graphmaquina.dsl.RecoveryDslTest$Open is not a failure kind; a failure kind is a named"
                + " final class or record that implements FailureKind and is not an interface, an enum, a local or"
                + " anonymous class, or a Throwable")), open.problems());
        /** A local class is never a failure kind, even a final one. */
        final class Local implements FailureKind { }
        Class<? extends FailureKind> anonymous = new FailureKind() { }.getClass();
        Class raw = String.class;
        List<Class<? extends FailureKind>> kinds = List.of(Family.class, Severity.class, Thrown.class, Local.class,
                anonymous, raw);
        for (Class<? extends FailureKind> kind : kinds) {
            InvalidGraphException error = refused((TransitionBuilder<Ticket, TicketState, TicketChange> publish) ->
                    publish.action("publish", SUCCEEDS).recover(kind, TicketChange.SCHEDULE_RETRY));
            assertEquals(List.of(new InvalidGraphException.Problem("SG303", WHERE + ": " + kind.getName()
                    + NOT_A_KIND)), error.problems(), kind.getName());
        }
    }

    /** One problem per kind, however many rules repeat it. */
    @Test
    void sg304AKindThatSeveralRulesDeclare() {
        InvalidGraphException error = refused(
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.action("publish", SUCCEEDS)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.RECORD_REJECTION,
                                ServiceUnavailableException.class)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.MARK_INTEGRATION_FAILED));
        assertEquals(List.of(new InvalidGraphException.Problem("SG304",
                WHERE + ": failure kind " + UNAVAILABLE + " is declared by more than one recover")), error.problems());
    }

    /** In one rule, in two rules, or in a rule and failsOn whichever comes first: one problem per type. */
    @Test
    void sg304AThrowableTypeThatSelectsMoreThanOneRoute() {
        String expected = WHERE + ": io.github.graphmaquina.fixtures.RecoveryFixtures$ServiceUnavailableException is"
                + " declared more than once across recover and failsOn; a throwable type selects a single route";
        List<UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>>> edges = List.of(
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.action("publish", SUCCEEDS)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                List.of(ServiceUnavailableException.class, ServiceUnavailableException.class)),
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.action("publish", SUCCEEDS)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                ServiceUnavailableException.class)
                        .recover(PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION,
                                ServiceUnavailableException.class),
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.action("publish", SUCCEEDS)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                ServiceUnavailableException.class)
                        .failsOn(ServiceUnavailableException.class).onFailure(TicketChange.MARK_INTEGRATION_FAILED),
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.action("publish", SUCCEEDS)
                        .failsOn(ServiceUnavailableException.class).onFailure(TicketChange.MARK_INTEGRATION_FAILED)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                ServiceUnavailableException.class),
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> publish.action("publish", SUCCEEDS)
                        .failsOn(ServiceUnavailableException.class).failsOn(ServiceUnavailableException.class)
                        .onFailure(TicketChange.MARK_INTEGRATION_FAILED)
                        .recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                List.of(ServiceUnavailableException.class, ServiceUnavailableException.class))
                        .recover(PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION,
                                ServiceUnavailableException.class));
        for (UnaryOperator<TransitionBuilder<Ticket, TicketState, TicketChange>> edge : edges) {
            assertEquals(List.of(new InvalidGraphException.Problem("SG304", expected)), refused(edge).problems());
        }
    }

    @Test
    void sg507RulesWithoutAnAction() {
        InvalidGraphException rules = refused((TransitionBuilder<Ticket, TicketState, TicketChange> publish) ->
                publish.recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY));
        assertEquals(List.of(new InvalidGraphException.Problem("SG507", WHERE + " declares recover without an action")),
                rules.problems());
        InvalidGraphException withOnFailure = refused((TransitionBuilder<Ticket, TicketState, TicketChange> publish) ->
                publish.recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY)
                        .onFailure(TicketChange.MARK_INTEGRATION_FAILED));
        assertEquals(List.of(
                        new InvalidGraphException.Problem("SG507", WHERE + " declares onFailure or failsOn without an"
                                + " action"),
                        new InvalidGraphException.Problem("SG507", WHERE + " declares recover without an action")),
                withOnFailure.problems());
    }

    /** Nothing of an edge's rules stops at the first problem: every one is reported, sorted by code. */
    @Test
    void everyProblemOfTheRulesIsReportedAtOnce() {
        InvalidGraphException error = refused((TransitionBuilder<Ticket, TicketState, TicketChange> publish) ->
                publish.recover(Open.class, TicketChange.SCHEDULE_RETRY,
                                List.of(RuntimeException.class, RuntimeException.class))
                        .recover(Open.class, TicketChange.RECORD_REJECTION));
        assertEquals(List.of("SG302", "SG302", "SG303", "SG304", "SG304", "SG507"), error.codes());
    }

    /** Each problem is filed at its edge: sorted by origin, whatever order the edges were declared in. */
    @Test
    void theProblemsOfTheRulesArePositionedAtTheirEdge() {
        GraphBuilder<Ticket, TicketState, TicketChange> builder = StateGraph.define(RecoveryFixtures.KEY)
                .initial(TicketState.READY)
                .transition(TicketChange.PUBLISH, TicketState.RETRY_PENDING, TicketState.PUBLISHED)
                        .action("publish", SUCCEEDS).recover(Open.class, TicketChange.RETRY).endTransition()
                .transition(TicketChange.PUBLISH, TicketState.READY, TicketState.PUBLISHED)
                        .action("publish", SUCCEEDS).recover(Open.class, TicketChange.SCHEDULE_RETRY).endTransition();
        InvalidGraphException error = assertThrows(InvalidGraphException.class, builder::build);
        String open = Open.class.getName() + NOT_A_KIND;
        assertEquals(List.of(new InvalidGraphException.Problem("SG303", WHERE + ": " + open),
                        new InvalidGraphException.Problem("SG303", "RETRY_PENDING -PUBLISH-> PUBLISHED: " + open)),
                error.problems());
    }

    /** Refused at the call, so the refused calls leave no rule behind. */
    @Test
    void nullArgumentsAndNullElementsAreRefusedAtTheCall() {
        List<Class<? extends Throwable>> withNull = new ArrayList<>();
        withNull.add(ServiceUnavailableException.class);
        withNull.add(null);
        GraphDefinition<Ticket, TicketState, TicketChange> graph = RecoveryFixtures.graph(
                (TransitionBuilder<Ticket, TicketState, TicketChange> publish) -> {
                    TransitionBuilder<Ticket, TicketState, TicketChange> stage = publish.action("publish", SUCCEEDS);
                    assertTrue(assertThrows(NullPointerException.class,
                            () -> stage.recover(null, TicketChange.SCHEDULE_RETRY)).getMessage().startsWith("kind"));
                    assertTrue(assertThrows(NullPointerException.class,
                            () -> stage.recover(PublicationFailures.Unavailable.class, null))
                            .getMessage().startsWith("transition"));
                    assertTrue(assertThrows(NullPointerException.class,
                            () -> stage.recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                    (Class<? extends Throwable>) null)).getMessage().startsWith("failsOn"));
                    assertTrue(assertThrows(NullPointerException.class,
                            () -> stage.recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                    (List<Class<? extends Throwable>>) null)).getMessage().startsWith("failsOn"));
                    assertEquals("failsOn contains a null element", assertThrows(NullPointerException.class,
                            () -> stage.recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                                    withNull)).getMessage());
                    return stage;
                });
        assertEquals(List.of(), publishFrom(graph, TicketState.READY).recoveryRules());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void anIdentifierOfAnotherCatalogueIsRefusedAtTheCall() {
        TransitionBuilder raw = StateGraph.define(RecoveryFixtures.KEY)
                .transition(TicketChange.PUBLISH, TicketState.READY, TicketState.PUBLISHED);
        List<Executable> calls = List.of(
                () -> raw.recover(PublicationFailures.Unavailable.class, Foreign.SCHEDULE_RETRY),
                () -> raw.recover(PublicationFailures.Unavailable.class, Foreign.SCHEDULE_RETRY, IOException.class),
                () -> raw.recover(PublicationFailures.Unavailable.class, Foreign.SCHEDULE_RETRY, List.of()));
        for (Executable call : calls) {
            assertEquals("transition must belong to " + TicketChange.class.getName() + ": SCHEDULE_RETRY",
                    assertThrows(IllegalArgumentException.class, call).getMessage());
        }
    }

    @Test
    void aClosedStageAndABuiltGraphRefuseRecover() {
        GraphBuilder<Ticket, TicketState, TicketChange> builder = StateGraph.define(RecoveryFixtures.KEY)
                .initial(TicketState.READY);
        TransitionBuilder<Ticket, TicketState, TicketChange> closed =
                builder.transition(TicketChange.PUBLISH, TicketState.READY, TicketState.PUBLISHED);
        closed.endTransition();
        List<Executable> late = List.of(
                () -> closed.recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY),
                () -> closed.recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                        ServiceUnavailableException.class),
                () -> closed.recover(PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,
                        List.of(ServiceUnavailableException.class)));
        for (Executable call : late) {
            assertEquals("endTransition() was already called on " + WHERE + " in the graph " + RecoveryFixtures.KEY,
                    assertThrows(IllegalStateException.class, call).getMessage());
        }
        TransitionBuilder<Ticket, TicketState, TicketChange> open =
                builder.transition(TicketChange.SCHEDULE_RETRY, TicketState.READY, TicketState.RETRY_PENDING);
        assertThrows(InvalidGraphException.class, builder::build);
        assertEquals("build() was already called on the graph " + RecoveryFixtures.KEY
                        + ", so READY -SCHEDULE_RETRY-> RETRY_PENDING can no longer change",
                assertThrows(IllegalStateException.class,
                        () -> open.recover(PublicationFailures.Unavailable.class, TicketChange.RECORD_REJECTION))
                        .getMessage());
    }
}
