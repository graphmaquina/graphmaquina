package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Billing.BOOKINGS;
import static io.github.graphmaquina.fixtures.Billing.PAYMENTS;
import static io.github.graphmaquina.fixtures.DocGraphs.docs;
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
import static io.github.graphmaquina.fixtures.TrailFixtures.hopOf;
import static io.github.graphmaquina.fixtures.TrailFixtures.recording;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.FailureKind;
import io.github.graphmaquina.api.FlowEntry;
import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.error.WatchChainExceededException;
import io.github.graphmaquina.fixtures.Billing;
import io.github.graphmaquina.fixtures.Billing.Booking;
import io.github.graphmaquina.fixtures.Billing.BookingStatus;
import io.github.graphmaquina.fixtures.Billing.BookingTransition;
import io.github.graphmaquina.fixtures.Billing.Payment;
import io.github.graphmaquina.fixtures.Billing.PaymentStatus;
import io.github.graphmaquina.fixtures.Billing.PaymentTransition;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.Item;
import io.github.graphmaquina.fixtures.ItemStatus;
import io.github.graphmaquina.fixtures.ItemTransition;
import io.github.graphmaquina.fixtures.Order;
import io.github.graphmaquina.fixtures.OrderGraphs;
import io.github.graphmaquina.fixtures.OrderStatus;
import io.github.graphmaquina.fixtures.OrderTransition;
import io.github.graphmaquina.fixtures.TickingClock;
import io.github.graphmaquina.internal.FrozenFlow;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Spec R8, R9.1, R9.2, R12.2, R15 and R16: the trail of a call. A machine built with {@code recordTrail} records the
 * trail of every root call it starts: a {@code Hop} per hop and a {@code Start} per sub-machine start, each at the
 * instant its clock read right after the entry was logged. The root machine decides for the whole call, and each
 * watch reaction, a root of its own, is decided by its own machine. A failure route leaves its {@code Recovery} on
 * its recovery hop, and steps and hooks read the trail so far. How the trail ends when the call is rejected or fails
 * is read in {@link TrailFailureTest}.
 */
class TrailTest {

    private static final FlowKey<String> BATCH = FlowKey.of("batch", String.class);

    /** A failure kind of the fixture, named by a returned failure. */
    record Declined() implements FailureKind { }

    /**
     * A payment captured in its booking's DEPOSIT runs the booking's exit, whose hop enters BALANCE and starts the
     * balance payment. The payment machine records its trail and the booking machine does not, but the root decides:
     * the trail lists the payment's hop, the booking's exit and the start, each at the instant of its own clock read,
     * wrapping the very records and starts of the outcome. The flow holds the root entity and no value; any thread
     * reads it.
     */
    @Test
    void aRecordingRootListsEveryHopAndStartAtItsMark() throws InterruptedException {
        Billing billing = new Billing();
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Payment balance = new Payment("p2", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit).attachBalance(balance);
        TickingClock clock = new TickingClock();
        Outcome<Payment, PaymentStatus, PaymentTransition> outcome;
        try (MachineRegistry registry = MachineRegistry.builder().add(billing.bookingMachine())
                .add(billing.paymentMachine().recordTrail(clock)).build()) {
            outcome = registry.of(PAYMENTS).execute(deposit, PaymentTransition.CAPTURE);
        }
        assertEquals(BookingStatus.BALANCE, booking.status());
        List<FlowEntry> trail = outcome.flow().trail();
        assertEquals(List.of(
                new FlowEntry.Hop(outcome.records().get(0), Optional.empty(), TickingClock.at(1)),
                new FlowEntry.Hop(outcome.records().get(1), Optional.empty(), TickingClock.at(2)),
                new FlowEntry.Start(outcome.starts().get(0), TickingClock.at(3))), trail);
        assertSame(outcome.records().get(0), hopOf(trail.get(0)));
        assertSame(outcome.records().get(1), hopOf(trail.get(1)));
        assertSame(outcome.starts().get(0), assertInstanceOf(FlowEntry.Start.class, trail.get(2)).start());
        assertEquals(BOOKINGS, outcome.records().get(1).key());
        assertSame(balance, outcome.starts().get(0).child());
        assertEquals(3, clock.reads());
        assertSame(deposit, outcome.flow().entity().orElseThrow());
        assertEquals(Set.of(), outcome.flow().keys());
        List<Object> read = new ArrayList<>();
        Thread other = new Thread(() -> read.add(outcome.flow().trail()));
        other.start();
        other.join();
        assertEquals(List.of(trail), read);
    }

