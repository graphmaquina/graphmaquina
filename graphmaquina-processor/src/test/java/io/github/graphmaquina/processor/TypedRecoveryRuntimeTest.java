package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The {@code recover} calls the generated companion makes of the recovery rules a facade declares, and what the
 * compiled consumer does with them. Each rule is one call, in declaration order, after the edge's {@code failsOn}
 * calls and before its hook; its kind and its types are written by their qualified names, which the companion's scope
 * must leave free, and a rule with two types or more passes them in a {@code java.util.List}. Run through the
 * companion, a returned failure of a kind and a thrown type of the same rule reach the same transition and the same
 * kind, the most specific declared type wins whatever the order of the rules, a checked exception the action declares
 * reaches its rule through the companion, the asynchronous facade routes a synchronous and an asynchronous action
 * alike, and the recovery receives the input of the call. A machine without rules gets the very companion it got
 * before rules existed. Every class and every cause is compared by identity, inside the consumer's own loader.
 */
class TypedRecoveryRuntimeTest {

    /** The set that builds the publication machine with the one host its edges bind. */
    private static final String DESK = """
            package fx;
            import io.github.graphmaquina.annotation.StateGraphs;
            import io.github.graphmaquina.api.Machines;
            /** Set owning the publication facade. */ @StateGraphs public interface Desk extends Machines { Tickets tickets(); }
            """;

    /**
     * The class each runtime test compiles with its fixture: {@code run()} has the test's body, and the helpers tell
     * how a publication ended, from inside the consumer's own loader, comparing the kind and the cause by identity.
     */
    private static final String SCENARIO = """
            package fx;
            import io.github.graphmaquina.api.ActionOutcome;
            import io.github.graphmaquina.api.FailureKind;
            import io.github.graphmaquina.api.Outcome;
            import io.github.graphmaquina.error.TransitionAbortedException;
            import java.io.IOException;
            import java.io.UncheckedIOException;
            import java.time.Duration;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Optional;
            import java.util.concurrent.CompletableFuture;
            import java.util.concurrent.TimeUnit;
            /** Executes publication scenarios inside the compiled consumer. */ public final class Scenario {
                private Scenario() { }
                public static Object run() throws Exception {
            %s
                }
                /** Publishes a new ticket whose action returns {@code outcome}, and tells how the call ended. */
                static String returning(Tickets tickets, TicketActions actions, ActionOutcome outcome) {
                    actions.result = outcome;
                    actions.failure = null;
                    return publish(tickets, null);
                }
                /** Publishes a new ticket whose action throws {@code failure}, and tells how the call ended. */
                static String throwing(Tickets tickets, TicketActions actions, RuntimeException failure) {
                    actions.failure = failure;
                    return publish(tickets, failure);
                }
                /** Publishes a new ticket and tells how the call ended; the action throws {@code thrown}, if any. */
                static String publish(Tickets tickets, Throwable thrown) {
                    Ticket ticket = new Ticket("ticket");
                    try {
                        return describe(ticket, tickets.publish(ticket), thrown);
                    } catch (TransitionAbortedException aborted) {
                        return "aborted " + (aborted.getCause() == thrown ? "by it" : "by " + aborted.getCause())
                                + ", " + ticket.status();
                    }
                }
                /**
                 * The transition the failure recovered through, the state it moved to, the kind, whether the cause is
                 * {@code thrown}, and the state {@code ticket} holds once the call returned.
                 */
                static String describe(Ticket ticket, Outcome<Ticket, TicketState, TicketChange> outcome,
                        Throwable thrown) {
                    Optional<Outcome.Failed<Ticket, TicketState, TicketChange>> failed = outcome.asFailed();
                    if (failed.isEmpty()) {
                        return outcome + ", " + ticket.status();
                    }
                    Optional<Throwable> cause = failed.get().cause();
                    String carried = cause.isEmpty() ? "no cause"
                            : cause.get() == thrown ? "its cause" : "cause " + cause.get();
                    return failed.get().failureTransition() + " to " + failed.get().movedTo() + ", "
                            + kind(failed.get().failureKind()) + ", " + carried + ", " + ticket.status();
                }
                /** The kind, compared by identity with the kinds of the fixture. */
                static String kind(Optional<Class<? extends FailureKind>> kind) {
                    if (kind.isEmpty()) {
                        return "no kind";
                    }
                    if (kind.get() == PublicationFailures.Unavailable.class) {
                        return "Unavailable";
                    }
                    return kind.get() == PublicationFailures.Rejected.class ? "Rejected" : "kind " + kind.get();
                }
            }
            """;

