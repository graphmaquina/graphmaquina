package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Billing.BOOKINGS;
import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.ARCHIVE;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SEND_BACK;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static io.github.graphmaquina.fixtures.Keys.ITEMS;
import static io.github.graphmaquina.fixtures.Keys.ORDERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.api.WatchCondition;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.error.ReentrantTransitionException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Billing;
import io.github.graphmaquina.fixtures.Billing.Booking;
import io.github.graphmaquina.fixtures.Billing.BookingStatus;
import io.github.graphmaquina.fixtures.Billing.BookingTransition;
import io.github.graphmaquina.fixtures.Billing.Payment;
import io.github.graphmaquina.fixtures.Billing.PaymentStatus;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The DEBUG lines of the runtime log, read through {@code java.util.logging} at {@code FINE}: every call that starts
 * logs {@code start}, one {@code committed} line per hop and one {@code started} line per start of a sub-machine it
 * committed, and then {@code end} with its outcome or {@code failed} with its failure, on the thread that runs it. A
 * cascade has no lines of its own, a watch reaction names the call that triggered it, an Error ends the lines of its
 * call without a {@code failed} one, and nothing at all is built while the logger is off.
 */
class RuntimeLogDebugTest {

    private static final Pattern CALL_ID = Pattern.compile("(call|trigger)=(\\d+)");
    private static final Pattern ELAPSED = Pattern.compile(" elapsed=\\d+us$");

    /** An Error an action, a hook or the backend throws, which no call contains. */
    static final class Fatal extends Error {
        Fatal(String message) {
            super(message);
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

    /**
     * Each record as {@code LEVEL message}, every call id replaced by a letter in order of first appearance, A for the
     * first, and the elapsed time that ends a line replaced by {@code <us>}. A DEBUG line never carries the id 0.
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
            lines.add(ELAPSED.matcher(line).replaceFirst(" elapsed=<us>"));
        }
        return lines;
    }

    /**
     * DRAFT -SUBMIT-> REVIEW, shaped by {@code submit}; DRAFT -DISCARD-> ARCHIVED, REVIEW -PUBLISH-> PUBLISHED and
     * PUBLISHED -ARCHIVE-> ARCHIVED are plain moves.
     */
    private static GraphDefinition<Doc, DocStatus, DocTransition> docs(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit) {
        return submit.apply(StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                        .statePolicy(DRAFT, "a draft is submitted or discarded")
                        .statePolicy(REVIEW, "a review is published")
                        .statePolicy(PUBLISHED, "a publication is archived")
                        .transition(SUBMIT, DRAFT, REVIEW))
                .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .build();
    }

    private static GraphDefinition<Doc, DocStatus, DocTransition> plain() {
        return docs(UnaryOperator.<TransitionBuilder<Doc, DocStatus, DocTransition>>identity());
    }

    /** {@link #plain()}, plus a watch on DRAFT that runs {@code rule} on a reevaluation and then executes SUBMIT. */
    private static GraphDefinition<Doc, DocStatus, DocTransition> watching(
            WatchCondition<Doc, Doc, DocStatus, DocTransition> rule) {
        return StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded")
                .statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(DRAFT, DOCS, (Doc doc) -> List.of(doc), (Doc moved) -> Optional.<Doc>empty())
                    .when("rule", rule).execute(SUBMIT)
                    .endWatch()
                .build();
    }

    private static MachineRegistry registry(GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        return registry(graph, Doc::setStatus);
    }

    private static MachineRegistry registry(GraphDefinition<Doc, DocStatus, DocTransition> graph,
                                            BiConsumer<Doc, DocStatus> setter) {
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Doc::status, setter).identity(Doc::id))
                .build();
    }