    /**
     * Spec R9.2 the other way round: the booking machine records its trail, but the payment, the root, does not, so
     * the call records nothing and its clock is never read. A call that wrote nothing hands out the empty view; one
     * whose action wrote hands out its values and an empty trail.
     */
    @Test
    void aRootThatDoesNotRecordRecordsNothingWhateverItsPeersDo() {
        Billing billing = new Billing();
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Payment balance = new Payment("p2", PaymentStatus.AUTHORIZING);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit).attachBalance(balance);
        Payment written = new Payment("d2", PaymentStatus.AUTHORIZING);
        new Booking("b2", BookingStatus.DEPOSIT).attachDeposit(written).attachBalance(new Payment("p3",
                PaymentStatus.AUTHORIZING));
        billing.paymentActions.behaviour = (TransitionContext<Payment, PaymentStatus, PaymentTransition> ctx) -> {
            if (ctx.entity() == written) {
                ctx.flow().put(BATCH, "b-2");
            }
            return ActionOutcome.ok();
        };
        TickingClock clock = new TickingClock();
        Outcome<Payment, PaymentStatus, PaymentTransition> quiet;
        Outcome<Payment, PaymentStatus, PaymentTransition> writing;
        try (MachineRegistry registry = MachineRegistry.builder().add(billing.bookingMachine().recordTrail(clock))
                .add(billing.paymentMachine()).build()) {
            quiet = registry.of(PAYMENTS).execute(deposit, PaymentTransition.CAPTURE);
            writing = registry.of(PAYMENTS).execute(written, PaymentTransition.CAPTURE);
        }
        assertEquals(BookingStatus.BALANCE, booking.status());
        assertEquals(2, quiet.records().size());
        assertSame(FlowView.empty(), quiet.flow());
        assertEquals(Optional.of("b-2"), writing.flow().get(BATCH));
        assertEquals(List.of(), writing.flow().trail());
        assertEquals(0, clock.reads());
    }

    /**
     * A reevaluation that selects no edge runs no step, so a machine that records its trail creates no flow for it:
     * its ignored outcome hands out the empty view, and the clock is never read.
     */
    @Test
    void anIgnoredReevaluationOfARecordingMachineHandsOutTheEmptyFlow() {
        TickingClock clock = new TickingClock();
        Outcome<Doc, DocStatus, DocTransition> ignored = recording(docs(UnaryOperator.identity()), clock).build()
                .reevaluate(new Doc("1", DRAFT));
        assertInstanceOf(Outcome.Ignored.class, ignored);
        assertSame(FlowView.empty(), ignored.flow());
        assertEquals(0, clock.reads());
    }

    /**
     * Spec D10: the mark of a hop is read once the hop is logged, after its guards and before its action, which reads
     * its own hop in the trail. A call that wrote nothing still hands out its flow when it records its trail: the root
     * entity and the trail, without values.
     */
    @Test
    void theMarkOfAHopIsReadAfterItsGuardsAndBeforeItsAction() {
        Doc doc = new Doc("1", DRAFT);
        TickingClock clock = new TickingClock();
        List<Object> seen = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("Ready", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            seen.add(clock.reads());
                            seen.add(ctx.flow().trail());
                            return GuardResult.pass();
                        })
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            seen.add(clock.reads());
                            seen.add(ctx.flow().trail());
                            return ActionOutcome.ok();
                        }));
        Outcome<Doc, DocStatus, DocTransition> outcome = recording(graph, clock).build().execute(doc, SUBMIT);
        List<FlowEntry> trail = List.of(new FlowEntry.Hop(outcome.records().get(0), Optional.empty(),
                TickingClock.at(1)));
        assertEquals(List.of(0, List.of(), 1, trail), seen);
        assertEquals(new FrozenFlow(Optional.of(doc), Map.of(), trail), outcome.flow());
        assertNotEquals(FlowView.empty(), outcome.flow());
    }

    /**
     * Spec R12.2 and R16.1: a failure route drops the failed hop, its entry and its mark, and leaves its
     * {@code Recovery} on the recovery hop: the identifier that failed and the failure as the outcome reports it, a
     * returned failure with its kind, a routed throwable with its cause. The recovery action reads its own hop with it.
     */
    @Test
    void aFailureRouteLeavesItsRecoveryOnTheRecoveryHop() {
        Doc returned = new Doc("1", DRAFT);
        Doc thrown = new Doc("2", DRAFT);
        IllegalStateException unavailable = new IllegalStateException("reviewer unavailable");
        TickingClock clock = new TickingClock();
        List<List<FlowEntry>> seen = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            if (ctx.entity() == thrown) {
                                throw unavailable;
                            }
                            return ActionOutcome.failed(Declined.class, "no reviewer");
                        })
                        .failsOn(IllegalStateException.class).onFailure(DISCARD),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.action("Archive",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            seen.add(ctx.flow().trail());
                            return ActionOutcome.ok();
                        }));
        Outcome<Doc, DocStatus, DocTransition> declined;
        Outcome<Doc, DocStatus, DocTransition> routed;
        try (MachineRegistry registry = MachineRegistry.builder().add(recording(graph, clock)).build()) {
            declined = registry.of(DOCS).execute(returned, SUBMIT);
            routed = registry.of(DOCS).execute(thrown, SUBMIT);
        }
        Outcome.Failed<Doc, DocStatus, DocTransition> first = declined.asFailed().orElseThrow();
        FlowEntry.Recovery kinded = new FlowEntry.Recovery(SUBMIT, "no reviewer", Optional.empty(),
                Optional.of(Declined.class));
        assertEquals(List.of(new FlowEntry.Hop(declined.records().get(0), Optional.of(kinded), TickingClock.at(2))),
                declined.flow().trail());
        assertSame(declined.records().get(0), hopOf(declined.flow().trail().get(0)));
        assertEquals(DISCARD, declined.records().get(0).transition());
        assertEquals(first.reason(), kinded.reason());
        Outcome.Failed<Doc, DocStatus, DocTransition> second = routed.asFailed().orElseThrow();
        FlowEntry.Recovery caused = new FlowEntry.Recovery(SUBMIT, "IllegalStateException: reviewer unavailable",
                Optional.of(unavailable), Optional.empty());
        assertEquals(List.of(new FlowEntry.Hop(routed.records().get(0), Optional.of(caused), TickingClock.at(4))),
                routed.flow().trail());
        assertEquals(second.reason(), caused.reason());
        assertEquals(List.of(declined.flow().trail(), routed.flow().trail()), seen);
        assertEquals(4, clock.reads());
    }

    /**
     * A throwable that a recovery rule's type routes leaves that rule's failure kind on the recovery hop, as the
     * outcome reports it, with the throwable as cause.
     */
    @Test
    void aThrowableARecoveryRuleRoutesLeavesTheRulesKindOnTheRecoveryHop() {
        Doc doc = new Doc("1", DRAFT);
        IllegalArgumentException refused = new IllegalArgumentException("no reviewer");
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw refused;
                        })
                        .recover(Declined.class, DISCARD, IllegalArgumentException.class));
        Outcome<Doc, DocStatus, DocTransition> outcome = recording(graph, new TickingClock()).build()
                .execute(doc, SUBMIT);
        Outcome.Failed<Doc, DocStatus, DocTransition> failed = outcome.asFailed().orElseThrow();
        assertEquals(Optional.of(Declined.class), failed.failureKind());
        FlowEntry.Recovery recovery = new FlowEntry.Recovery(SUBMIT, failed.reason(), Optional.of(refused),
                Optional.of(Declined.class));
        assertEquals(List.of(new FlowEntry.Hop(outcome.records().get(0), Optional.of(recovery), TickingClock.at(2))),
                outcome.flow().trail());
    }

    /**
     * The recovery hop holds its {@code Recovery} from the moment it is marked, before the sub-machine its target binds
     * starts: a booking that cannot pay in full falls back to BOOK, whose DEPOSIT starts the deposit payment, and a
     * booking without one aborts on that start. The recovery hop stays in the trail of the abort, with what it
     * recovered from.
     */
    @Test
    void aRecoveryHopWhoseSubmachineCannotStartKeepsItsRecovery() {
        Billing billing = new Billing();
        billing.bookingActions.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) ->
                ActionOutcome.failed("card declined");
        Booking booking = new Booking("b1", BookingStatus.REQUESTED)
                .attachBalance(new Payment("p1", PaymentStatus.AUTHORIZING));
        TransitionAbortedException aborted;
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(billing.bookingMachine().recordTrail(new TickingClock())).add(billing.paymentMachine()).build()) {
            aborted = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(BOOKINGS).execute(booking, BookingTransition.PAY_IN_FULL));
        }
        List<FlowEntry> trail = aborted.flow().trail();
        assertEquals(2, trail.size());
        FlowEntry.Hop hop = assertInstanceOf(FlowEntry.Hop.class, trail.get(0));
        assertEquals(BookingTransition.BOOK, hop.hop().transition());
        assertEquals(Optional.of(new FlowEntry.Recovery(BookingTransition.PAY_IN_FULL, "card declined",
                Optional.empty(), Optional.empty())), hop.recovery());
        assertInstanceOf(FlowEntry.Failed.class, trail.get(1));
    }

    /**
     * Spec R16.3: each watch reaction is a root call with a trail of its own, absent from the trail of the call that
     * triggered it. A reaction aborted past the limit of a watch chain fails before it runs an edge, and its failure
     * ends with {@code Failed} (spec D11).
     */
    @Test
    void eachReactionRecordsItsOwnTrail() {
        Doc doc = new Doc("1", DRAFT);
        TickingClock clock = new TickingClock();
        GraphDefinition<Doc, DocStatus, DocTransition> pingPong = StateGraph.define(DOCS)
                .initial(DRAFT).terminal(ARCHIVED).allowsCycle(REVIEW)
                .statePolicy(DRAFT, "x").statePolicy(REVIEW, "x").statePolicy(PUBLISHED, "x")
                .transition(SUBMIT, DRAFT, REVIEW).endTransition()
                .transition(SEND_BACK, REVIEW, DRAFT).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(DRAFT, DOCS, (Doc owner) -> List.of(owner), (Doc moved) -> Optional.of(moved))
                    .when("always", (Doc owner, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> true).execute(SUBMIT)
                    .endWatch()
                .watch(REVIEW, DOCS, (Doc owner) -> List.of(owner), (Doc moved) -> Optional.of(moved))
                    .when("always", (Doc owner, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> true).execute(SEND_BACK)
                    .endWatch()
                .build();
        Outcome<Doc, DocStatus, DocTransition> root;
        try (MachineRegistry registry = MachineRegistry.builder().add(recording(pingPong, clock)).maxWatchChain(1)
                .build()) {
            root = registry.of(DOCS).execute(doc, SUBMIT);
        }
        assertEquals(List.of(new FlowEntry.Hop(root.records().get(0), Optional.empty(), TickingClock.at(1))),
                root.flow().trail());
        Reaction.Done first = assertInstanceOf(Reaction.Done.class, root.reactions().get(0));
        assertEquals(List.of(new FlowEntry.Hop(first.outcome().records().get(0), Optional.empty(),
                TickingClock.at(2))), first.outcome().flow().trail());
        assertEquals(SEND_BACK, first.outcome().records().get(0).transition());
        Reaction.Aborted second = assertInstanceOf(Reaction.Aborted.class, first.outcome().reactions().get(0));
        WatchChainExceededException chain = assertInstanceOf(WatchChainExceededException.class, second.cause());
        assertEquals(new FrozenFlow(Optional.of(doc), Map.of(), List.of(new FlowEntry.Failed(
                new CallFailure(CallPhase.RESOLVE, StatesWritten.NONE, chain), TickingClock.at(3)))), chain.flow());
    }

    /**
     * Spec R9.2 for reactions: an item that the root call packs triggers its order's watch, and the reaction is
     * decided by the order machine alone. With only the order recording, the root records nothing and the reaction
     * lists its hop; with only the items recording, the other way round.
     */
    @Test
    void aReactionIsDecidedByItsOwnMachine() {
        TickingClock orders = new TickingClock();
        TickingClock items = new TickingClock();
        List<Outcome<Item, ItemStatus, ItemTransition>> outcomes = new ArrayList<>();
        for (boolean orderRecords : List.of(true, false)) {
            Order order = new Order("o1", OrderStatus.PAID);
            Item item = new Item("i1", ItemStatus.PACKING, Optional.of(order));
            order.add(item);
            StateMachines.Builder<Order, OrderStatus, OrderTransition> orderMachine = StateMachines.from(
                            OrderGraphs.order(
                                    (TransitionContext<Order, OrderStatus, OrderTransition> ctx) ->
                                            CompletableFuture.completedFuture(ActionOutcome.ok()),
                                    (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                                    (TransitionContext<Order, OrderStatus, OrderTransition> ctx) -> ActionOutcome.ok(),
                                    (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass(),
                                    (GuardContext<Order, OrderStatus, OrderTransition> ctx) -> GuardResult.pass()))
                    .state(Order::status, Order::setStatus).identity(Order::id).asyncTimeout(Duration.ofSeconds(5));
            StateMachines.Builder<Item, ItemStatus, ItemTransition> itemMachine = StateMachines.from(OrderGraphs.item(
                            (TransitionContext<Item, ItemStatus, ItemTransition> ctx) -> ActionOutcome.ok()))
                    .state(Item::status, Item::setStatus).identity(Item::id);
            if (orderRecords) {
                orderMachine.recordTrail(orders);
            } else {
                itemMachine.recordTrail(items);
            }
            try (MachineRegistry registry = MachineRegistry.builder().add(orderMachine).add(itemMachine).build()) {
                outcomes.add(registry.of(ITEMS).execute(item, ItemTransition.PACK));
            }
            assertEquals(OrderStatus.SHIPPED, order.status());
        }
        Outcome<Item, ItemStatus, ItemTransition> unrecorded = outcomes.get(0);
        assertSame(FlowView.empty(), unrecorded.flow());
        Reaction.Done shipped = assertInstanceOf(Reaction.Done.class, unrecorded.reactions().get(0));
        assertEquals(ORDERS, shipped.outcome().records().get(0).key());
        assertEquals(List.of(new FlowEntry.Hop(shipped.outcome().records().get(0), Optional.empty(),
                TickingClock.at(1))), shipped.outcome().flow().trail());
        Outcome<Item, ItemStatus, ItemTransition> recorded = outcomes.get(1);
        assertEquals(List.of(new FlowEntry.Hop(recorded.records().get(0), Optional.empty(), TickingClock.at(1))),
                recorded.flow().trail());
        Reaction.Done quiet = assertInstanceOf(Reaction.Done.class, recorded.reactions().get(0));
        assertSame(FlowView.empty(), quiet.outcome().flow());
        assertEquals(1, orders.reads());
        assertEquals(1, items.reads());
    }

    /**
     * Steps and hooks read the trail so far: a guard before its hop is logged, an action its own hop, a cascaded
     * child's action the parent's hop and its own, the parent again once the cascade returned, and a hook every hop
     * the call committed, as the outcome lists them. A step's trail does not follow later entries.
     */
    @Test
    void stepsAndHooksReadTheTrailSoFar() {
        Doc parent = new Doc("1", DRAFT);
        Doc child = new Doc("2", DRAFT);
        TickingClock clock = new TickingClock();
        List<List<FlowEntry>> seen = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            seen.add(ctx.flow().trail());
                            return ActionOutcome.ok();
                        }),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard
                        .guard("Open", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            seen.add(ctx.flow().trail());
                            return GuardResult.pass();
                        })
                        .action("Collect", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            seen.add(ctx.flow().trail());
                            ctx.cascade(DOCS, child, SUBMIT);
                            seen.add(ctx.flow().trail());
                            return ActionOutcome.ok();
                        })
                        .afterCommitWith("Persist", (Doc committed, CommitContext<DocStatus, DocTransition> commit) ->
                                seen.add(commit.flow().trail())));
        Outcome<Doc, DocStatus, DocTransition> outcome = recording(graph, clock).build().execute(parent, DISCARD);
        FlowEntry parentHop = new FlowEntry.Hop(outcome.records().get(0), Optional.empty(), TickingClock.at(1));
        FlowEntry childHop = new FlowEntry.Hop(outcome.records().get(1), Optional.empty(), TickingClock.at(2));
        assertEquals(List.of(List.of(), List.of(parentHop), List.of(parentHop, childHop), List.of(parentHop, childHop),
                List.of(parentHop, childHop)), seen);
        assertEquals(List.of(parentHop, childHop), outcome.flow().trail());
        assertEquals(1, seen.get(1).size());
        assertThrows(UnsupportedOperationException.class, () -> seen.get(1).add(childHop));
    }

    /**
     * Each hook reads the whole trail of its call, every hop and every start the call committed, whichever hop it runs
     * for: both hooks of a payment captured in its booking's DEPOSIT, the payment's and the exit's, read the payment's
     * hop, the booking's exit and the start of the balance.
     */
    @Test
    void everyHookReadsEveryHopAndStartTheCallCommitted() {
        Billing billing = new Billing();
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Payment balance = new Payment("p2", PaymentStatus.AUTHORIZING);
        new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit).attachBalance(balance);
        List<List<FlowEntry>> read = new ArrayList<>();
        billing.onCommit = (CommitContext<?, ?> context) -> read.add(context.flow().trail());
        Outcome<Payment, PaymentStatus, PaymentTransition> outcome;
        try (MachineRegistry registry = MachineRegistry.builder().add(billing.bookingMachine())
                .add(billing.paymentMachine().recordTrail(new TickingClock())).build()) {
            outcome = registry.of(PAYMENTS).execute(deposit, PaymentTransition.CAPTURE);
        }
        List<FlowEntry> trail = outcome.flow().trail();
        assertEquals(3, trail.size());
        assertSame(outcome.starts().get(0), assertInstanceOf(FlowEntry.Start.class, trail.get(2)).start());
        assertEquals(2, billing.hooks.size());
        assertEquals(List.of(trail, trail), read);
    }

    /**
     * Spec D6: outcomes compare their trails. Two calls alike, on a clock that always answers the same instant, return
     * equal outcomes with equal hash codes; on a clock that moves, their trails, and so the outcomes, differ.
     */
    @Test
    void outcomesCompareTheirTrails() {
        Doc doc = new Doc("1", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(UnaryOperator.identity());
        StateMachine<Doc, DocStatus, DocTransition> fixed =
                recording(graph, InstantSource.fixed(Instant.ofEpochSecond(7))).build();
        Outcome<Doc, DocStatus, DocTransition> first = fixed.execute(doc, SUBMIT);
        doc.setStatus(DRAFT);
        Outcome<Doc, DocStatus, DocTransition> second = fixed.execute(doc, SUBMIT);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(Instant.ofEpochSecond(7), first.flow().trail().get(0).at());
        StateMachine<Doc, DocStatus, DocTransition> ticking = recording(graph, new TickingClock()).build();
        doc.setStatus(DRAFT);
        Outcome<Doc, DocStatus, DocTransition> third = ticking.execute(doc, SUBMIT);
        doc.setStatus(DRAFT);
        Outcome<Doc, DocStatus, DocTransition> fourth = ticking.execute(doc, SUBMIT);
        assertNotEquals(third, fourth);
        assertEquals(third.records(), fourth.records());
    }

    /** {@code recordTrail()} reads the system clock: the instant of each entry lies within the call. */
    @Test
    void recordTrailWithoutAClockReadsTheSystemClock() {
        Doc doc = new Doc("1", DRAFT);
        StateMachine<Doc, DocStatus, DocTransition> machine = StateMachines.from(docs(UnaryOperator.identity()))
                .state(Doc::status, Doc::setStatus).recordTrail().build();
        Instant before = Instant.now();
        Outcome<Doc, DocStatus, DocTransition> outcome = machine.execute(doc, SUBMIT);
        Instant after = Instant.now();
        Instant at = outcome.flow().trail().get(0).at();
        assertTrue(!at.isBefore(before) && !at.isAfter(after), before + " " + at + " " + after);
    }

    /** A null clock is refused by name, before anything is configured. */
    @Test
    void recordTrailRefusesANullClock() {
        StateMachines.Builder<Doc, DocStatus, DocTransition> builder =
                StateMachines.from(docs(UnaryOperator.identity())).state(Doc::status, Doc::setStatus);
        assertEquals("clock", assertThrows(NullPointerException.class, () -> builder.recordTrail(null)).getMessage());
        Outcome<Doc, DocStatus, DocTransition> outcome = builder.build().execute(new Doc("1", DRAFT), SUBMIT);
        assertSame(FlowView.empty(), outcome.flow());
    }
}