    /** The companion the base of this change generates for {@link Fixtures#validDocs()}, which declares no rule. */
    private static final String VALID_DOCS_COMPANION = """
            package fx;

            import io.github.graphmaquina.api.GraphDefinition;
            import io.github.graphmaquina.api.MachineKey;
            import io.github.graphmaquina.api.Outcome;
            import io.github.graphmaquina.api.StateMachine;
            import io.github.graphmaquina.dsl.StateGraph;
            import java.util.Objects;
            import java.util.Set;
            import java.util.concurrent.CompletableFuture;
            import java.util.function.Supplier;
            import io.github.graphmaquina.annotation.internal.GeneratedCompanion;
            import java.time.Duration;

            /** Generated companion of {@link Docs}: builds its graph and implements its facade. Do not edit. */
            @GeneratedCompanion
            @SuppressWarnings({"deprecation", "removal"})
            public final class DocsRuntime implements Docs {

                public static final MachineKey<Doc, DocStatus, DocTransition> KEY = MachineKey.of(Doc.class, \
            DocStatus.class, DocTransition.class);

                private final StateMachine<Doc, DocStatus, DocTransition> stateMachine;

                public DocsRuntime(StateMachine<Doc, DocStatus, DocTransition> stateMachine) {
                    this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
                }

                /** The definition, built from the annotations of {@link Docs}. One parameter per class with instance \
            members. */
                public static GraphDefinition<Doc, DocStatus, DocTransition> graph(DocActions docActions) {
                    Objects.requireNonNull(docActions, "docActions");
                    return StateGraph.define(KEY)
                            .initial(DocStatus.DRAFT)
                            .terminal(DocStatus.ARCHIVED)
                            .allowsCycle(DocStatus.REVIEW)
                            .statePolicy(DocStatus.DRAFT, "a draft is sent to review")
                            .statePolicy(DocStatus.REVIEW, "a review is published or sent back")
                            .statePolicy(DocStatus.PUBLISHED, "a published doc can only be archived")
                            .transition(DocTransition.RETURN, DocStatus.REVIEW, DocStatus.DRAFT).endTransition()
                            .transition(DocTransition.SUBMIT, DocStatus.DRAFT, DocStatus.REVIEW).endTransition()
                            .transition(DocTransition.PUBLISH, DocStatus.REVIEW, DocStatus.PUBLISHED)\
            .action("publish", (context) -> docActions.publish(context)).guard("approved", (context) -> \
            docActions.approved(context)).onFailure(DocTransition.RETURN).afterCommit("notify", (doc) -> \
            docActions.notify(doc)).endTransition()
                            .transition(DocTransition.ARCHIVE, DocStatus.PUBLISHED, DocStatus.ARCHIVED)\
            .asyncAction("archive", (context) -> docActions.archiveAsync(context)).timeout(Duration.parse("PT2S"))\
            .endTransition()
                            .build();
                }

                @Override
                public Outcome<Doc, DocStatus, DocTransition> submit(Doc doc) {
                    Objects.requireNonNull(doc, "doc");
                    return stateMachine.execute(doc, DocTransition.SUBMIT);
                }

                @Override
                public Outcome<Doc, DocStatus, DocTransition> publish(Doc doc) {
                    Objects.requireNonNull(doc, "doc");
                    return stateMachine.execute(doc, DocTransition.PUBLISH);
                }

                @Override
                public Outcome<Doc, DocStatus, DocTransition> archive(Doc doc) {
                    Objects.requireNonNull(doc, "doc");
                    return stateMachine.execute(doc, DocTransition.ARCHIVE);
                }

                @Override
                public GraphDefinition<Doc, DocStatus, DocTransition> definition() { return stateMachine.definition(); }

                @Override
                public Outcome<Doc, DocStatus, DocTransition> reevaluate(Doc doc) { return \
            stateMachine.reevaluate(doc); }

                @Override
                public Set<DocTransition> transitionsFrom(DocStatus state) { return \
            stateMachine.transitionsFrom(state); }

                @Override
                public <R> CompletableFuture<R> async(Supplier<R> call) { return stateMachine.supplyAsync(call); }
            }
            """;

