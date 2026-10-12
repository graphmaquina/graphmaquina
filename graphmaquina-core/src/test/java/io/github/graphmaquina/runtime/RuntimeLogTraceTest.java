package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Billing.PAYMENTS;
import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static io.github.graphmaquina.fixtures.Keys.ITEMS;
import static io.github.graphmaquina.fixtures.Keys.ORDERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.CascadeException;
import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.UnhandledFailureException;
import io.github.graphmaquina.fixtures.Billing;
import io.github.graphmaquina.fixtures.Billing.Booking;
import io.github.graphmaquina.fixtures.Billing.BookingStatus;
import io.github.graphmaquina.fixtures.Billing.Payment;
import io.github.graphmaquina.fixtures.Billing.PaymentStatus;
import io.github.graphmaquina.fixtures.Billing.PaymentTransition;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.GatewayException;
import io.github.graphmaquina.fixtures.Item;
import io.github.graphmaquina.fixtures.ItemStatus;
import io.github.graphmaquina.fixtures.ItemTransition;
import io.github.graphmaquina.fixtures.LibraryLog;
import io.github.graphmaquina.fixtures.Order;
import io.github.graphmaquina.fixtures.OrderGraphs;
import io.github.graphmaquina.fixtures.OrderStatus;
import io.github.graphmaquina.fixtures.OrderTransition;
import io.github.graphmaquina.fixtures.Probe;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The TRACE lines of the runtime log, read through {@code java.util.logging} at {@code FINER}, which logs the DEBUG
 * lines too. Each step of a call logs how it resolved its edge, its binding check, each guard and action, its failure
 * route, each cascade and its result, the starts and exits of sub-machines and the failure that poisons the call, all
 * at the depth of the step; the commit, the hooks and the watch dispatch log what they do without a depth. A line
 * prints an entity only as the reference the engine holds, and nothing is built at TRACE while only DEBUG is on.
 */
class RuntimeLogTraceTest {

    private static final Pattern CALL_ID = Pattern.compile("(call|trigger)=(\\d+)");
    private static final Pattern ELAPSED = Pattern.compile(" elapsed=\\d+us$");
    private static final Pattern BUDGET = Pattern.compile(" budget=(\\d+)us$");

    /** A failure kind an action returns. */
    record Busy() implements FailureKind { }

    /** A throwable whose message cannot be read; every read is counted. */
    private static final class UnreadableFailure extends RuntimeException {
        private int reads;

        @Override
        public String getMessage() {
            reads++;
            throw new IllegalStateException("the message of this failure cannot be read");
        }
    }

    /** An identity value whose {@code hashCode()} throws: it can be printed, never put in a hash table. */
    private record UnhashableId(String id) {
        @Override
        public int hashCode() {
            throw new IllegalStateException("unhashable identity");
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /** An identity value whose {@code toString()} throws. */
    private record BrokenId(String id) {
        @Override
        public String toString() {
            throw new IllegalStateException("no text for " + id);
        }
    }

    /** An identity value that counts how many times it was printed. */
    private record CountedId(String id, AtomicInteger printed) {
        @Override
        public boolean equals(Object other) {
            return other instanceof CountedId that && id.equals(that.id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            printed.incrementAndGet();
            return id;
        }
    }

    /** States of {@link Lamp}, each printed by its {@code toString()} as something other than its name. */
    enum Shade {
        DARK, LIT;

        @Override
        public String toString() {
            return "shade " + ordinal();
        }
    }

    /** The one transition of {@link Lamp}, printed by its {@code toString()} as something other than its name. */
    enum Switch {
        LIGHT;

        @Override
        public String toString() {
            return "switch " + ordinal();
        }
    }

    /** An entity whose state and transition enums print otherwise than their names. */
    static final class Lamp {
        private Shade shade = Shade.DARK;

        Shade shade() {
            return shade;
        }

        void setShade(Shade shade) {
            this.shade = shade;
        }
    }

    /**
     * Each record as {@code LEVEL message}, every call id replaced by a letter in order of first appearance, A for the
     * first, the elapsed time that ends a line replaced by {@code <us>}, and so is the budget of an awaiting line. No
     * line carries the id 0.
     */
    private static List<String> lines(LibraryLog log) {
        Map<String, String> letters = new HashMap<>();
        List<String> lines = new ArrayList<>();
        for (LogRecord record : log.records()) {
            Matcher ids = CALL_ID.matcher(record.getMessage());
            StringBuilder line = new StringBuilder(record.getLevel().getName()).append(' ');
            while (ids.find()) {
                String id = ids.group(2);
                assertNotEquals("0", id, record.getMessage());
                if (!letters.containsKey(id)) {
                    letters.put(id, String.valueOf((char) ('A' + letters.size())));
                }
                ids.appendReplacement(line, ids.group(1) + "=" + letters.get(id));
            }
            ids.appendTail(line);
            String text = ELAPSED.matcher(line).replaceFirst(" elapsed=<us>");
            lines.add(BUDGET.matcher(text).replaceFirst(" budget=<us>"));
        }
        return lines;
    }

    /** The budgets, in microseconds, that the awaiting lines of {@code log} printed, in order. */
    private static List<Long> budgets(LibraryLog log) {
        List<Long> budgets = new ArrayList<>();
        for (LogRecord record : log.records()) {
            Matcher budget = BUDGET.matcher(record.getMessage());
            if (budget.find()) {
                budgets.add(Long.parseLong(budget.group(1)));
            }
        }
        return budgets;
    }

    /**
     * DRAFT -SUBMIT-> REVIEW, shaped by {@code submit}; DRAFT -DISCARD-> ARCHIVED, shaped by {@code discard};
     * REVIEW -PUBLISH-> PUBLISHED and PUBLISHED -ARCHIVE-> ARCHIVED are plain moves.
     */
    private static GraphDefinition<Doc, DocStatus, DocTransition> docs(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit,
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> discard) {
        GraphBuilder<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded")
                .statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived");
        graph = submit.apply(graph.transition(SUBMIT, DRAFT, REVIEW)).endTransition();
        graph = discard.apply(graph.transition(DISCARD, DRAFT, ARCHIVED)).endTransition();
        return graph.transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    private static GraphDefinition<Doc, DocStatus, DocTransition> docs(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit) {
        return docs(submit, UnaryOperator.<TransitionBuilder<Doc, DocStatus, DocTransition>>identity());
    }

    private static GraphDefinition<Doc, DocStatus, DocTransition> plain() {
        return docs(UnaryOperator.<TransitionBuilder<Doc, DocStatus, DocTransition>>identity());
    }

    private static MachineRegistry registry(GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .build();
    }

    /** {@link #registry(GraphDefinition)} for a graph with an async action, which needs an asyncTimeout. */
    private static MachineRegistry registry(GraphDefinition<Doc, DocStatus, DocTransition> graph,
                                            Duration asyncTimeout) {
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id)
                        .asyncTimeout(asyncTimeout))
                .build();
    }

    /** Orders that watch their items from PAID and ship once every item is packed; {@code cancel} cancels them. */
    private static MachineRegistry orders(Probe<Order, OrderStatus, OrderTransition> cancel) {
        return MachineRegistry.builder()
                .add(StateMachines.from(OrderGraphs.order(
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                                        CompletableFuture.completedFuture(ActionOutcome.ok()),
                                new Probe<>(), cancel,
                                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass(),
                                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass()))
                        .state(Order::status, Order::setStatus).identity(Order::id)
                        .asyncTimeout(Duration.ofSeconds(5)))
                .add(StateMachines.from(OrderGraphs.item(
                                (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> ActionOutcome.ok()))
                        .state(Item::status, Item::setStatus).identity(Item::id))
                .build();
    }

    @Test
    void anAppliedCallTracesItsStepItsCommitAndItsHook() {
        Doc doc = new Doc("1", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> reviewed = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("Ready", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> GuardResult.pass())
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                                ActionOutcome.ok())
                        .afterCommit("Notify", (Doc target) -> { }));
        try (MachineRegistry registry = registry(reviewed); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            registry.of(DOCS).execute(doc, SUBMIT);
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINER call=A depth=0 resolved Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW kind=ACTION",
                    "FINER call=A depth=0 guard Ready PASS elapsed=<us>",
                    "FINER call=A depth=0 action Review start mode=sync",
                    "FINER call=A depth=0 action Review OK elapsed=<us>",
                    "FINER call=A commit-check entities=1 hops=1",
                    "FINER call=A setter Doc/DocStatus#1 DRAFT -> REVIEW entries=1",
                    "FINE call=A committed Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW depth=0",
                    "FINER call=A hook Notify Doc/DocStatus#1 SUBMIT",
                    "FINE call=A end code=APPLIED state=REVIEW entity=Doc/DocStatus#1 records=1 starts=0 reactions=0"
                            + " aborted=0 elapsed=<us>"), lines(log));
        }
    }

