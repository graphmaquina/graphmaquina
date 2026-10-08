package io.github.graphmaquina.processor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Source fixtures of the typed-recovery tests: the publication machine of the example graph, declared with the
 * vocabulary generated for it. Everything lives in package {@code fx}, one entry per compilation unit, and no entry
 * names a generated type the pre-javac generator does not produce from these very sources.
 *
 * <p>The facade {@code fx.Tickets} is fixed up to the declaration of its {@code publish} operation, which a test
 * writes itself, so every diagnostic a test expects has a line it can name: the problems of the graph as a whole
 * are reported at {@link #DEFINITION_LINE}, the {@code retry} operation starts at {@link #RETRY_LINE} and
 * {@code publish} at {@link #PUBLISH_LINE}. The edges a publication recovers through, and {@code CLOSE}, are moves
 * the interface declares itself. The host {@code TicketActions} publishes as a test sets it to: it throws
 * {@code failure} when one is set, and returns {@code result} otherwise, in a completed stage for its asynchronous
 * action; it records the input its publication and its rescheduling receive.</p>
 */
final class RecoveryFixtures {

    /** The line of the facade on which its {@code Definition} starts: where the problems of the graph are reported. */
    static final int DEFINITION_LINE = 8;

    /** The line of the facade on which the declaration of the {@code retry} operation starts. */
    static final int RETRY_LINE = 19;

    /** The line of the facade on which the declaration of {@code publish} that {@link #sources} takes starts. */
    static final int PUBLISH_LINE = 21;

    /** The declaration of {@code publish} the example graph has: two rules, a failsOn of its own and a fallback. */
    static final String EXAMPLE_PUBLISH = """
                @TicketsGraph.Transition(id = PUBLISH, from = READY, to = PUBLISHED,
                    action = TicketActionsRefs.Publish.class,
                    recoveries = {
                        @TicketsGraph.Recovery(kind = PublicationFailures.Unavailable.class,
                            transition = SCHEDULE_RETRY, failsOn = ServiceUnavailableException.class),
                        @TicketsGraph.Recovery(kind = PublicationFailures.Rejected.class,
                            transition = RECORD_REJECTION, failsOn = ProviderRejectedException.class)},
                    failsOn = InvalidResponseException.class,
                    onFailure = MARK_INTEGRATION_FAILED)
                Outcome<Ticket, TicketState, TicketChange> publish(Ticket ticket);
            """;

    /** The facade up to the declaration of {@code publish}. */
    private static final String HEADER = """
            package fx;
            import io.github.graphmaquina.annotation.GenerateGraphApi;
            import io.github.graphmaquina.api.Machine;
            import io.github.graphmaquina.api.Outcome;
            import static fx.TicketChange.*;
            import static fx.TicketState.*;
            /** Publication facade used by recovery diagnostics. */ @GenerateGraphApi
            @TicketsGraph.Definition(initial = READY, terminals = {PUBLISHED, CLOSED}, allowsCycle = READY,
                state = TicketRefs.Status.class, id = TicketRefs.Id.class)
            @TicketsGraph.StatePolicy(state = READY, otherwise = "publish the ticket")
            @TicketsGraph.StatePolicy(state = RETRY_PENDING, otherwise = "retry the publication explicitly")
            @TicketsGraph.StatePolicy(state = REJECTED_BY_PROVIDER, otherwise = "close the rejected ticket")
            @TicketsGraph.StatePolicy(state = INTEGRATION_FAILED, otherwise = "close after manual review")
            @TicketsGraph.Transition(id = SCHEDULE_RETRY, from = READY, to = RETRY_PENDING)
            @TicketsGraph.Transition(id = RECORD_REJECTION, from = READY, to = REJECTED_BY_PROVIDER)
            @TicketsGraph.Transition(id = MARK_INTEGRATION_FAILED, from = READY, to = INTEGRATION_FAILED)
            @TicketsGraph.Transition(id = CLOSE, from = {REJECTED_BY_PROVIDER, INTEGRATION_FAILED}, to = CLOSED)
            public interface Tickets extends Machine<Ticket, TicketState, TicketChange> {
                @TicketsGraph.Transition(id = RETRY, from = RETRY_PENDING, to = READY)
                Outcome<Ticket, TicketState, TicketChange> retry(Ticket ticket);
            """;

    private RecoveryFixtures() { }

    /** The example graph: {@link #sources} with {@link #EXAMPLE_PUBLISH}. */
    static Map<String, String> example() {
        return sources(EXAMPLE_PUBLISH);
    }

    /** The whole machine, its facade declaring the {@code publish} operation as {@code publish} spells it. */
    static Map<String, String> sources(String publish) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.TicketState", """
                package fx;
                /** Fixture declaration for recovery diagnostics. */ public enum TicketState {
                    READY, PUBLISHED, RETRY_PENDING, REJECTED_BY_PROVIDER, INTEGRATION_FAILED, CLOSED
                }
                """);
        sources.put("fx.TicketChange", """
                package fx;
                /** Fixture declaration for recovery diagnostics. */ public enum TicketChange {
                    PUBLISH, SCHEDULE_RETRY, RECORD_REJECTION, MARK_INTEGRATION_FAILED, RETRY, CLOSE
                }
                """);
        sources.put("fx.Ticket", """
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                /** Fixture declaration for recovery diagnostics. */ public final class Ticket {
                    private final String id;
                    @GraphProperty private TicketState status = TicketState.READY;
                    public Ticket(String id) { this.id = id; }
                    @GraphProperty public String id() { return id; }
                    public TicketState status() { return status; }
                    public void status(TicketState value) { status = value; }
                }
                """);
        sources.put("fx.PublicationFailures", """
                package fx;
                import io.github.graphmaquina.api.FailureKind;
                /** Fixture declaration for recovery diagnostics. */ public final class PublicationFailures {
                    private PublicationFailures() { }
                    /** Fixture declaration for recovery diagnostics. */ public record Unavailable() implements FailureKind { }
                    /** Fixture declaration for recovery diagnostics. */ public record Rejected() implements FailureKind { }
                }
                """);
        sources.put("fx.ServiceUnavailableException", """
                package fx;
                /** Fixture declaration for recovery diagnostics. */ public class ServiceUnavailableException extends RuntimeException {
                    public ServiceUnavailableException(String message) {
                        super(message);
                    }
                }
                """);
        sources.put("fx.RateLimitedException", """
                package fx;
                /** Fixture declaration for recovery diagnostics. */ public final class RateLimitedException extends ServiceUnavailableException {
                    public RateLimitedException(String message) {
                        super(message);
                    }
                }
                """);
        sources.put("fx.ProviderRejectedException", """
                package fx;
                /** Fixture declaration for recovery diagnostics. */ public final class ProviderRejectedException extends RuntimeException {
                    public ProviderRejectedException(String message) {
                        super(message);
                    }
                }
                """);
        sources.put("fx.InvalidResponseException", """
                package fx;
                /** Fixture declaration for recovery diagnostics. */ public final class InvalidResponseException extends RuntimeException {
                    public InvalidResponseException(String message) {
                        super(message);
                    }
                }
                """);
        sources.put("fx.TicketActions", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.annotation.GraphHook;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                import java.io.IOException;
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.CompletionStage;
                /** Fixture declaration for recovery diagnostics. */ public final class TicketActions {
                    public final IOException reset = new IOException("connection reset by the provider");
                    public ActionOutcome result = ActionOutcome.ok();
                    public RuntimeException failure;
                    public Object publishInput;
                    public Object retryInput;
                    @GraphAction
                    public ActionOutcome publish(TransitionContext<Ticket, TicketState, TicketChange> context) {
                        publishInput = context.input().orElse(null);
                        if (failure != null) {
                            throw failure;
                        }
                        return result;
                    }
                    @GraphAction
                    public CompletionStage<ActionOutcome> publishAsync(
                            TransitionContext<Ticket, TicketState, TicketChange> context) {
                        if (failure != null) {
                            return CompletableFuture.failedFuture(failure);
                        }
                        return CompletableFuture.completedFuture(result);
                    }
                    @GraphAction
                    public ActionOutcome publishOverSocket(TransitionContext<Ticket, TicketState, TicketChange> context)
                            throws IOException {
                        throw reset;
                    }
                    @GraphAction
                    public ActionOutcome scheduleRetry(TransitionContext<Ticket, TicketState, TicketChange> context) {
                        retryInput = context.input().orElse(null);
                        return ActionOutcome.ok();
                    }
                    @GraphHook
                    public void published(Ticket ticket) { }
                }
                """);
        sources.put("fx.Tickets", HEADER + publish + "}\n");
        return sources;
    }
}