    /** The comment the vocabulary of {@code fx.Tickets} gives its {@code Recovery}. */
    private static final String RECOVERY_DOC = """
                /**
                 * One recovery rule in the {@code recoveries} of a {@link TicketsGraph.Transition} of
                 * {@link fx.Tickets}. A failed outcome the edge's action returns with {@code kind}, or a
                 * throwable the action fails with whose most specific declared type is one of {@code failsOn},
                 * none by default, takes {@code transition}, and the call's failed outcome reports
                 * {@code kind} as its failure kind.
                 *
                 * <p>The most specific declared type wins across the whole edge, whether a rule or the edge's
                 * own {@code failsOn} declares it, and declaration order never matters. A type of the edge's
                 * own {@code failsOn}, and a failed outcome that carries no kind or one no rule declares, take
                 * the edge's {@code onFailure}, the fallback, when it declares one. {@code transition} is
                 * resolved from the origin the entity is in, so an edge with that identifier must leave every
                 * origin of the declaring edge. Entered as a recovery, that edge runs without its guards and
                 * applies no rule or fallback of its own: a recovery never chains.</p>
                 */
            """;

    /** The comment the vocabulary of {@code fx.Tickets} gives the element {@code recoveries} of its Transition. */
    private static final String RECOVERIES_DOC = """
                    /**
                     * The recovery rules of this edge, each a {@link TicketsGraph.Recovery}, which need an action.
                     * A failed outcome or a throwable of the action takes the transition of the rule that selects
                     * it, the most specific declared type winning across the rules and {@code failsOn}, and
                     * {@code onFailure} stays the fallback. Each transition is resolved from the edge's origin and
                     * entered without its guards or any recovery of its own.
                     */
            """;

    /**
     * The spec's example: each rule is one call with its one type, in declaration order and after the edge's own
     * failsOn, and the companion names no {@code List}. Each row of the spec's table ends as the table says: a
     * returned kind and a thrown type of one rule reach its transition with its kind, with no cause and with the
     * exception, the fallback takes what no rule selects, and an exception no declaration names aborts the call.
     */
    @Test
    void theSpecsExampleRoutesEveryRowOfItsTableThroughTheCompanion() throws Exception {
        Compilation.Result result = compile(RecoveryFixtures.example(), """
                TicketActions actions = new TicketActions();
                List<String> ends = new ArrayList<>();
                try (Desk desk = DeskRuntime.builder(actions).build()) {
                    Tickets tickets = desk.tickets();
                    ends.add(returning(tickets, actions,
                            ActionOutcome.failed(PublicationFailures.Unavailable.class, "provider unavailable")));
                    ends.add(throwing(tickets, actions, new ServiceUnavailableException("provider unavailable")));
                    ends.add(returning(tickets, actions,
                            ActionOutcome.failed(PublicationFailures.Rejected.class, "content rejected")));
                    ends.add(throwing(tickets, actions, new ProviderRejectedException("content rejected")));
                    ends.add(returning(tickets, actions, ActionOutcome.failed("invalid response")));
                    ends.add(throwing(tickets, actions, new InvalidResponseException("unreadable response")));
                    ends.add(throwing(tickets, actions, new IllegalStateException("unexpected answer")));
                }
                return ends;
                """);

        String tickets = result.generated("fx.TicketsRuntime");
        assertTrue(tickets.contains(".onFailure(TicketChange.MARK_INTEGRATION_FAILED)"
                + ".failsOn(fx.InvalidResponseException.class)"
                + ".recover(fx.PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,"
                + " fx.ServiceUnavailableException.class)"
                + ".recover(fx.PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION,"
                + " fx.ProviderRejectedException.class)"
                + ".endTransition()"), tickets);
        assertFalse(tickets.contains("java.util.List") || tickets.contains("List.of("), tickets);
        assertEquals(List.of("SCHEDULE_RETRY to RETRY_PENDING, Unavailable, no cause, RETRY_PENDING",
                "SCHEDULE_RETRY to RETRY_PENDING, Unavailable, its cause, RETRY_PENDING",
                "RECORD_REJECTION to REJECTED_BY_PROVIDER, Rejected, no cause, REJECTED_BY_PROVIDER",
                "RECORD_REJECTION to REJECTED_BY_PROVIDER, Rejected, its cause, REJECTED_BY_PROVIDER",
                "MARK_INTEGRATION_FAILED to INTEGRATION_FAILED, no kind, no cause, INTEGRATION_FAILED",
                "MARK_INTEGRATION_FAILED to INTEGRATION_FAILED, no kind, its cause, INTEGRATION_FAILED",
                "aborted by it, READY"), run(result));
    }