    @Test
    void aMissingEdgeAndARejectingGuardAreTraced() {
        Doc reviewed = new Doc("1", REVIEW);
        Doc draft = new Doc("2", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> guarded = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("Ready",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> GuardResult.reject("no reviewer")));
        try (MachineRegistry registry = registry(guarded); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            registry.of(DOCS).execute(reviewed, SUBMIT);
            registry.of(DOCS).execute(draft, SUBMIT);
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINER call=A depth=0 no-edge Doc/DocStatus#1 REVIEW SUBMIT",
                    "FINE call=A end code=NO_TRANSITION state=REVIEW entity=Doc/DocStatus#1 records=0 starts=0"
                            + " reactions=0 aborted=0 reason=\"a review is published\" elapsed=<us>",
                    "FINE call=B start Doc/DocStatus SUBMIT",
                    "FINER call=B depth=0 resolved Doc/DocStatus#2 DRAFT -SUBMIT-> REVIEW kind=MOVE",
                    "FINER call=B depth=0 guard Ready REJECT reason=\"no reviewer\" elapsed=<us>",
                    "FINE call=B end code=GUARD_REJECTED state=DRAFT entity=Doc/DocStatus#2 records=0 starts=0"
                            + " reactions=0 aborted=0 reason=\"no reviewer\" elapsed=<us>"), lines(log));
        }
    }

    /** A returned failure with a kind and one without, each routed, and the recovery action of their route. */
    @Test
    void aFailedActionTracesItsKindItsRouteAndTheRecoveryAction() {
        Doc busy = new Doc("1", DRAFT);
        Doc away = new Doc("2", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> refusing = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                                ctx.entity() == busy
                                        ? ActionOutcome.failed(Busy.class, "the reviewer is busy")
                                        : ActionOutcome.failed("the reviewer is away"))
                        .recover(Busy.class, DISCARD).onFailure(DISCARD),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.action("Archive",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> ActionOutcome.ok()));
        try (MachineRegistry registry = registry(refusing); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            assertEquals(OutcomeCode.FAILURE_ROUTED, registry.of(DOCS).execute(busy, SUBMIT).code());
            assertEquals(OutcomeCode.FAILURE_ROUTED, registry.of(DOCS).execute(away, SUBMIT).code());
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINER call=A depth=0 resolved Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW kind=ACTION",
                    "FINER call=A depth=0 action Review start mode=sync",
                    "FINER call=A depth=0 action Review FAILED kind=Busy reason=\"the reviewer is busy\""
                            + " elapsed=<us>",
                    "FINER call=A depth=0 route SUBMIT -> DISCARD rollback-to=0 kind=Busy"
                            + " reason=\"the reviewer is busy\"",
                    "FINER call=A depth=0 action Archive start mode=sync",
                    "FINER call=A depth=0 action Archive OK elapsed=<us>",
                    "FINER call=A commit-check entities=1 hops=1",
                    "FINER call=A setter Doc/DocStatus#1 DRAFT -> ARCHIVED entries=1",
                    "FINE call=A committed Doc/DocStatus#1 DRAFT -DISCARD-> ARCHIVED depth=0",
                    "FINE call=A end code=FAILURE_ROUTED state=ARCHIVED entity=Doc/DocStatus#1 records=1 starts=0"
                            + " reactions=0 aborted=0 reason=\"the reviewer is busy\" elapsed=<us>",
                    "FINE call=B start Doc/DocStatus SUBMIT",
                    "FINER call=B depth=0 resolved Doc/DocStatus#2 DRAFT -SUBMIT-> REVIEW kind=ACTION",
                    "FINER call=B depth=0 action Review start mode=sync",
                    "FINER call=B depth=0 action Review FAILED reason=\"the reviewer is away\" elapsed=<us>",
                    "FINER call=B depth=0 route SUBMIT -> DISCARD rollback-to=0 reason=\"the reviewer is away\"",
                    "FINER call=B depth=0 action Archive start mode=sync",
                    "FINER call=B depth=0 action Archive OK elapsed=<us>",
                    "FINER call=B commit-check entities=1 hops=1",
                    "FINER call=B setter Doc/DocStatus#2 DRAFT -> ARCHIVED entries=1",
                    "FINE call=B committed Doc/DocStatus#2 DRAFT -DISCARD-> ARCHIVED depth=0",
                    "FINE call=B end code=FAILURE_ROUTED state=ARCHIVED entity=Doc/DocStatus#2 records=1 starts=0"
                            + " reactions=0 aborted=0 reason=\"the reviewer is away\" elapsed=<us>"), lines(log));
        }
    }

    @Test
    void aFailureWithoutARouteIsTracedAsUnhandled() {
        Doc doc = new Doc("1", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> unrouted = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Review",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                                ActionOutcome.failed("the reviewer is away")));
        try (MachineRegistry registry = registry(unrouted); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            UnhandledFailureException error = assertThrows(UnhandledFailureException.class,
                    () -> registry.of(DOCS).execute(doc, SUBMIT));
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINER call=A depth=0 resolved Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW kind=ACTION",
                    "FINER call=A depth=0 action Review start mode=sync",
                    "FINER call=A depth=0 action Review FAILED reason=\"the reviewer is away\" elapsed=<us>",
                    "FINER call=A depth=0 unhandled SUBMIT reason=\"the reviewer is away\"",
                    "FINE call=A failed phase=ACTION written=NONE entity=Doc/DocStatus#1 error=" + error
                            + " elapsed=<us>"), lines(log));
        }
    }

    /**
     * A throwable a declared type accepts takes the route, whose line prints the reason composed from it, which the
     * end line leaves out; any other aborts the call. The THREW line names the class of what the action threw.
     */
    @Test
    void aThrowingActionIsTracedWhetherItsThrowableIsRoutedOrNot() {
        Doc routed = new Doc("1", DRAFT);
        Doc aborted = new Doc("2", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> throwing = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            if (ctx.entity() == routed) {
                                throw new GatewayException("the gateway is down");
                            }
                            throw new IllegalStateException("no reviewer list");
                        })
                        .failsOn(GatewayException.class).onFailure(DISCARD));
        try (MachineRegistry registry = registry(throwing); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            assertEquals(OutcomeCode.FAILURE_ROUTED, registry.of(DOCS).execute(routed, SUBMIT).code());
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(aborted, SUBMIT));
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINER call=A depth=0 resolved Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW kind=ACTION",
                    "FINER call=A depth=0 action Review start mode=sync",
                    "FINER call=A depth=0 action Review THREW error=io.github.graphmaquina.fixtures.GatewayException"
                            + " elapsed=<us>",
                    "FINER call=A depth=0 route SUBMIT -> DISCARD rollback-to=0"
                            + " reason=\"GatewayException: the gateway is down\"",
                    "FINER call=A commit-check entities=1 hops=1",
                    "FINER call=A setter Doc/DocStatus#1 DRAFT -> ARCHIVED entries=1",
                    "FINE call=A committed Doc/DocStatus#1 DRAFT -DISCARD-> ARCHIVED depth=0",
                    "FINE call=A end code=FAILURE_ROUTED state=ARCHIVED entity=Doc/DocStatus#1 records=1 starts=0"
                            + " reactions=0 aborted=0 elapsed=<us>",
                    "FINE call=B start Doc/DocStatus SUBMIT",
                    "FINER call=B depth=0 resolved Doc/DocStatus#2 DRAFT -SUBMIT-> REVIEW kind=ACTION",
                    "FINER call=B depth=0 action Review start mode=sync",
                    "FINER call=B depth=0 action Review THREW error=java.lang.IllegalStateException elapsed=<us>",
                    "FINE call=B failed phase=ACTION written=NONE entity=Doc/DocStatus#2 error=" + error
                            + " elapsed=<us>"), lines(log));
        }
    }

    /**
     * After a caught cascade failure poisoned the call, a throwable a declared type accepts ends it with the poison,
     * and the engine never reads its message: neither does the THREW line, which names its class.
     */
    @Test
    void theThrewLineNeverReadsTheMessageOfWhatTheActionThrew() {
        Doc doc = new Doc("1", DRAFT);
        Doc reviewed = new Doc("2", REVIEW);
        UnreadableFailure unreadable = new UnreadableFailure();
        GraphDefinition<Doc, DocStatus, DocTransition> poisoned = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            try {
                                ctx.cascade(DOCS, reviewed, SUBMIT);
                            } catch (TransitionAbortedException failure) {
                                throw unreadable;
                            }
                            return ActionOutcome.ok();
                        })
                        .failsOn(UnreadableFailure.class).onFailure(DISCARD));
        try (MachineRegistry registry = registry(poisoned); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            TransitionAbortedException poison = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(doc, SUBMIT));
            assertEquals(0, unreadable.reads);
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINER call=A depth=0 resolved Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW kind=ACTION",
                    "FINER call=A depth=0 action Review start mode=sync",
                    "FINER call=A depth=0 cascade Doc/DocStatus#2 SUBMIT",
                    "FINER call=A depth=1 no-edge Doc/DocStatus#2 REVIEW SUBMIT",
                    "FINER call=A depth=1 poison error=" + poison,
                    "FINER call=A depth=0 action Review THREW error=" + UnreadableFailure.class.getName()
                            + " elapsed=<us>",
                    "FINE call=A failed phase=ACTION written=NONE entity=Doc/DocStatus#1 error=" + poison
                            + " elapsed=<us>"), lines(log));
        }
    }

    /**
     * The budget is what is left of the wait when it starts: none when neither the edge nor the call sets a limit,
     * here an asyncTimeout of {@code Duration.ofNanos(Long.MAX_VALUE)}; at most the edge's timeout when it sets one.
     */
    @Test
    void anAsyncActionTracesItsBudgetAndHowItsWaitEnded() {
        Doc unbounded = new Doc("1", DRAFT);
        Doc bounded = new Doc("2", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> answering = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.asyncAction("Review",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                                CompletableFuture.completedFuture(ActionOutcome.ok())));
        GraphDefinition<Doc, DocStatus, DocTransition> silent = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .asyncAction("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                                new CompletableFuture<ActionOutcome>())
                        .timeout(Duration.ofMillis(20)));
        try (MachineRegistry fast = registry(answering, Duration.ofNanos(Long.MAX_VALUE));
             MachineRegistry slow = registry(silent, Duration.ofSeconds(5));
             LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            fast.of(DOCS).execute(unbounded, SUBMIT);
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> slow.of(DOCS).execute(bounded, SUBMIT));
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINER call=A depth=0 resolved Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW kind=ACTION",
                    "FINER call=A depth=0 action Review start mode=async",
                    "FINER call=A depth=0 action Review awaiting budget=none",
                    "FINER call=A depth=0 action Review OK elapsed=<us>",
                    "FINER call=A commit-check entities=1 hops=1",
                    "FINER call=A setter Doc/DocStatus#1 DRAFT -> REVIEW entries=1",
                    "FINE call=A committed Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW depth=0",
                    "FINE call=A end code=APPLIED state=REVIEW entity=Doc/DocStatus#1 records=1 starts=0 reactions=0"
                            + " aborted=0 elapsed=<us>",
                    "FINE call=B start Doc/DocStatus SUBMIT",
                    "FINER call=B depth=0 resolved Doc/DocStatus#2 DRAFT -SUBMIT-> REVIEW kind=ACTION",
                    "FINER call=B depth=0 action Review start mode=async",
                    "FINER call=B depth=0 action Review awaiting budget=<us>",
                    "FINER call=B depth=0 action Review THREW error=" + error.getClass().getName() + " elapsed=<us>",
                    "FINE call=B failed phase=ACTION written=NONE entity=Doc/DocStatus#2 error=" + error
                            + " elapsed=<us>"), lines(log));
            assertTrue(error.getMessage().contains("waiting for the action Review timed out"), error.getMessage());
            long budget = budgets(log).get(0);
            assertTrue(budget > 0 && budget <= 20_000, "budget=" + budget);
        }
    }

    /** Each cascade is traced by the step that asks for it, at that step's depth, around the steps it runs. */
    @Test
    void aCascadeTracesItsPeerTheStepItRunsAndItsResult() {
        Order order = new Order("o1", OrderStatus.NEW);
        new Item("i1", ItemStatus.PENDING, Optional.of(order));
        Probe<Order, OrderStatus, OrderTransition> cancel = new Probe<>();
        cancel.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            Item item = ctx.entity().items().get(0);
            ctx.cascade(ITEMS, item, ItemTransition.START_PACKING);
            ctx.cascade(ITEMS, item, ItemTransition.PACK);
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = orders(cancel); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            registry.of(ORDERS).execute(order, OrderTransition.CANCEL);
            assertEquals(List.of(
                    "FINE call=A start Order/OrderStatus CANCEL",
                    "FINER call=A depth=0 resolved Order/OrderStatus#o1 NEW -CANCEL-> CANCELLED kind=ACTION",
                    "FINER call=A depth=0 action CancelOrder start mode=sync",
                    "FINER call=A depth=0 cascade Item/ItemStatus#i1 START_PACKING",
                    "FINER call=A depth=1 resolved Item/ItemStatus#i1 PENDING -START_PACKING-> PACKING kind=MOVE",
                    "FINER call=A depth=0 cascade-result Item/ItemStatus#i1 START_PACKING code=APPLIED",
                    "FINER call=A depth=0 cascade Item/ItemStatus#i1 PACK",
                    "FINER call=A depth=1 resolved Item/ItemStatus#i1 PACKING -PACK-> PACKED kind=ACTION",
                    "FINER call=A depth=1 action PackItem start mode=sync",
                    "FINER call=A depth=1 action PackItem OK elapsed=<us>",
                    "FINER call=A depth=0 cascade-result Item/ItemStatus#i1 PACK code=APPLIED",
                    "FINER call=A depth=0 action CancelOrder OK elapsed=<us>",
                    "FINER call=A commit-check entities=2 hops=3",
                    "FINER call=A setter Order/OrderStatus#o1 NEW -> CANCELLED entries=1",
                    "FINER call=A setter Item/ItemStatus#i1 PENDING -> PACKED entries=2",
                    "FINE call=A committed Order/OrderStatus#o1 NEW -CANCEL-> CANCELLED depth=0",
                    "FINE call=A committed Item/ItemStatus#i1 PENDING -START_PACKING-> PACKING depth=1",
                    "FINE call=A committed Item/ItemStatus#i1 PACKING -PACK-> PACKED depth=1",
                    "FINER call=A watch watcher=Order/OrderStatus moved=Item/ItemStatus#i1 owner=none",
                    "FINE call=A end code=APPLIED state=CANCELLED entity=Order/OrderStatus#o1 records=3 starts=0"
                            + " reactions=0 aborted=0 elapsed=<us>"), lines(log));
        }
    }

    @Test
    void cascadeAllTracesItsPolicyForEachElement() {
        Order order = new Order("o1", OrderStatus.NEW);
        new Item("i1", ItemStatus.PENDING, Optional.of(order));
        new Item("i2", ItemStatus.PENDING, Optional.of(order));
        Probe<Order, OrderStatus, OrderTransition> cancel = new Probe<>();
        cancel.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            ctx.cascadeAll(ITEMS, ctx.entity().items(), ItemTransition.CANCEL, CascadePolicy.FAIL_FAST);
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = orders(cancel); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            registry.of(ORDERS).execute(order, OrderTransition.CANCEL);
            assertEquals(List.of(
                    "FINE call=A start Order/OrderStatus CANCEL",
                    "FINER call=A depth=0 resolved Order/OrderStatus#o1 NEW -CANCEL-> CANCELLED kind=ACTION",
                    "FINER call=A depth=0 action CancelOrder start mode=sync",
                    "FINER call=A depth=0 cascade Item/ItemStatus#i1 CANCEL policy=FAIL_FAST",
                    "FINER call=A depth=1 resolved Item/ItemStatus#i1 PENDING -CANCEL-> CANCELLED kind=MOVE",
                    "FINER call=A depth=0 cascade-result Item/ItemStatus#i1 CANCEL code=APPLIED",
                    "FINER call=A depth=0 cascade Item/ItemStatus#i2 CANCEL policy=FAIL_FAST",
                    "FINER call=A depth=1 resolved Item/ItemStatus#i2 PENDING -CANCEL-> CANCELLED kind=MOVE",
                    "FINER call=A depth=0 cascade-result Item/ItemStatus#i2 CANCEL code=APPLIED",
                    "FINER call=A depth=0 action CancelOrder OK elapsed=<us>",
                    "FINER call=A commit-check entities=3 hops=3",
                    "FINER call=A setter Order/OrderStatus#o1 NEW -> CANCELLED entries=1",
                    "FINER call=A setter Item/ItemStatus#i1 PENDING -> CANCELLED entries=1",
                    "FINER call=A setter Item/ItemStatus#i2 PENDING -> CANCELLED entries=1",
                    "FINE call=A committed Order/OrderStatus#o1 NEW -CANCEL-> CANCELLED depth=0",
                    "FINE call=A committed Item/ItemStatus#i1 PENDING -CANCEL-> CANCELLED depth=1",
                    "FINE call=A committed Item/ItemStatus#i2 PENDING -CANCEL-> CANCELLED depth=1",
                    "FINER call=A watch watcher=Order/OrderStatus moved=Item/ItemStatus#i1 owner=none",
                    "FINER call=A watch watcher=Order/OrderStatus moved=Item/ItemStatus#i2 owner=none",
                    "FINE call=A end code=APPLIED state=CANCELLED entity=Order/OrderStatus#o1 records=3 starts=0"
                            + " reactions=0 aborted=0 elapsed=<us>"), lines(log));
        }
    }

    /**
     * COLLECT: the element whose step aborts poisons the call, is rolled back, which clears the poison, and the next
     * element runs; once the last one ran, the aggregate poisons the call.
     */
    @Test
    void collectTracesTheRollbackOfEachFailedElementAndTheAggregatePoison() {
        Order order = new Order("o1", OrderStatus.NEW);
        new Item("i1", ItemStatus.PENDING, Optional.of(order));
        new Item("i2", ItemStatus.PACKED, Optional.of(order));
        new Item("i3", ItemStatus.PENDING, Optional.of(order));
        Probe<Order, OrderStatus, OrderTransition> cancel = new Probe<>();
        cancel.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            ctx.cascadeAll(ITEMS, ctx.entity().items(), ItemTransition.CANCEL, CascadePolicy.COLLECT);
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = orders(cancel); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            CascadeException aggregate = assertThrows(CascadeException.class,
                    () -> registry.of(ORDERS).execute(order, OrderTransition.CANCEL));
            TransitionAbortedException element = aggregate.errors().get(0);
            assertEquals(List.of(
                    "FINE call=A start Order/OrderStatus CANCEL",
                    "FINER call=A depth=0 resolved Order/OrderStatus#o1 NEW -CANCEL-> CANCELLED kind=ACTION",
                    "FINER call=A depth=0 action CancelOrder start mode=sync",
                    "FINER call=A depth=0 cascade Item/ItemStatus#i1 CANCEL policy=COLLECT",
                    "FINER call=A depth=1 resolved Item/ItemStatus#i1 PENDING -CANCEL-> CANCELLED kind=MOVE",
                    "FINER call=A depth=0 cascade-result Item/ItemStatus#i1 CANCEL code=APPLIED",
                    "FINER call=A depth=0 cascade Item/ItemStatus#i2 CANCEL policy=COLLECT",
                    "FINER call=A depth=1 no-edge Item/ItemStatus#i2 PACKED CANCEL",
                    "FINER call=A depth=1 poison error=" + element,
                    "FINER call=A depth=0 collect-rollback Item/ItemStatus#i2 CANCEL error=" + element,
                    "FINER call=A depth=0 cascade Item/ItemStatus#i3 CANCEL policy=COLLECT",
                    "FINER call=A depth=1 resolved Item/ItemStatus#i3 PENDING -CANCEL-> CANCELLED kind=MOVE",
                    "FINER call=A depth=0 cascade-result Item/ItemStatus#i3 CANCEL code=APPLIED",
                    "FINER call=A depth=0 poison error=" + aggregate,
                    "FINER call=A depth=0 action CancelOrder THREW error=" + aggregate.getClass().getName()
                            + " elapsed=<us>",
                    "FINE call=A failed phase=ACTION written=NONE entity=Order/OrderStatus#o1 error=" + aggregate
                            + " elapsed=<us>"), lines(log));
        }
    }

    /**
     * A nested abort poisons the call at the depth of its step; a cascade that cannot start, here to a machine the
     * registry does not hold, poisons it at the depth of the step that asked for it. Neither is traced twice.
     */
    @Test
    void aNestedAbortAndACascadeThatCannotStartTraceThePoison() {
        Order nested = new Order("o1", OrderStatus.NEW);
        new Item("i1", ItemStatus.PACKED, Optional.of(nested));
        Order foreign = new Order("o2", OrderStatus.NEW);
        Probe<Order, OrderStatus, OrderTransition> cancel = new Probe<>();
        cancel.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            if (ctx.entity() == nested) {
                ctx.cascade(ITEMS, ctx.entity().items().get(0), ItemTransition.CANCEL);
            } else {
                ctx.cascade(DOCS, new Doc("9", DRAFT), SUBMIT);
            }
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = orders(cancel); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            TransitionAbortedException rejected = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(ORDERS).execute(nested, OrderTransition.CANCEL));
            TransitionAbortedException unknown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(ORDERS).execute(foreign, OrderTransition.CANCEL));
            assertTrue(unknown.getMessage().contains("the cascade to Doc/DocStatus failed"), unknown.getMessage());
            assertEquals(List.of(
                    "FINE call=A start Order/OrderStatus CANCEL",
                    "FINER call=A depth=0 resolved Order/OrderStatus#o1 NEW -CANCEL-> CANCELLED kind=ACTION",
                    "FINER call=A depth=0 action CancelOrder start mode=sync",
                    "FINER call=A depth=0 cascade Item/ItemStatus#i1 CANCEL",
                    "FINER call=A depth=1 no-edge Item/ItemStatus#i1 PACKED CANCEL",
                    "FINER call=A depth=1 poison error=" + rejected,
                    "FINER call=A depth=0 action CancelOrder THREW error=" + rejected.getClass().getName()
                            + " elapsed=<us>",
                    "FINE call=A failed phase=ACTION written=NONE entity=Order/OrderStatus#o1 error=" + rejected
                            + " elapsed=<us>",
                    "FINE call=B start Order/OrderStatus CANCEL",
                    "FINER call=B depth=0 resolved Order/OrderStatus#o2 NEW -CANCEL-> CANCELLED kind=ACTION",
                    "FINER call=B depth=0 action CancelOrder start mode=sync",
                    "FINER call=B depth=0 poison error=" + unknown,
                    "FINER call=B depth=0 action CancelOrder THREW error=" + unknown.getClass().getName()
                            + " elapsed=<us>",
                    "FINE call=B failed phase=ACTION written=NONE entity=Order/OrderStatus#o2 error=" + unknown
                            + " elapsed=<us>"), lines(log));
        }
    }

    /**
     * A payment held in its booking's DEPOSIT is captured: its binding is checked, its terminal state runs the
     * booking's exit, which names the child it exits, and the exit enters BALANCE, which starts the balance payment.
     */
    @Test
    void aSubmachineChildTracesItsBindingItsExitAndTheStartThatFollows() {
        Billing billing = new Billing();
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Payment balance = new Payment("p2", PaymentStatus.CAPTURED);
        new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit).attachBalance(balance);
        try (MachineRegistry registry = billing.registry(); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            registry.of(PAYMENTS).execute(deposit, PaymentTransition.CAPTURE);
            assertEquals(List.of(
                    "FINE call=A start Payment/PaymentStatus CAPTURE",
                    "FINER call=A depth=0 binding Payment/PaymentStatus#d1 parent=Booking/BookingStatus#b1"
                            + " node=DEPOSIT",
                    "FINER call=A depth=0 resolved Payment/PaymentStatus#d1 AUTHORIZING -CAPTURE-> CAPTURED"
                            + " kind=ACTION",
                    "FINER call=A depth=0 action Capture start mode=sync",
                    "FINER call=A depth=0 action Capture OK elapsed=<us>",
                    "FINER call=A depth=0 submachine-exit Payment/PaymentStatus#d1 terminal=CAPTURED"
                            + " parent=Booking/BookingStatus#b1 exit=DEPOSIT_PAID",
                    "FINER call=A depth=1 resolved Booking/BookingStatus#b1 DEPOSIT -DEPOSIT_PAID-> BALANCE"
                            + " kind=ACTION exit-of=Payment/PaymentStatus#d1",
                    "FINER call=A depth=1 guard Settled PASS elapsed=<us>",
                    "FINER call=A depth=1 submachine-start Payment/PaymentStatus#p2 CAPTURED -> AUTHORIZING"
                            + " parent=Booking/BookingStatus#b1 node=BALANCE",
                    "FINER call=A depth=1 action Settle start mode=sync",
                    "FINER call=A depth=1 action Settle OK elapsed=<us>",
                    "FINER call=A commit-check entities=3 hops=2",
                    "FINER call=A setter Payment/PaymentStatus#d1 AUTHORIZING -> CAPTURED entries=1",
                    "FINER call=A setter Booking/BookingStatus#b1 DEPOSIT -> BALANCE entries=1",
                    "FINER call=A setter Payment/PaymentStatus#p2 CAPTURED -> AUTHORIZING entries=1",
                    "FINE call=A committed Payment/PaymentStatus#d1 AUTHORIZING -CAPTURE-> CAPTURED depth=0",
                    "FINE call=A committed Booking/BookingStatus#b1 DEPOSIT -DEPOSIT_PAID-> BALANCE depth=1",
                    "FINE call=A started Payment/PaymentStatus#p2 CAPTURED -> AUTHORIZING"
                            + " parent=Booking/BookingStatus#b1 node=BALANCE depth=1",
                    "FINER call=A hook Audit Payment/PaymentStatus#d1 CAPTURE",
                    "FINER call=A hook Audit Booking/BookingStatus#b1 DEPOSIT_PAID",
                    "FINER call=A watch watcher=Booking/BookingStatus moved=Payment/PaymentStatus#d1 owner=none",
                    "FINER call=A watch watcher=Booking/BookingStatus moved=Payment/PaymentStatus#p2 owner=none",
                    "FINE call=A end code=APPLIED state=CAPTURED entity=Payment/PaymentStatus#d1 records=2 starts=1"
                            + " reactions=0 aborted=0 elapsed=<us>"), lines(log));
        }
    }

    @Test
    void aDetachedChildIsTraced() {
        Billing billing = new Billing();
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        new Booking("b1", BookingStatus.CANCELLED).attachDeposit(deposit);
        try (MachineRegistry registry = billing.registry(); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            assertEquals(OutcomeCode.DETACHED,
                    registry.of(PAYMENTS).execute(deposit, PaymentTransition.CAPTURE).code());
            assertEquals(List.of(
                    "FINE call=A start Payment/PaymentStatus CAPTURE",
                    "FINER call=A depth=0 detached Payment/PaymentStatus#d1"
                            + " reason=\"no parent holds it in DEPOSIT or BALANCE\"",
                    "FINE call=A end code=DETACHED state=AUTHORIZING entity=Payment/PaymentStatus#d1 records=0"
                            + " starts=0 reactions=0 aborted=0 reason=\"no parent holds it in DEPOSIT or BALANCE\""
                            + " elapsed=<us>"), lines(log));
        }
    }

    @Test
    void aCommitConflictIsTraced() {
        Doc doc = new Doc("1", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> meddling = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Review",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.entity().setStatus(PUBLISHED);
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(meddling); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            CommitFailedException error = assertThrows(CommitFailedException.class,
                    () -> registry.of(DOCS).execute(doc, SUBMIT));
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINER call=A depth=0 resolved Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW kind=ACTION",
                    "FINER call=A depth=0 action Review start mode=sync",
                    "FINER call=A depth=0 action Review OK elapsed=<us>",
                    "FINER call=A commit-check entities=1 hops=1",
                    "FINER call=A commit-conflict reason=\"the state of Doc/DocStatus#1 changed during the"
                            + " transaction: expected DRAFT but is PUBLISHED; an action called the setter, or a"
                            + " concurrent transition on the same entity wrote it first\"",
                    "FINE call=A failed phase=COMMIT written=NONE entity=Doc/DocStatus#1 error=" + error
                            + " elapsed=<us>"), lines(log));
        }
    }

    /**
     * A line prints the identity the engine already computed: a call logged at TRACE runs the identity function and
     * the state getter exactly as often as the same call with the logger off.
     */
    @Test
    void aLineRunsNeitherTheIdentityFunctionNorTheStateGetter() {
        AtomicInteger identities = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        GraphDefinition<Doc, DocStatus, DocTransition> reviewed = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("Ready", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> GuardResult.pass())
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                                ActionOutcome.ok()));
        List<Integer> counts = new ArrayList<>();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(reviewed)
                        .state((Doc target) -> {
                            reads.incrementAndGet();
                            return target.status();
                        }, Doc::setStatus)
                        .identity((Doc target) -> {
                            identities.incrementAndGet();
                            return target.id();
                        }))
                .build()) {
            registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT);
            counts.add(identities.getAndSet(0));
            counts.add(reads.getAndSet(0));
            try (LibraryLog log = LibraryLog.runtime(Level.FINER)) {
                registry.of(DOCS).execute(new Doc("2", DRAFT), SUBMIT);
                assertEquals(9, log.records().size());
            }
            counts.add(identities.get());
            counts.add(reads.get());
        }
        assertTrue(counts.get(0) > 0 && counts.get(1) > 0, counts.toString());
        assertEquals(counts.subList(0, 2), counts.subList(2, 4));
    }

    /** The setter line is logged before the setter runs: a setter that throws leaves it, then the failed line. */
    @Test
    void theSetterLineIsLoggedBeforeTheSetterRuns() {
        Doc doc = new Doc("1", DRAFT);
        IllegalStateException locked = new IllegalStateException("the row is locked");
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(plain()).state(Doc::status, (Doc target, DocStatus status) -> {
                    throw locked;
                }).identity(Doc::id))
                .build(); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            CommitFailedException error = assertThrows(CommitFailedException.class,
                    () -> registry.of(DOCS).execute(doc, SUBMIT));
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINER call=A depth=0 resolved Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW kind=MOVE",
                    "FINER call=A commit-check entities=1 hops=1",
                    "FINER call=A setter Doc/DocStatus#1 DRAFT -> REVIEW entries=1",
                    "FINE call=A failed phase=COMMIT written=PARTIAL entity=Doc/DocStatus#1 error=" + error
                            + " elapsed=<us>"), lines(log));
        }
    }

    /**
     * A guard that throws logs no verdict, and an action that throws an Error logs no end line: the lines of a call
     * stop where it threw, before its failed line, and the Error gets none.
     */
    @Test
    void aGuardThatThrowsAndAnActionsErrorLogNoVerdict() {
        Doc guarded = new Doc("1", DRAFT);
        Doc fatal = new Doc("2", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("Ready", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            if (ctx.entity() == guarded) {
                                throw new IllegalStateException("no reviewer list");
                            }
                            return GuardResult.pass();
                        })
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw new RuntimeLogDebugTest.Fatal("out of reviewers");
                        }));
        try (MachineRegistry registry = registry(graph); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(guarded, SUBMIT));
            assertThrows(RuntimeLogDebugTest.Fatal.class, () -> registry.of(DOCS).execute(fatal, SUBMIT));
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINER call=A depth=0 resolved Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW kind=ACTION",
                    "FINE call=A failed phase=GUARD written=NONE entity=Doc/DocStatus#1 error=" + error
                            + " elapsed=<us>",
                    "FINE call=B start Doc/DocStatus SUBMIT",
                    "FINER call=B depth=0 resolved Doc/DocStatus#2 DRAFT -SUBMIT-> REVIEW kind=ACTION",
                    "FINER call=B depth=0 guard Ready PASS elapsed=<us>",
                    "FINER call=B depth=0 action Review start mode=sync"), lines(log));
        }
    }

    /**
     * An Error that leaves a cascade, single or {@code COLLECT}, poisons the call but logs no poison line: the lines of
     * the call stop where the cascaded action threw it, and the Error propagates as itself.
     */
    @Test
    void anErrorThatLeavesACascadeLogsNoPoison() {
        Doc single = new Doc("p1", DRAFT);
        Doc collecting = new Doc("p2", DRAFT);
        Doc first = new Doc("c1", DRAFT);
        Doc second = new Doc("c2", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw new RuntimeLogDebugTest.Fatal("out of reviewers");
                        }),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard
                        .action("Collect", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            if (ctx.entity() == single) {
                                ctx.cascade(DOCS, first, SUBMIT);
                            } else {
                                ctx.cascadeAll(DOCS, List.of(second), SUBMIT, CascadePolicy.COLLECT);
                            }
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            assertThrows(RuntimeLogDebugTest.Fatal.class, () -> registry.of(DOCS).execute(single, DISCARD));
            assertThrows(RuntimeLogDebugTest.Fatal.class, () -> registry.of(DOCS).execute(collecting, DISCARD));
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus DISCARD",
                    "FINER call=A depth=0 resolved Doc/DocStatus#p1 DRAFT -DISCARD-> ARCHIVED kind=ACTION",
                    "FINER call=A depth=0 action Collect start mode=sync",
                    "FINER call=A depth=0 cascade Doc/DocStatus#c1 SUBMIT",
                    "FINER call=A depth=1 resolved Doc/DocStatus#c1 DRAFT -SUBMIT-> REVIEW kind=ACTION",
                    "FINER call=A depth=1 action Review start mode=sync",
                    "FINE call=B start Doc/DocStatus DISCARD",
                    "FINER call=B depth=0 resolved Doc/DocStatus#p2 DRAFT -DISCARD-> ARCHIVED kind=ACTION",
                    "FINER call=B depth=0 action Collect start mode=sync",
                    "FINER call=B depth=0 cascade Doc/DocStatus#c2 SUBMIT policy=COLLECT",
                    "FINER call=B depth=1 resolved Doc/DocStatus#c2 DRAFT -SUBMIT-> REVIEW kind=ACTION",
                    "FINER call=B depth=1 action Review start mode=sync"), lines(log));
        }
    }

    /** The watch names the owner its lookup found, and the reaction it runs is a call of its own. */
    @Test
    void aWatchTracesTheOwnerItFound() {
        Order order = new Order("o1", OrderStatus.PAID);
        new Item("i1", ItemStatus.PACKED, Optional.of(order));
        Item last = new Item("i2", ItemStatus.PACKING, Optional.of(order));
        try (MachineRegistry registry = orders(new Probe<>()); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            registry.of(ITEMS).execute(last, ItemTransition.PACK);
            assertEquals(List.of(
                    "FINE call=A start Item/ItemStatus PACK",
                    "FINER call=A depth=0 resolved Item/ItemStatus#i2 PACKING -PACK-> PACKED kind=ACTION",
                    "FINER call=A depth=0 action PackItem start mode=sync",
                    "FINER call=A depth=0 action PackItem OK elapsed=<us>",
                    "FINER call=A commit-check entities=1 hops=1",
                    "FINER call=A setter Item/ItemStatus#i2 PACKING -> PACKED entries=1",
                    "FINE call=A committed Item/ItemStatus#i2 PACKING -PACK-> PACKED depth=0",
                    "FINER call=A watch watcher=Order/OrderStatus moved=Item/ItemStatus#i2"
                            + " owner=Order/OrderStatus#o1",
                    "FINE call=B start Order/OrderStatus reevaluate trigger=A",
                    "FINER call=B depth=0 resolved Order/OrderStatus#o1 PAID -SHIP-> SHIPPED kind=ACTION",
                    "FINER call=B depth=0 guard AllItemsPacked PASS elapsed=<us>",
                    "FINER call=B depth=0 action ShipOrder start mode=sync",
                    "FINER call=B depth=0 action ShipOrder OK elapsed=<us>",
                    "FINER call=B commit-check entities=1 hops=1",
                    "FINER call=B setter Order/OrderStatus#o1 PAID -> SHIPPED entries=1",
                    "FINE call=B committed Order/OrderStatus#o1 PAID -SHIP-> SHIPPED depth=0",
                    "FINE call=B end code=APPLIED state=SHIPPED entity=Order/OrderStatus#o1 records=1 starts=0"
                            + " reactions=0 aborted=0 elapsed=<us>",
                    "FINE call=A end code=APPLIED state=PACKED entity=Item/ItemStatus#i2 records=1 starts=0"
                            + " reactions=1 aborted=0 elapsed=<us>"), lines(log));
        }
    }

    @Test
    void aWatchLookupThatThrowsIsTraced() {
        Doc doc = new Doc("1", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> watching = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(DRAFT, DOCS, (Doc owner) -> List.of(owner), (Doc moved) -> {
                    throw new IllegalStateException("no owner index");
                })
                    .when("always", (Doc owner, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> true)
                        .execute(SUBMIT)
                    .endWatch()
                .build();
        try (MachineRegistry registry = registry(watching); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            Outcome<Doc, DocStatus, DocTransition> outcome = registry.of(DOCS).execute(doc, SUBMIT);
            assertInstanceOf(Reaction.Aborted.class, outcome.reactions().get(0));
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINER call=A depth=0 resolved Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW kind=MOVE",
                    "FINER call=A commit-check entities=1 hops=1",
                    "FINER call=A setter Doc/DocStatus#1 DRAFT -> REVIEW entries=1",
                    "FINE call=A committed Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW depth=0",
                    "FINER call=A watch-lookup-threw watcher=Doc/DocStatus moved=Doc/DocStatus#1"
                            + " error=java.lang.IllegalStateException: no owner index",
                    "FINE call=A end code=APPLIED state=REVIEW entity=Doc/DocStatus#1 records=1 starts=0 reactions=1"
                            + " aborted=1 elapsed=<us>"), lines(log));
        }
    }

    /**
     * A lookup that finds an owner logs it only once the owner is taken as a target: an owner whose identity cannot be
     * hashed makes the lookup throw instead, and the lookup is logged once, as one that threw.
     */
    @Test
    void aWatchLookupWhoseOwnerCannotBeHashedIsTracedOnceAsOneThatThrew() {
        Doc doc = new Doc("1", DRAFT);
        Doc owner = new Doc("owner", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> watching = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(DRAFT, DOCS, (Doc watcher) -> List.of(doc), (Doc moved) -> Optional.of(owner))
                    .when("always", (Doc watcher, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> true)
                        .execute(SUBMIT)
                    .endWatch()
                .build();
        try (MachineRegistry registry = MachineRegistry.builder().add(StateMachines.from(watching)
                .state(Doc::status, Doc::setStatus)
                .identity((Doc target) -> target == owner ? new UnhashableId("owner") : target.id())).build();
             LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            Outcome<Doc, DocStatus, DocTransition> outcome = registry.of(DOCS).execute(doc, SUBMIT);
            assertInstanceOf(Reaction.Aborted.class, outcome.reactions().get(0));
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINER call=A depth=0 resolved Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW kind=MOVE",
                    "FINER call=A commit-check entities=1 hops=1",
                    "FINER call=A setter Doc/DocStatus#1 DRAFT -> REVIEW entries=1",
                    "FINE call=A committed Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW depth=0",
                    "FINER call=A watch-lookup-threw watcher=Doc/DocStatus moved=Doc/DocStatus#1"
                            + " error=java.lang.IllegalStateException: unhashable identity",
                    "FINE call=A end code=APPLIED state=REVIEW entity=Doc/DocStatus#1 records=1 starts=0 reactions=1"
                            + " aborted=1 elapsed=<us>"), lines(log));
        }
    }

    /**
     * The cost pin of TRACE: with the logger at DEBUG only, a call prints its identity for its committed and end
     * lines alone, so no TRACE line was built; at TRACE, the resolved and setter lines print it too.
     */
    @Test
    void withTheLoggerAtDebugOnlyNoTraceLineIsBuilt() {
        AtomicInteger printed = new AtomicInteger();
        Doc debugged = new Doc("1", DRAFT);
        Doc traced = new Doc("2", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> reviewed = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("Ready", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> GuardResult.pass())
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                                ActionOutcome.ok()));
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(reviewed).state(Doc::status, Doc::setStatus)
                        .identity((Doc target) -> new CountedId(target.id(), printed)))
                .build()) {
            try (LibraryLog log = LibraryLog.runtime(Level.FINE)) {
                registry.of(DOCS).execute(debugged, SUBMIT);
                assertEquals(List.of(
                        "FINE call=A start Doc/DocStatus SUBMIT",
                        "FINE call=A committed Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW depth=0",
                        "FINE call=A end code=APPLIED state=REVIEW entity=Doc/DocStatus#1 records=1 starts=0"
                                + " reactions=0 aborted=0 elapsed=<us>"), lines(log));
            }
            assertEquals(2, printed.get());
            try (LibraryLog log = LibraryLog.runtime(Level.FINER)) {
                registry.of(DOCS).execute(traced, SUBMIT);
                assertEquals(9, log.records().size());
            }
            assertEquals(6, printed.get());
        }
    }

    /**
     * A line names the constants of the state and transition enums by {@code name()}, whatever their
     * {@code toString()} prints.
     */
    @Test
    void aLineNamesEnumConstantsByTheirNames() {
        MachineKey<Lamp, Shade, Switch> lamps = MachineKey.of(Lamp.class, Shade.class, Switch.class);
        GraphDefinition<Lamp, Shade, Switch> graph = StateGraph.define(lamps).initial(Shade.DARK).terminal(Shade.LIT)
                .statePolicy(Shade.DARK, "a dark lamp is lit")
                .transition(Switch.LIGHT, Shade.DARK, Shade.LIT).endTransition()
                .build();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Lamp::shade, Lamp::setShade).identity((Lamp lamp) -> "l1"))
                .build(); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            registry.of(lamps).execute(new Lamp(), Switch.LIGHT);
            assertEquals(List.of(
                    "FINE call=A start Lamp/Shade LIGHT",
                    "FINER call=A depth=0 resolved Lamp/Shade#l1 DARK -LIGHT-> LIT kind=MOVE",
                    "FINER call=A commit-check entities=1 hops=1",
                    "FINER call=A setter Lamp/Shade#l1 DARK -> LIT entries=1",
                    "FINE call=A committed Lamp/Shade#l1 DARK -LIGHT-> LIT depth=0",
                    "FINE call=A end code=APPLIED state=LIT entity=Lamp/Shade#l1 records=1 starts=0 reactions=0"
                            + " aborted=0 elapsed=<us>"), lines(log));
        }
    }

    @Test
    void anIdentityWhoseToStringThrowsPrintsAMarkerInTraceLinesToo() {
        Doc doc = new Doc("1", DRAFT);
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(plain()).state(Doc::status, Doc::setStatus)
                        .identity((Doc target) -> new BrokenId(target.id())))
                .build();
             LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            assertEquals(OutcomeCode.APPLIED, registry.of(DOCS).execute(doc, SUBMIT).code());
            String marker = "Doc/DocStatus#<toString threw java.lang.IllegalStateException>";
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINER call=A depth=0 resolved " + marker + " DRAFT -SUBMIT-> REVIEW kind=MOVE",
                    "FINER call=A commit-check entities=1 hops=1",
                    "FINER call=A setter " + marker + " DRAFT -> REVIEW entries=1",
                    "FINE call=A committed " + marker + " DRAFT -SUBMIT-> REVIEW depth=0",
                    "FINE call=A end code=APPLIED state=REVIEW entity=" + marker + " records=1 starts=0 reactions=0"
                            + " aborted=0 elapsed=<us>"), lines(log));
        }
    }

    @Test
    void aBackendThatThrowsAtTraceChangesNothing() {
        Doc doc = new Doc("1", DRAFT);
        Handler failing = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.FINER) {
                    throw new IllegalStateException("disk full");
                }
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        Logger runtime = Logger.getLogger(CallLog.LOGGER_NAME);
        try (MachineRegistry registry = registry(plain()); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            runtime.addHandler(failing);
            try {
                assertEquals(OutcomeCode.APPLIED, registry.of(DOCS).execute(doc, SUBMIT).code());
            } finally {
                runtime.removeHandler(failing);
            }
            assertEquals(6, log.records().size());
            assertEquals(REVIEW, doc.status());
        }
    }
}