    /** Orders that watch their items from PAID and ship once every item is packed; {@code ship} ships them. */
    private static MachineRegistry orders(Probe<Order, OrderStatus, OrderTransition> ship,
                                          Probe<Order, OrderStatus, OrderTransition> cancel) {
        return MachineRegistry.builder()
                .add(StateMachines.from(OrderGraphs.order(
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                                        CompletableFuture.completedFuture(ActionOutcome.ok()),
                                ship, cancel,
                                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass(),
                                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass()))
                        .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5)))
                .add(StateMachines.from(OrderGraphs.item(
                                (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> ActionOutcome.ok()))
                        .state(Item::status, Item::setStatus).identity(Item::id))
                .build();
    }

    @Test
    void anAppliedCallLogsItsStartItsHopAndItsEnd() {
        Doc doc = new Doc("1", DRAFT);
        try (MachineRegistry registry = registry(plain()); LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            registry.of(DOCS).execute(doc, SUBMIT);
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINE call=A committed Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW depth=0",
                    "FINE call=A end code=APPLIED state=REVIEW entity=Doc/DocStatus#1 records=1 starts=0 reactions=0"
                            + " aborted=0 elapsed=<us>"), lines(log));
        }
    }

    @Test
    void aRejectedCallLogsItsReason() {
        Doc reviewed = new Doc("1", REVIEW);
        Doc draft = new Doc("2", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> guarded = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("Ready",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> GuardResult.reject("no reviewer")));
        try (MachineRegistry registry = registry(guarded); LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            registry.of(DOCS).execute(reviewed, SUBMIT);
            registry.of(DOCS).execute(draft, SUBMIT);
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINE call=A end code=NO_TRANSITION state=REVIEW entity=Doc/DocStatus#1 records=0 starts=0"
                            + " reactions=0 aborted=0 reason=\"a review is published\" elapsed=<us>",
                    "FINE call=B start Doc/DocStatus SUBMIT",
                    "FINE call=B end code=GUARD_REJECTED state=DRAFT entity=Doc/DocStatus#2 records=0 starts=0"
                            + " reactions=0 aborted=0 reason=\"no reviewer\" elapsed=<us>"), lines(log));
        }
    }

    @Test
    void aRoutedFailureLogsTheRecoveryHopAndItsReason() {
        Doc doc = new Doc("1", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> refusing = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) ->
                                ActionOutcome.failed("the reviewer is away"))
                        .onFailure(DISCARD));
        try (MachineRegistry registry = registry(refusing); LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            assertEquals(OutcomeCode.FAILURE_ROUTED, registry.of(DOCS).execute(doc, SUBMIT).code());
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINE call=A committed Doc/DocStatus#1 DRAFT -DISCARD-> ARCHIVED depth=0",
                    "FINE call=A end code=FAILURE_ROUTED state=ARCHIVED entity=Doc/DocStatus#1 records=1 starts=0"
                            + " reactions=0 aborted=0 reason=\"the reviewer is away\" elapsed=<us>"), lines(log));
        }
    }

    @Test
    void anAbortBeforeTheCommitLogsItsPhaseAndItsError() {
        Doc doc = new Doc("1", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> broken = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("Ready",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw new IllegalStateException("no reviewer list");
                        }));
        try (MachineRegistry registry = registry(broken); LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(doc, SUBMIT));
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINE call=A failed phase=GUARD written=NONE entity=Doc/DocStatus#1 error=" + error
                            + " elapsed=<us>"), lines(log));
        }
    }

    @Test
    void aCommitFailureLogsWhatTheSettersWrote() {
        Doc doc = new Doc("1", DRAFT);
        BiConsumer<Doc, DocStatus> failing = (Doc target, DocStatus status) -> {
            throw new IllegalStateException("the row is locked");
        };
        try (MachineRegistry registry = registry(plain(), failing); LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            CommitFailedException error = assertThrows(CommitFailedException.class,
                    () -> registry.of(DOCS).execute(doc, SUBMIT));
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINE call=A failed phase=COMMIT written=PARTIAL entity=Doc/DocStatus#1 error=" + error
                            + " elapsed=<us>"), lines(log));
        }
    }

    @Test
    void aHookFailureLogsTheCommittedHopsFirst() {
        Doc doc = new Doc("1", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> notifying = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.afterCommit("Notify",
                        (Doc target) -> {
                            throw new IllegalStateException("mail is down");
                        }));
        try (MachineRegistry registry = registry(notifying); LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            AfterCommitException error = assertThrows(AfterCommitException.class,
                    () -> registry.of(DOCS).execute(doc, SUBMIT));
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINE call=A committed Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW depth=0",
                    "FINE call=A failed phase=HOOK written=ALL entity=Doc/DocStatus#1 error=" + error
                            + " elapsed=<us>"), lines(log));
        }
    }

    /** D17: an Error is not a failure of the call; the lines logged before it stay, and nothing follows them. */
    @Test
    void anErrorEndsTheLinesOfItsCallWithoutAFailedLine() {
        Doc acting = new Doc("1", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> fatalAction = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Review",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw new Fatal("out of reviewers");
                        }));
        Doc hooked = new Doc("2", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> fatalHook = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.afterCommit("Notify",
                        (Doc target) -> {
                            throw new Fatal("out of mail");
                        }));
        try (MachineRegistry action = registry(fatalAction); MachineRegistry hook = registry(fatalHook);
             LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            assertThrows(Fatal.class, () -> action.of(DOCS).execute(acting, SUBMIT));
            assertThrows(Fatal.class, () -> hook.of(DOCS).execute(hooked, SUBMIT));
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINE call=B start Doc/DocStatus SUBMIT",
                    "FINE call=B committed Doc/DocStatus#2 DRAFT -SUBMIT-> REVIEW depth=0"), lines(log));
        }
    }

    @Test
    void aCascadeIsPartOfTheCallThatRunsIt() {
        Order order = new Order("o1", OrderStatus.NEW);
        new Item("i1", ItemStatus.PENDING, Optional.of(order));
        Probe<Order, OrderStatus, OrderTransition> cancel = new Probe<>();
        cancel.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
            ctx.cascade(ITEMS, ctx.entity().items().get(0), ItemTransition.CANCEL);
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = orders(new Probe<>(), cancel);
             LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            registry.of(ORDERS).execute(order, OrderTransition.CANCEL);
            assertEquals(List.of(
                    "FINE call=A start Order/OrderStatus CANCEL",
                    "FINE call=A committed Order/OrderStatus#o1 NEW -CANCEL-> CANCELLED depth=0",
                    "FINE call=A committed Item/ItemStatus#i1 PENDING -CANCEL-> CANCELLED depth=1",
                    "FINE call=A end code=APPLIED state=CANCELLED entity=Order/OrderStatus#o1 records=2 starts=0"
                            + " reactions=0 aborted=0 elapsed=<us>"), lines(log));
        }
    }

    @Test
    void aSubmachineStartIsLoggedAfterTheHopThatEnteredItsNode() {
        Payment deposit = new Payment("d1", PaymentStatus.CAPTURED);
        Booking booking = new Booking("b1", BookingStatus.REQUESTED).attachDeposit(deposit);
        try (MachineRegistry registry = new Billing().registry(); LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            registry.of(BOOKINGS).execute(booking, BookingTransition.BOOK);
            assertEquals(List.of(
                    "FINE call=A start Booking/BookingStatus BOOK",
                    "FINE call=A committed Booking/BookingStatus#b1 REQUESTED -BOOK-> DEPOSIT depth=0",
                    "FINE call=A started Payment/PaymentStatus#d1 CAPTURED -> AUTHORIZING"
                            + " parent=Booking/BookingStatus#b1 node=DEPOSIT depth=0",
                    "FINE call=A end code=APPLIED state=DEPOSIT entity=Booking/BookingStatus#b1 records=1 starts=1"
                            + " reactions=0 aborted=0 elapsed=<us>"), lines(log));
        }
    }

    @Test
    void aWatchReactionNamesTheCallWhoseCommitTriggeredIt() {
        Order order = new Order("o1", OrderStatus.PAID);
        new Item("i1", ItemStatus.PACKED, Optional.of(order));
        Item last = new Item("i2", ItemStatus.PACKING, Optional.of(order));
        Probe<Order, OrderStatus, OrderTransition> ship = new Probe<>();
        try (MachineRegistry registry = orders(ship, new Probe<>());
             LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            registry.of(ITEMS).execute(last, ItemTransition.PACK);
            ship.behaviour = (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> {
                throw new IllegalStateException("no carrier");
            };
            order.setStatus(OrderStatus.PAID);
            last.setStatus(ItemStatus.PACKING);
            Outcome<Item, ItemStatus, ItemTransition> aborted = registry.of(ITEMS).execute(last, ItemTransition.PACK);
            Reaction.Aborted reaction = assertInstanceOf(Reaction.Aborted.class, aborted.reactions().get(0));
            assertEquals(List.of(
                    "FINE call=A start Item/ItemStatus PACK",
                    "FINE call=A committed Item/ItemStatus#i2 PACKING -PACK-> PACKED depth=0",
                    "FINE call=B start Order/OrderStatus reevaluate trigger=A",
                    "FINE call=B committed Order/OrderStatus#o1 PAID -SHIP-> SHIPPED depth=0",
                    "FINE call=B end code=APPLIED state=SHIPPED entity=Order/OrderStatus#o1 records=1 starts=0"
                            + " reactions=0 aborted=0 elapsed=<us>",
                    "FINE call=A end code=APPLIED state=PACKED entity=Item/ItemStatus#i2 records=1 starts=0"
                            + " reactions=1 aborted=0 elapsed=<us>",
                    "FINE call=C start Item/ItemStatus PACK",
                    "FINE call=C committed Item/ItemStatus#i2 PACKING -PACK-> PACKED depth=0",
                    "FINE call=D start Order/OrderStatus reevaluate trigger=C",
                    "FINE call=D failed phase=ACTION written=NONE entity=Order/OrderStatus#o1 error="
                            + reaction.cause() + " elapsed=<us>",
                    "FINE call=C end code=APPLIED state=PACKED entity=Item/ItemStatus#i2 records=1 starts=0"
                            + " reactions=1 aborted=1 elapsed=<us>"), lines(log));
        }
    }

    /** Each reaction names the one before it, and a reaction past maxWatchChain fails before it reads its state. */
    @Test
    void aChainOfReactionsAndTheLinkItRefusesAreLogged() {
        GraphDefinition<Doc, DocStatus, DocTransition> pingPong = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(DRAFT, DOCS, (Doc d) -> List.of(d), (Doc d) -> Optional.of(d))
                    .when("always", (Doc d, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> true)
                        .execute(SUBMIT)
                    .endWatch()
                .watch(REVIEW, DOCS, (Doc d) -> List.of(d), (Doc d) -> Optional.of(d))
                    .when("always", (Doc d, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> true)
                        .execute(SEND_BACK)
                    .endWatch()
                .build();
        Doc doc = new Doc("d1", DRAFT);
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(pingPong).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .maxWatchChain(1)
                .build();
             LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            Outcome<Doc, DocStatus, DocTransition> root = registry.of(DOCS).execute(doc, SUBMIT);
            Reaction.Done first = assertInstanceOf(Reaction.Done.class, root.reactions().get(0));
            Reaction.Aborted refused = assertInstanceOf(Reaction.Aborted.class,
                    first.outcome().reactions().get(0));
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINE call=A committed Doc/DocStatus#d1 DRAFT -SUBMIT-> REVIEW depth=0",
                    "FINE call=B start Doc/DocStatus reevaluate trigger=A",
                    "FINE call=B committed Doc/DocStatus#d1 REVIEW -SEND_BACK-> DRAFT depth=0",
                    "FINE call=C start Doc/DocStatus reevaluate trigger=B",
                    "FINE call=C failed phase=RESOLVE written=NONE error=" + refused.cause() + " elapsed=<us>",
                    "FINE call=B end code=APPLIED state=DRAFT entity=Doc/DocStatus#d1 records=1 starts=0 reactions=1"
                            + " aborted=1 elapsed=<us>",
                    "FINE call=A end code=APPLIED state=REVIEW entity=Doc/DocStatus#d1 records=1 starts=0 reactions=1"
                            + " aborted=0 elapsed=<us>"), lines(log));
        }
    }

    @Test
    void aReevaluationTheApplicationMakesNamesNoTrigger() {
        Doc doc = new Doc("1", DRAFT);
        try (MachineRegistry registry = registry(watching(
                (Doc owner, Collection<? extends Doc> peers, PeerView<Doc, DocStatus, DocTransition> view) -> true));
             LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            registry.of(DOCS).reevaluate(doc);
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus reevaluate",
                    "FINE call=A committed Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW depth=0",
                    "FINE call=A end code=APPLIED state=REVIEW entity=Doc/DocStatus#1 records=1 starts=0 reactions=0"
                            + " aborted=0 elapsed=<us>"), lines(log));
        }
    }

    /** No edge ran, so the engine registered no reference for the entity: the end line names none. */
    @Test
    void anIgnoredReevaluationEndsWithoutAnEntity() {
        Doc unwatched = new Doc("1", REVIEW);
        Doc unmatched = new Doc("2", DRAFT);
        try (MachineRegistry registry = registry(watching(
                (Doc owner, Collection<? extends Doc> peers, PeerView<Doc, DocStatus, DocTransition> view) -> false));
             LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            registry.of(DOCS).reevaluate(unwatched);
            registry.of(DOCS).reevaluate(unmatched);
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus reevaluate",
                    "FINE call=A end code=NO_WATCH state=REVIEW records=0 starts=0 reactions=0 aborted=0"
                            + " elapsed=<us>",
                    "FINE call=B start Doc/DocStatus reevaluate",
                    "FINE call=B end code=NO_RULE_MATCHED state=DRAFT records=0 starts=0 reactions=0 aborted=0"
                            + " elapsed=<us>"), lines(log));
        }
    }

    @Test
    void aReevaluationThatFailsBeforeItsEdgeRunsLogsItsFailureWithoutAnEntity() {
        Doc doc = new Doc("1", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> broken = watching(
                (Doc owner, Collection<? extends Doc> peers, PeerView<Doc, DocStatus, DocTransition> view) -> {
                    throw new IllegalStateException("the rule broke");
                });
        try (MachineRegistry rules = registry(broken);
             MachineRegistry unreadable = MachineRegistry.builder()
                     .add(StateMachines.from(plain()).state((Doc target) -> {
                         throw new IllegalStateException("the store is offline");
                     }, Doc::setStatus).identity(Doc::id))
                     .build();
             LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            TransitionAbortedException ruleError = assertThrows(TransitionAbortedException.class,
                    () -> rules.of(DOCS).reevaluate(doc));
            TransitionAbortedException readError = assertThrows(TransitionAbortedException.class,
                    () -> unreadable.of(DOCS).reevaluate(doc));
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus reevaluate",
                    "FINE call=A failed phase=RESOLVE written=NONE error=" + ruleError + " elapsed=<us>",
                    "FINE call=B start Doc/DocStatus reevaluate",
                    "FINE call=B failed phase=RESOLVE written=NONE error=" + readError + " elapsed=<us>"),
                    lines(log));
        }
    }

    /** A standalone machine without identity(...) tells entities apart by reference, and prints that reference. */
    @Test
    void aStandaloneMachineLogsToo() {
        Doc doc = new Doc("1", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> machine =
                StateMachines.from(plain()).state(Doc::status, Doc::setStatus).build();
        String ref = "Doc/DocStatus#@" + Integer.toHexString(System.identityHashCode(doc));
        try (LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            machine.execute(doc, SUBMIT);
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINE call=A committed " + ref + " DRAFT -SUBMIT-> REVIEW depth=0",
                    "FINE call=A end code=APPLIED state=REVIEW entity=" + ref + " records=1 starts=0 reactions=0"
                            + " aborted=0 elapsed=<us>"), lines(log));
        }
    }

    /** D2: the submission logs nothing; the call logs on the executor's thread, which runs it. */
    @Test
    void anAsyncCallLogsOnTheThreadThatRunsIt() throws Exception {
        Doc doc = new Doc("1", DRAFT);
        try (ExecutorService executor = Executors.newSingleThreadExecutor();
             MachineRegistry registry = MachineRegistry.builder()
                     .add(StateMachines.from(plain()).state(Doc::status, Doc::setStatus).identity(Doc::id)
                             .asyncExecutor(executor))
                     .build();
             LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            long worker = executor.submit(() -> Thread.currentThread().threadId()).get();
            registry.of(DOCS).async().execute(doc, SUBMIT).join();
            assertNotEquals(Thread.currentThread().threadId(), worker);
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINE call=A committed Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW depth=0",
                    "FINE call=A end code=APPLIED state=REVIEW entity=Doc/DocStatus#1 records=1 starts=0 reactions=0"
                            + " aborted=0 elapsed=<us>"), lines(log));
            for (LogRecord record : log.records()) {
                assertEquals(worker, record.getLongThreadID());
            }
        }
    }

    @Test
    void anIdentityWhoseToStringThrowsPrintsAMarkerAndChangesNothing() {
        Doc doc = new Doc("1", DRAFT);
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(plain()).state(Doc::status, Doc::setStatus)
                        .identity((Doc target) -> new BrokenId(target.id())))
                .build();
             LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            assertEquals(OutcomeCode.APPLIED, registry.of(DOCS).execute(doc, SUBMIT).code());
            String marker = "<toString threw java.lang.IllegalStateException>";
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINE call=A committed " + marker + " DRAFT -SUBMIT-> REVIEW depth=0",
                    "FINE call=A end code=APPLIED state=REVIEW entity=" + marker + " records=1 starts=0 reactions=0"
                            + " aborted=0 elapsed=<us>"), lines(log));
        }
    }

    @Test
    void aBackendThatThrowsARuntimeExceptionChangesNothing() {
        Doc doc = new Doc("1", DRAFT);
        Handler failing = new Handler() {
            @Override
            public void publish(LogRecord record) {
                throw new IllegalStateException("disk full");
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        Logger runtime = Logger.getLogger(CallLog.LOGGER_NAME);
        try (MachineRegistry registry = registry(plain()); LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            runtime.addHandler(failing);
            try {
                assertEquals(OutcomeCode.APPLIED, registry.of(DOCS).execute(doc, SUBMIT).code());
            } finally {
                runtime.removeHandler(failing);
            }
            assertEquals(3, log.records().size());
            assertEquals(REVIEW, doc.status());
        }
    }

    @Test
    void aBackendErrorPropagates() {
        Doc doc = new Doc("1", DRAFT);
        Fatal broken = new Fatal("handler invariant");
        Handler failing = new Handler() {
            @Override
            public void publish(LogRecord record) {
                throw broken;
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        Logger runtime = Logger.getLogger(CallLog.LOGGER_NAME);
        try (MachineRegistry registry = registry(plain()); LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            runtime.addHandler(failing);
            try {
                assertSame(broken, assertThrows(Fatal.class, () -> registry.of(DOCS).execute(doc, SUBMIT)));
            } finally {
                runtime.removeHandler(failing);
            }
            assertEquals(List.of("FINE call=A start Doc/DocStatus SUBMIT"), lines(log));
            assertEquals(DRAFT, doc.status());
        }
    }

    /**
     * The start line is written inside the try that tells the observers of an Error: a backend that throws an Error
     * on that line ends the call, of execute as of reevaluate, and the observers, told the call started, are told it
     * failed with that very Error.
     */
    @Test
    void anErrorOfTheBackendOnTheStartLineReachesTheObservers() {
        Fatal broken = new Fatal("handler invariant");
        Handler failing = new Handler() {
            @Override
            public void publish(LogRecord record) {
                throw broken;
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        List<String> heard = new ArrayList<>();
        MachineObserver observer = new MachineObserver() {
            @Override
            public void onStarted(MachineCall call) {
                heard.add("started");
            }

            @Override
            public void onFailed(MachineCall call, CallFailure failure, Duration elapsed) {
                heard.add(failure.error() == broken ? "failed with the backend's Error" : "failed with another");
            }
        };
        Logger runtime = Logger.getLogger(CallLog.LOGGER_NAME);
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(plain()).state(Doc::status, Doc::setStatus).identity(Doc::id))
                .observer(observer)
                .build(); LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            runtime.addHandler(failing);
            try {
                assertSame(broken, assertThrows(Fatal.class,
                        () -> registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT)));
                assertSame(broken, assertThrows(Fatal.class, () -> registry.of(DOCS).reevaluate(new Doc("2", DRAFT))));
            } finally {
                runtime.removeHandler(failing);
            }
            assertEquals(List.of("FINE call=A start Doc/DocStatus SUBMIT",
                    "FINE call=B start Doc/DocStatus reevaluate"), lines(log));
        }
        assertEquals(List.of("started", "failed with the backend's Error", "started",
                "failed with the backend's Error"), heard);
    }

    /**
     * A backend Error on a line the call logs once it ran, its committed line or its end or failed line, ends the
     * call: the caller receives that very Error, and so do the observers, as their one end event, never the outcome or
     * the failure the call would have returned or thrown, with the phase the call reached and what it wrote. The
     * committed line comes once every setter returned, so an Error on it reports every state written.
     */
    @Test
    void anErrorOfTheBackendOnALaterLineReachesTheObservers() {
        GraphDefinition<Doc, DocStatus, DocTransition> rejecting = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("Ready",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> GuardResult.reject("not ready")));
        GraphDefinition<Doc, DocStatus, DocTransition> throwing = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Review",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw new IllegalStateException("reviewer unavailable");
                        }));
        Doc committed = new Doc("1", DRAFT);
        assertEquals(List.of("failed with the backend's Error in HOOK, ALL"), terminalEvents(" committed ",
                readable(plain()), (StateMachine<Doc, DocStatus, DocTransition> machine) ->
                        machine.execute(committed, SUBMIT)));
        assertEquals(REVIEW, committed.status());
        Doc applied = new Doc("2", DRAFT);
        assertEquals(List.of("failed with the backend's Error in REACTIONS, ALL"), terminalEvents(" end code=",
                readable(plain()), (StateMachine<Doc, DocStatus, DocTransition> machine) ->
                        machine.execute(applied, SUBMIT)));
        assertEquals(REVIEW, applied.status());
        assertEquals(List.of("failed with the backend's Error in GUARD, NONE"), terminalEvents(" end code=",
                readable(rejecting), (StateMachine<Doc, DocStatus, DocTransition> machine) ->
                        machine.execute(new Doc("3", DRAFT), SUBMIT)));
        assertEquals(List.of("failed with the backend's Error in RESOLVE, NONE"), terminalEvents(" end code=",
                readable(plain()), (StateMachine<Doc, DocStatus, DocTransition> machine) ->
                        machine.reevaluate(new Doc("4", DRAFT))));
        assertEquals(List.of("failed with the backend's Error in ACTION, NONE"), terminalEvents(" failed phase=",
                readable(throwing), (StateMachine<Doc, DocStatus, DocTransition> machine) ->
                        machine.execute(new Doc("5", DRAFT), SUBMIT)));
        assertEquals(List.of("failed with the backend's Error in RESOLVE, NONE"), terminalEvents(" failed phase=",
                StateMachines.from(plain())
                        .state((Doc doc) -> {
                            throw new IllegalStateException("unreadable");
                        }, Doc::setStatus)
                        .identity(Doc::id),
                (StateMachine<Doc, DocStatus, DocTransition> machine) -> machine.reevaluate(new Doc("6", DRAFT))));
    }

    /** A new builder of the documents machine over {@code graph}, which reads and writes the status. */
    private static StateMachines.Builder<Doc, DocStatus, DocTransition> readable(
            GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        return StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id);
    }

    /**
     * Runs {@code call} on {@code machine}, in a registry with an observer, while the backend throws an Error on every
     * line that contains {@code event}; asserts that the call throws that Error, and returns the end events the
     * observer heard.
     */
    private static List<String> terminalEvents(String event,
                                               StateMachines.Builder<Doc, DocStatus, DocTransition> machine,
                                               Function<StateMachine<Doc, DocStatus, DocTransition>, Object> call) {
        Fatal broken = new Fatal("handler invariant");
        Handler failing = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage().contains(event)) {
                    throw broken;
                }
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        List<String> heard = new ArrayList<>();
        MachineObserver observer = new MachineObserver() {
            @Override
            public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                heard.add("completed");
            }

            @Override
            public void onFailed(MachineCall call, CallFailure failure, Duration elapsed) {
                String with = failure.error() == broken ? "the backend's Error" : "another";
                heard.add("failed with " + with + " in " + failure.phase() + ", " + failure.statesWritten());
            }
        };
        Logger runtime = Logger.getLogger(CallLog.LOGGER_NAME);
        try (MachineRegistry registry = MachineRegistry.builder().add(machine).observer(observer).build();
             LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            runtime.addHandler(failing);
            try {
                assertSame(broken, assertThrows(Fatal.class, () -> call.apply(registry.of(DOCS))));
            } finally {
                runtime.removeHandler(failing);
            }
            List<LogRecord> records = log.records();
            assertTrue(records.get(records.size() - 1).getMessage().contains(event), records.toString());
        }
        return heard;
    }

    /**
     * A reaction names its trigger only when the call whose commit triggered it logged: a level raised while that
     * call runs reaches the reaction, a call of its own, whose start line names no trigger.
     */
    @Test
    void aReactionOfACallThatLoggedNothingNamesNoTrigger() {
        Order order = new Order("o1", OrderStatus.PAID);
        new Item("i1", ItemStatus.PACKED, Optional.of(order));
        Item last = new Item("i2", ItemStatus.PACKING, Optional.of(order));
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(OrderGraphs.order(
                                (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                                        CompletableFuture.completedFuture(ActionOutcome.ok()),
                                new Probe<>(), new Probe<>(),
                                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass(),
                                (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass()))
                        .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5)))
                .add(StateMachines.from(OrderGraphs.item((TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> {
                    Logger.getLogger(CallLog.LOGGER_NAME).setLevel(Level.FINE);
                    return ActionOutcome.ok();
                })).state(Item::status, Item::setStatus).identity(Item::id))
                .build(); LibraryLog log = LibraryLog.runtime(Level.INFO)) {
            registry.of(ITEMS).execute(last, ItemTransition.PACK);
            assertEquals(List.of(
                    "FINE call=A start Order/OrderStatus reevaluate",
                    "FINE call=A committed Order/OrderStatus#o1 PAID -SHIP-> SHIPPED depth=0",
                    "FINE call=A end code=APPLIED state=SHIPPED entity=Order/OrderStatus#o1 records=1 starts=0"
                            + " reactions=0 aborted=0 elapsed=<us>"), lines(log));
        }
    }

    /** The cost pin: with the logger off, no line is built, so the identity is never printed. */
    @Test
    void withTheLoggerOffNothingIsPrinted() {
        AtomicInteger printed = new AtomicInteger();
        Doc quiet = new Doc("1", DRAFT);
        Doc logged = new Doc("2", DRAFT);
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(plain()).state(Doc::status, Doc::setStatus)
                        .identity((Doc target) -> new CountedId(target.id(), printed)))
                .build()) {
            assertFalse(Logger.getLogger(CallLog.LOGGER_NAME).isLoggable(Level.FINE));
            assertFalse(Logger.getLogger(CallLog.LOGGER_NAME).isLoggable(Level.FINER));
            registry.of(DOCS).execute(quiet, SUBMIT);
            assertEquals(0, printed.get());
            try (LibraryLog log = LibraryLog.runtime(Level.FINE)) {
                registry.of(DOCS).execute(logged, SUBMIT);
                assertEquals(3, log.records().size());
            }
            assertEquals(2, printed.get());
        }
    }

    /** D16: the levels are read when a call starts, so raising one during a call reaches the next call only. */
    @Test
    void aLevelRaisedDuringACallReachesTheNextCall() {
        Doc doc = new Doc("1", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> raising = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Raise",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            Logger.getLogger(CallLog.LOGGER_NAME).setLevel(Level.FINE);
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(raising); LibraryLog log = LibraryLog.runtime(Level.INFO)) {
            registry.of(DOCS).execute(doc, SUBMIT);
            assertEquals(List.of(), lines(log));
            registry.of(DOCS).execute(doc, PUBLISH);
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus PUBLISH",
                    "FINE call=A committed Doc/DocStatus#1 REVIEW -PUBLISH-> PUBLISHED depth=0",
                    "FINE call=A end code=APPLIED state=PUBLISHED entity=Doc/DocStatus#1 records=1 starts=0"
                            + " reactions=0 aborted=0 elapsed=<us>"), lines(log));
        }
    }

    /** A request refused before it starts, as observers see it, logs nothing either. */
    @Test
    void aRequestRefusedBeforeItStartsLogsNothing() {
        Doc doc = new Doc("1", DRAFT);
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> machine = new AtomicReference<>();
        List<Class<?>> refused = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> reentrant = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Again",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            refused.add(assertThrows(ReentrantTransitionException.class,
                                    () -> machine.get().execute(ctx.entity(), PUBLISH)).getClass());
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(reentrant); LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            machine.set(registry.of(DOCS));
            assertThrows(NullPointerException.class, () -> machine.get().execute(null, SUBMIT));
            machine.get().execute(doc, SUBMIT);
            assertEquals(List.of(ReentrantTransitionException.class), refused);
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINE call=A committed Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW depth=0",
                    "FINE call=A end code=APPLIED state=REVIEW entity=Doc/DocStatus#1 records=1 starts=0 reactions=0"
                            + " aborted=0 elapsed=<us>"), lines(log));
        }
    }

    /**
     * The observers hear of a call before its first line and after its last: onStarted before the start line, so the
     * clock the observers measure from is read just before onStarted, and the end event after the end or failed line,
     * so a backend Error on that line is the end event they hear.
     */
    @Test
    void theObserversHearOfACallBeforeItsFirstLineAndAfterItsLast() {
        Doc applied = new Doc("1", DRAFT);
        Doc failing = new Doc("2", DRAFT);
        BiConsumer<Doc, DocStatus> setter = (Doc target, DocStatus status) -> {
            if (target == failing) {
                throw new IllegalStateException("the row is locked");
            }
            target.setStatus(status);
        };
        try (LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            List<Integer> seen = new ArrayList<>();
            MachineObserver observer = new MachineObserver() {
                @Override
                public void onStarted(MachineCall call) {
                    seen.add(log.records().size());
                }

                @Override
                public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                    seen.add(log.records().size());
                }

                @Override
                public void onFailed(MachineCall call, CallFailure failure, Duration elapsed) {
                    seen.add(log.records().size());
                }
            };
            try (MachineRegistry registry = MachineRegistry.builder()
                    .add(StateMachines.from(plain()).state(Doc::status, setter).identity(Doc::id))
                    .observer(observer)
                    .build()) {
                registry.of(DOCS).execute(applied, SUBMIT);
                assertThrows(CommitFailedException.class, () -> registry.of(DOCS).execute(failing, SUBMIT));
            }
            assertEquals(List.of(0, 3, 3, 5), seen);
            assertEquals(5, log.records().size());
        }
    }
}