    /**
     * A rule with two types is one call that passes them in a {@code List}, which the companion then imports, and a
     * rule with none is one call with the kind and the transition alone; both come before the edge's hook. Each type
     * of the first rule reaches its transition, and the second rule selects its kind only: an exception no type
     * names aborts the call.
     */
    @Test
    void aRuleWithTwoTypesPassesThemInAListAndRoutesEach() throws Exception {
        Compilation.Result result = compile(RecoveryFixtures.sources("""
                    @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        recoveries = {
                            @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
                                transition = SCHEDULE_RETRY,
                                failsOn = {ServiceUnavailableException.class, java.io.UncheckedIOException.class}),
                            @TicketsGraph.Recovery(kind = PublicationFailures.Rejected.class,
                                transition = RECORD_REJECTION)},
                        failsOn = InvalidResponseException.class,
                        onFailure = MARK_INTEGRATION_FAILED,
                        afterCommit = TicketActionsRefs.Published.class)
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """), """
                TicketActions actions = new TicketActions();
                List<String> ends = new ArrayList<>();
                try (Desk desk = DeskRuntime.builder(actions).build()) {
                    Tickets tickets = desk.tickets();
                    ends.add(throwing(tickets, actions, new ServiceUnavailableException("provider unavailable")));
                    ends.add(throwing(tickets, actions,
                            new UncheckedIOException("stream closed", new IOException("closed"))));
                    ends.add(returning(tickets, actions,
                            ActionOutcome.failed(PublicationFailures.Rejected.class, "content rejected")));
                    ends.add(throwing(tickets, actions, new ProviderRejectedException("content rejected")));
                }
                return ends;
                """);

        String tickets = result.generated("fx.TicketsRuntime");
        assertTrue(tickets.contains("import java.util.List;"), tickets);
        assertTrue(tickets.contains(".onFailure(TicketChange.MARK_INTEGRATION_FAILED)"
                + ".failsOn(fx.InvalidResponseException.class)"
                + ".recover(fx.PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,"
                + " List.of(fx.ServiceUnavailableException.class, java.io.UncheckedIOException.class))"
                + ".recover(fx.PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION)"
                + ".afterCommit(\"published\", (ticket) -> ticketActions.published(ticket))"
                + ".endTransition()"), tickets);
        assertEquals(List.of("SCHEDULE_RETRY to RETRY_PENDING, Unavailable, its cause, RETRY_PENDING",
                "SCHEDULE_RETRY to RETRY_PENDING, Unavailable, its cause, RETRY_PENDING",
                "RECORD_REJECTION to REJECTED_BY_PROVIDER, Rejected, no cause, REJECTED_BY_PROVIDER",
                "aborted by it, READY"), run(result));
    }

    /**
     * A rule with two types writes {@code List.of(...)}. A top-level class {@code List} of the facade's package does
     * not shadow the single-type import of {@code java.util.List}, which the companion keeps; a member type
     * {@code List} the facade declares does, so the companion qualifies the call and imports nothing. In both the
     * companion compiles and the rule still routes a thrown exception.
     */
    @Test
    void aRuleWithTwoTypesQualifiesListWhereTheFacadeScopeShadowsIt() throws Exception {
        String rule = """
                    @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        recoveries = @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
                            transition = SCHEDULE_RETRY,
                            failsOn = {ServiceUnavailableException.class, java.io.UncheckedIOException.class}),
                        onFailure = MARK_INTEGRATION_FAILED)
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """;
        String body = """
                TicketActions actions = new TicketActions();
                try (Desk desk = DeskRuntime.builder(actions).build()) {
                    return throwing(desk.tickets(), actions, new ServiceUnavailableException("provider unavailable"));
                }
                """;
        Map<String, String> packageClass = RecoveryFixtures.sources(rule);
        packageClass.put("fx.List", """
                package fx;
                /** A top-level class of the facade's package that takes the simple name List. */
                public final class List { }
                """);
        Map<String, String> memberType = RecoveryFixtures.sources("""
                    /** A member type of the facade that takes the simple name List. */
                    interface List { }
                """ + rule);
        Compilation.Result imported = compile(packageClass, body);
        String importing = imported.generated("fx.TicketsRuntime");
        assertTrue(importing.contains("import java.util.List;"), importing);
        assertTrue(importing.contains(" List.of(fx.ServiceUnavailableException.class,"
                + " java.io.UncheckedIOException.class))"), importing);
        assertEquals("SCHEDULE_RETRY to RETRY_PENDING, Unavailable, its cause, RETRY_PENDING", run(imported));
        Compilation.Result qualified = compile(memberType, body);
        String qualifying = qualified.generated("fx.TicketsRuntime");
        assertTrue(qualifying.contains(" java.util.List.of(fx.ServiceUnavailableException.class,"
                + " java.io.UncheckedIOException.class))"), qualifying);
        assertFalse(qualifying.contains("import java.util.List;"), qualifying);
        assertFalse(qualifying.contains(" List.of("), qualifying);
        assertEquals("SCHEDULE_RETRY to RETRY_PENDING, Unavailable, its cause, RETRY_PENDING", run(qualified));
    }

    /**
     * RateLimitedException extends ServiceUnavailableException, and each has a rule of its own. The companion keeps
     * the order the facade declares the rules in, and in either order the subclass reaches its own rule while the
     * superclass keeps the other.
     */
    @Test
    void theMostSpecificTypeWinsThroughTheCompanionWhateverTheOrderOfTheRules() throws Exception {
        String superclassFirst = """
                    @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        recoveries = {
                            @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
                                transition = SCHEDULE_RETRY, failsOn = ServiceUnavailableException.class),
                            @TicketsGraph.Recovery(kind = PublicationFailures.Rejected.class,
                                transition = RECORD_REJECTION, failsOn = RateLimitedException.class)})
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """;
        String subclassFirst = """
                    @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        recoveries = {
                            @TicketsGraph.Recovery(kind = PublicationFailures.Rejected.class,
                                transition = RECORD_REJECTION, failsOn = RateLimitedException.class),
                            @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
                                transition = SCHEDULE_RETRY, failsOn = ServiceUnavailableException.class)})
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """;
        String body = """
                TicketActions actions = new TicketActions();
                List<String> ends = new ArrayList<>();
                try (Desk desk = DeskRuntime.builder(actions).build()) {
                    ends.add(throwing(desk.tickets(), actions, new RateLimitedException("too many publications")));
                    ends.add(throwing(desk.tickets(), actions,
                            new ServiceUnavailableException("provider unavailable")));
                }
                return ends;
                """;
        String unavailableCall = ".recover(fx.PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,"
                + " fx.ServiceUnavailableException.class)";
        String rejectedCall = ".recover(fx.PublicationFailures.Rejected.class, TicketChange.RECORD_REJECTION,"
                + " fx.RateLimitedException.class)";
        List<String> routed = List.of(
                "RECORD_REJECTION to REJECTED_BY_PROVIDER, Rejected, its cause, REJECTED_BY_PROVIDER",
                "SCHEDULE_RETRY to RETRY_PENDING, Unavailable, its cause, RETRY_PENDING");

        Compilation.Result superclass = compile(RecoveryFixtures.sources(superclassFirst), body);
        Compilation.Result subclass = compile(RecoveryFixtures.sources(subclassFirst), body);

        assertTrue(superclass.generated("fx.TicketsRuntime").contains(unavailableCall + rejectedCall),
                superclass.generated("fx.TicketsRuntime"));
        assertTrue(subclass.generated("fx.TicketsRuntime").contains(rejectedCall + unavailableCall),
                subclass.generated("fx.TicketsRuntime"));
        assertEquals(routed, run(superclass));
        assertEquals(routed, run(subclass));
    }

    /**
     * An action that declares a checked exception is called in a block that catches it and throws it on unchanged,
     * so the engine receives the very exception, and a rule that lists it routes it.
     */
    @Test
    void aCheckedExceptionTheActionDeclaresReachesItsRuleThroughTheCompanion() throws Exception {
        Compilation.Result result = compile(RecoveryFixtures.sources("""
                    @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.PublishOverSocket.class,
                        recoveries = @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
                            transition = SCHEDULE_RETRY, failsOn = java.io.IOException.class))
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """), """
                TicketActions actions = new TicketActions();
                try (Desk desk = DeskRuntime.builder(actions).build()) {
                    Ticket ticket = new Ticket("ticket");
                    return describe(ticket, desk.tickets().publish(ticket), actions.reset);
                }
                """);

        String tickets = result.generated("fx.TicketsRuntime");
        assertTrue(tickets.contains("catch (Exception failure) { throw rethrow(failure); }"), tickets);
        assertTrue(tickets.contains(".recover(fx.PublicationFailures.Unavailable.class, TicketChange.SCHEDULE_RETRY,"
                + " java.io.IOException.class)"), tickets);
        assertEquals("SCHEDULE_RETRY to RETRY_PENDING, Unavailable, its cause, RETRY_PENDING", run(result));
    }

    /**
     * The facade's {@code async(...)} runs the call on the machine's executor, and the rules route alike whether the
     * action is synchronous or returns a stage: a returned kind, then a thrown or failed-with exception. The
     * asynchronous action's machine takes its timeout from the set's builder. Each submitted call is cancelled in
     * {@code finally} if its bounded wait exits before completion.
     */
    @Test
    void theAsyncFacadeRoutesASynchronousAndAnAsynchronousActionAlike() throws Exception {
        String calls = """
                    Tickets tickets = desk.tickets();
                    actions.result = ActionOutcome.failed(PublicationFailures.Rejected.class, "content rejected");
                    Ticket rejected = new Ticket("rejected");
                    CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> rejectedCall =
                            tickets.async(() -> tickets.publish(rejected));
                    try {
                        ends.add(describe(rejected, rejectedCall.get(5, TimeUnit.SECONDS), null));
                    } finally {
                        rejectedCall.cancel(true);
                    }
                    actions.failure = unavailable;
                    Ticket failing = new Ticket("unavailable");
                    CompletableFuture<Outcome<Ticket, TicketState, TicketChange>> failingCall =
                            tickets.async(() -> tickets.publish(failing));
                    try {
                        ends.add(describe(failing, failingCall.get(5, TimeUnit.SECONDS), unavailable));
                    } finally {
                        failingCall.cancel(true);
                    }
                """;
        String opening = """
                TicketActions actions = new TicketActions();
                ServiceUnavailableException unavailable = new ServiceUnavailableException("provider unavailable");
                List<String> ends = new ArrayList<>();
                """;
        Compilation.Result synchronous = compile(RecoveryFixtures.example(), opening
                + "try (Desk desk = DeskRuntime.builder(actions).build()) {\n" + calls + "}\nreturn ends;\n");
        Compilation.Result asynchronous = compile(RecoveryFixtures.sources(RecoveryFixtures.EXAMPLE_PUBLISH.replace(
                "TicketActionsRefs.Publish.class", "TicketActionsRefs.PublishAsync.class")), opening
                + "try (Desk desk = DeskRuntime.builder(Duration.ofSeconds(5), actions).build()) {\n" + calls
                + "}\nreturn ends;\n");

        List<String> routed = List.of(
                "RECORD_REJECTION to REJECTED_BY_PROVIDER, Rejected, no cause, REJECTED_BY_PROVIDER",
                "SCHEDULE_RETRY to RETRY_PENDING, Unavailable, its cause, RETRY_PENDING");
        assertTrue(asynchronous.generated("fx.TicketsRuntime").contains(".asyncAction(\"publish\""),
                asynchronous.generated("fx.TicketsRuntime"));
        assertEquals(routed, run(synchronous));
        assertEquals(routed, run(asynchronous));
    }

    /**
     * The input of the call reaches the action that failed and, as the very same object, the action of the edge the
     * rule recovers through.
     */
    @Test
    void theRecoveryActionReceivesTheInputOfTheCall() throws Exception {
        Map<String, String> sources = RecoveryFixtures.sources(RecoveryFixtures.EXAMPLE_PUBLISH.replace(
                "publish(Ticket ticket);", "publish(Ticket ticket, Attempt attempt);"));
        sources.put("fx.Tickets", sources.get("fx.Tickets").replace(
                "@TicketsGraph.Transition(id = SCHEDULE_RETRY, from = READY, to = RETRY_PENDING)",
                "@TicketsGraph.Transition(id = SCHEDULE_RETRY, from = READY, to = RETRY_PENDING,\n"
                        + "    action = TicketActionsRefs.ScheduleRetry.class)"));
        sources.put("fx.Attempt", "package fx;\n/** Input shared by publication and recovery. */ public final class Attempt { }\n");
        sources.put("fx.Desk", DESK);
        sources.put("fx.Scenario", """
                package fx;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.Outcome;
                /** Executes publication scenarios inside the compiled consumer. */ public final class Scenario {
                    private Scenario() { }
                    public static Object run() {
                        TicketActions actions = new TicketActions();
                        actions.result = ActionOutcome.failed(PublicationFailures.Unavailable.class,
                                "provider unavailable");
                        try (Desk desk = DeskRuntime.builder(actions).build()) {
                            Attempt attempt = new Attempt();
                            Ticket ticket = new Ticket("ticket");
                            Outcome<Ticket, TicketState, TicketChange> outcome =
                                    desk.tickets().publish(ticket, attempt);
                            return outcome.asFailed()
                                    .map((Outcome.Failed<Ticket, TicketState, TicketChange> failed) ->
                                            failed.failureTransition() + " to " + failed.movedTo())
                                    .orElse(String.valueOf(outcome))
                                    + ", the publication got the attempt: " + (actions.publishInput == attempt)
                                    + ", the recovery got it: " + (actions.retryInput == attempt);
                        }
                    }
                }
                """);
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result::describe);

        assertEquals("SCHEDULE_RETRY to RETRY_PENDING, the publication got the attempt: true,"
                + " the recovery got it: true", run(result));
    }

    /**
     * A machine that declares no rule gets the very companion it got before rules existed: the text below is what the
     * base of this change generated for {@link Fixtures#validDocs()}, byte for byte. The same machine with two types
     * in its edge's own failsOn differs only by those two calls: only a rule claims {@code java.util.List}.
     */
    @Test
    void aMachineWithoutRulesGetsTheCompanionItGotBefore() {
        Compilation.Result valid = Compilation.compileTyped(Fixtures.validDocs());
        Compilation.Result twoTypes = Compilation.compileTyped(Fixtures.withDocs(Fixtures.DOCS_HEADER,
                Fixtures.DOCS_BODY.replace("onFailure = RETURN,", "onFailure = RETURN,"
                        + " failsOn = {IllegalStateException.class, java.io.UncheckedIOException.class},")));

        assertTrue(valid.success(), valid.describe());
        assertEquals(VALID_DOCS_COMPANION, valid.generated("fx.DocsRuntime"));
        assertTrue(twoTypes.success(), twoTypes.describe());
        assertEquals(VALID_DOCS_COMPANION.replace(".onFailure(DocTransition.RETURN).afterCommit(",
                ".onFailure(DocTransition.RETURN).failsOn(java.lang.IllegalStateException.class)"
                        + ".failsOn(java.io.UncheckedIOException.class).afterCommit("),
                twoTypes.generated("fx.DocsRuntime"));
    }

    /**
     * The companion writes the kind and the types of a rule by their qualified names, so a type of the facade's
     * package named like the first segment of one would hide that package from the companion: the facade is refused
     * as SG603 before its companion is written, whether the name comes from a kind or from a type.
     */
    @Test
    void sg603ARuleWhosePackageATypeOfTheFacadesPackageTakes() {
        String kind = """
                    @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        recoveries = @TicketsGraph.Recovery(kind = Outages.Unavailable.class,
                            transition = SCHEDULE_RETRY, failsOn = ServiceUnavailableException.class))
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """;
        String type = """
                    @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                        action = TicketActionsRefs.Publish.class,
                        recoveries = @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
                            transition = SCHEDULE_RETRY, failsOn = Outages.DownException.class))
                    Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
                """;

        Compilation.Result byKind = Compilation.compileTyped(besideBilling(kind));
        Compilation.Result byType = Compilation.compileTyped(besideBilling(type));

        assertEquals(List.of("[SG603] the companion TicketsRuntime of fx.Tickets writes billing.Outages.Unavailable,"
                + " but in its scope billing names the type fx.billing, not a package: rename that type or the package"
                + " billing"), byKind.errors(), byKind.describe());
        assertFalse(byKind.hasGenerated("fx.TicketsRuntime"), byKind.describe());
        assertEquals(List.of("[SG603] the companion TicketsRuntime of fx.Tickets writes billing.Outages.DownException,"
                + " but in its scope billing names the type fx.billing, not a package: rename that type or the package"
                + " billing"), byType.errors(), byType.describe());
        assertFalse(byType.hasGenerated("fx.TicketsRuntime"), byType.describe());
    }

    /**
     * The vocabulary documents what a rule selects, the fallback, the resolution of its transition from each origin,
     * and that the edge it enters as a recovery runs no guard and never chains.
     */
    @Test
    void theVocabularyDocumentsHowARuleIsSelectedAndResolved() throws IOException {
        Compilation.Prepared prepared = Compilation.prepare(RecoveryFixtures.example());
        String vocabulary = Files.readString(prepared.request().sourcesOutput().resolve("fx/TicketsGraph.java"),
                StandardCharsets.UTF_8);

        assertTrue(vocabulary.contains(RECOVERY_DOC + "    @io.github.graphmaquina.annotation.internal"
                + ".GeneratedVocabulary(machine = fx.Tickets.class,"
                + " value = io.github.graphmaquina.annotation.internal.VocabularyKind.RECOVERY)\n"), vocabulary);
        assertTrue(vocabulary.contains(RECOVERIES_DOC + "        TicketsGraph.Recovery[] recoveries() default {};\n"),
                vocabulary);
    }

    /** The fixture with {@code publish} declared as given, a class {@code fx.billing}, and the package billing. */
    private static Map<String, String> besideBilling(String publish) {
        Map<String, String> sources = RecoveryFixtures.sources(publish);
        sources.put("fx.Tickets", sources.get("fx.Tickets").replace("import io.github.graphmaquina.api.Outcome;\n",
                "import io.github.graphmaquina.api.Outcome;\nimport billing.Outages;\n"));
        sources.put("fx.billing", "package fx;\n/** Deliberate type shadowing the billing package. */ public final class billing { }\n");
        sources.put("billing.Outages", """
                package billing;
                import io.github.graphmaquina.api.FailureKind;
                /** Failure declarations used to probe package shadowing. */ public final class Outages {
                    private Outages() { }
                    /** Kind declared in the billing package. */ public record Unavailable() implements FailureKind { }
                    /** Throwable declared in the billing package. */ public static final class DownException extends RuntimeException {
                        public DownException(String message) {
                            super(message);
                        }
                    }
                }
                """);
        return sources;
    }

    /** Compiles {@code sources} with the desk and a scenario whose {@code run()} has {@code body}; it must succeed. */
    private static Compilation.Result compile(Map<String, String> sources, String body) {
        sources.put("fx.Desk", DESK);
        sources.put("fx.Scenario", SCENARIO.formatted(body));
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result::describe);
        return result;
    }

    /** What {@code fx.Scenario.run()} returns, bounded independently of the machine's timeout. */
    private static Object run(Compilation.Result result) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor((Runnable task) -> {
            Thread thread = new Thread(task, "typed-recovery-scenario");
            thread.setDaemon(true);
            return thread;
        });
        Future<Object> invocation = worker.submit(() -> result.load("fx.Scenario").getMethod("run").invoke(null));
        try {
            return invocation.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new AssertionError(cause);
        } finally {
            invocation.cancel(true);
            worker.shutdownNow();
            worker.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
