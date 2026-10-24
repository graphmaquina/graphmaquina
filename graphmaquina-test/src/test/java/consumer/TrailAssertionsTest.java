package consumer;

import static consumer.GraphAssertionErrors.assertFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.FlowEntry;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.RejectionKind;
import io.github.graphmaquina.error.AfterCommitException;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.TrailAssert;
import io.github.graphmaquina.test.TransitionRecordsAssert;
import io.github.graphmaquina.test.fixtures.Checkout;
import io.github.graphmaquina.test.fixtures.Checkout.Order;
import io.github.graphmaquina.test.fixtures.Checkout.OrderChange;
import io.github.graphmaquina.test.fixtures.Checkout.OrderState;
import io.github.graphmaquina.test.fixtures.Checkout.Payment;
import io.github.graphmaquina.test.fixtures.Checkout.PaymentState;
import io.github.graphmaquina.test.fixtures.Parcels;
import io.github.graphmaquina.test.fixtures.Parcels.Move;
import io.github.graphmaquina.test.fixtures.Parcels.Parcel;
import io.github.graphmaquina.test.fixtures.Parcels.Spot;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Contract of the trail view, over the trails real machines recorded on a clock the test controls: the parcel fixture
 * for hops, a guard rejection and an abort, and the checkout fixture for a sub-machine start and a hook that throws.
 * Order and multiplicity are pinned, a hop is named by its identifier alone or read as its record, from where to where,
 * the final entry is named by its kind or its phase, and equal instants keep the time order. Every mismatch is read
 * off the fields of the {@link GraphAssertionError}, its message too where the message is deterministic, and every
 * expectation gets its own null-subject case. The views opened from a flow, and the names they report, are pinned by
 * {@code FlowAssertionsTest}.
 */
class TrailAssertionsTest {

    private final Checkout checkout = new Checkout();

    // --- size --------------------------------------------------------------------------------------

    /** A call whose root machine records no trail has none, even when its flow holds values. */
    @Test
    void sizeAndEmptinessAreReadOffTheWholeTrail() {
        TrailAssert trail = TrailAssert.assertThat(shipping());
        assertSame(trail, trail.hasSize(1));
        assertFailure(assertThrows(GraphAssertionError.class, () -> trail.hasSize(2)),
                "trail", "size", 2, 1, "trail: size expected <2> but was <1>");
        assertFailure(assertThrows(GraphAssertionError.class, trail::isEmpty),
                "trail", "size", 0, 1, "trail: size expected <0> but was <1>");
        Outcome<Parcel, Spot, Move> untraced = Parcels.machine().execute(new Parcel("p3", Spot.PACKED), Move.SHIP);
        assertEquals(Optional.of("T-1"), untraced.flow().get(Parcels.TRACKING));
        TrailAssert none = TrailAssert.assertThat(untraced.flow().trail());
        assertSame(none, none.isEmpty());
        assertSame(none, none.hasSize(0));
    }

    // --- the class of every entry ------------------------------------------------------------------

    @Test
    void theClassOfEveryEntryIsComparedExactlyAndInOrder() {
        TrailAssert trail = TrailAssert.assertThat(checkingOut());
        assertSame(trail, trail.hasEntryTypesExactly(FlowEntry.Hop.class, FlowEntry.Start.class));
        List<Class<? extends FlowEntry>> taken = List.of(FlowEntry.Hop.class, FlowEntry.Start.class);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> trail.hasEntryTypesExactly(FlowEntry.Start.class, FlowEntry.Hop.class)),
                "trail", "entry types", List.of(FlowEntry.Start.class, FlowEntry.Hop.class), taken,
                "trail: entry types expected <[class io.github.graphmaquina.api.FlowEntry$Start, "
                        + "class io.github.graphmaquina.api.FlowEntry$Hop]> but was "
                        + "<[class io.github.graphmaquina.api.FlowEntry$Hop, "
                        + "class io.github.graphmaquina.api.FlowEntry$Start]>");
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> trail.hasEntryTypesExactly(FlowEntry.class, FlowEntry.class)),
                "trail", "entry types", List.of(FlowEntry.class, FlowEntry.class), taken);
        assertFailure(assertThrows(GraphAssertionError.class, () -> trail.hasEntryTypesExactly()),
                "trail", "entry types", List.of(), taken);
        TrailAssert none = TrailAssert.assertThat(List.of());
        assertSame(none, none.hasEntryTypesExactly());
    }

    // --- hops --------------------------------------------------------------------------------------

    /**
     * A start, a rejection and a failure are not hops, so no argument expects a trail without a hop; the hops of
     * several machines are named by their identifiers alone.
     */
    @Test
    void theHopsAreTheTransitionsOfTheHopEntriesInOrder() {
        TrailAssert trail = TrailAssert.assertThat(checkingOut());
        assertSame(trail, trail.hasSize(2).hasHopsExactly(OrderChange.CHECKOUT));
        assertFailure(assertThrows(GraphAssertionError.class, () -> trail.hasHopsExactly()),
                "trail", "hops", List.of(), List.of(OrderChange.CHECKOUT),
                "trail: hops expected <[]> but was <[CHECKOUT]>");
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> trail.hasHopsExactly(OrderChange.CHECKOUT, OrderChange.CHECKOUT)),
                "trail", "hops", List.of(OrderChange.CHECKOUT, OrderChange.CHECKOUT), List.of(OrderChange.CHECKOUT),
                "trail: hops expected <[CHECKOUT, CHECKOUT]> but was <[CHECKOUT]>");
        TrailAssert.assertThat(delivering())
                .hasSize(1)
                .hasHopsExactly();
        TrailAssert.assertThat(holding(Parcels.ticking()))
                .hasSize(2)
                .hasHopsExactly(Move.HOLD);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> TrailAssert.assertThat(shipping()).hasHopsExactly(Move.DELIVER)),
                "trail", "hops", List.of(Move.DELIVER), List.of(Move.SHIP),
                "trail: hops expected <[DELIVER]> but was <[SHIP]>");
        List<FlowEntry> twoMachines = new ArrayList<>(shipping());
        twoMachines.addAll(checkingOut());
        TrailAssert.assertThat(twoMachines).hasHopsExactly(Move.SHIP, OrderChange.CHECKOUT).hops().hasSize(2);
    }

    /**
     * The records of the hops say which machine and entity took each, from where to where, in an outcome's trail, where
     * they are the records the outcome lists, and in the trail of an abort, which has no records of its own; the
     * start, the rejection and the failure of a trail are not hops.
     */
    @Test
    void theHopsOpenAsTheRecordsOfTheHopEntries() {
        Order order = new Order("o3", new Payment("pay3", PaymentState.DECLINED));
        try (MachineRegistry registry = checkout.registry(Parcels.ticking())) {
            Outcome<Order, OrderState, OrderChange> checkedOut =
                    registry.of(Checkout.ORDERS).execute(order, OrderChange.CHECKOUT);
            TrailAssert.assertThat(checkedOut.flow().trail()).hasSize(2).hops()
                    .hasSize(1)
                    .containsExactly(checkedOut.records())
                    .forEntity(Checkout.ORDERS, order).single()
                    .from(OrderState.CART).hasTransition(OrderChange.CHECKOUT).to(OrderState.PAYING);
        }
        Parcel held = new Parcel("p2", Spot.SHIPPED);
        List<FlowEntry> aborted = assertThrows(CallFailedException.class,
                () -> Parcels.machine(Parcels.ticking()).execute(held, Move.HOLD)).flow().trail();
        TransitionRecordsAssert hops = TrailAssert.assertThat(aborted).hasSize(2).hops();
        hops.hasSize(1).forEntity(Parcels.PARCELS, held).single()
                .from(Spot.SHIPPED).hasTransition(Move.HOLD).to(Spot.HELD);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> hops.forEntity(Parcels.PARCELS, held).single().to(Spot.DELIVERED)),
                "trail.hops for Parcel/Spot[0]", "to", Spot.DELIVERED, Spot.HELD,
                "trail.hops for Parcel/Spot[0]: to expected <DELIVERED> but was <HELD>");
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> hops.forEntity(Parcels.PARCELS, held).single().from(Spot.PACKED)),
                "trail.hops for Parcel/Spot[0]", "from", Spot.PACKED, Spot.SHIPPED,
                "trail.hops for Parcel/Spot[0]: from expected <PACKED> but was <SHIPPED>");
        TrailAssert.assertThat(delivering()).hasSize(1).hops().isEmpty();
    }

    // --- time order --------------------------------------------------------------------------------

    @Test
    void equalInstantsKeepTheTimeOrder() {
        TrailAssert trail = TrailAssert.assertThat(holding(InstantSource.fixed(Instant.ofEpochSecond(7))));
        assertSame(trail, trail.hasSize(2).isInTimeOrder());
        TrailAssert.assertThat(shipping()).isInTimeOrder();
        TrailAssert.assertThat(List.of()).isInTimeOrder();
    }

    @Test
    void anInstantBeforeTheOneOfTheEntryAheadBreaksTheTimeOrder() {
        AtomicLong reads = new AtomicLong();
        TrailAssert trail = TrailAssert.assertThat(holding(() -> Instant.ofEpochSecond(100 - reads.incrementAndGet())))
                .hasSize(2);
        assertFailure(assertThrows(GraphAssertionError.class, trail::isInTimeOrder),
                "trail[1]", "at", "not before 1970-01-01T00:01:39Z", Instant.ofEpochSecond(98),
                "trail[1]: at expected <not before 1970-01-01T00:01:39Z> but was <1970-01-01T00:01:38Z>");
    }

    // --- the final entry ---------------------------------------------------------------------------

    @Test
    void aRejectedCallEndsWithItsRejectionOfThatKind() {
        List<FlowEntry> rejected = delivering();
        TrailAssert trail = TrailAssert.assertThat(rejected);
        assertSame(trail, trail.endsWithRejection(RejectionKind.GUARD));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> trail.endsWithRejection(RejectionKind.NO_TRANSITION)),
                "trail[0]", "kind", RejectionKind.NO_TRANSITION, RejectionKind.GUARD,
                "trail[0]: kind expected <NO_TRANSITION> but was <GUARD>");
        assertFailure(assertThrows(GraphAssertionError.class, () -> trail.endsWithFailure(CallPhase.GUARD)),
                "trail", "last entry", FlowEntry.Failed.class, rejected.get(0),
                "trail: last entry expected <" + FlowEntry.Failed.class + "> but was <" + rejected.get(0) + ">");
    }

    @Test
    void aFailedCallEndsWithItsFailureInThatPhase() {
        List<FlowEntry> aborted = holding(Parcels.ticking());
        TrailAssert trail = TrailAssert.assertThat(aborted);
        assertSame(trail, trail.endsWithFailure(CallPhase.ACTION));
        assertFailure(assertThrows(GraphAssertionError.class, () -> trail.endsWithFailure(CallPhase.COMMIT)),
                "trail[1]", "phase", CallPhase.COMMIT, CallPhase.ACTION,
                "trail[1]: phase expected <COMMIT> but was <ACTION>");
        assertFailure(assertThrows(GraphAssertionError.class, () -> trail.endsWithRejection(RejectionKind.GUARD)),
                "trail", "last entry", FlowEntry.Rejected.class, aborted.get(1));

        checkout.audit = (Order audited) -> {
            throw new IllegalStateException("audit down");
        };
        Order order = new Order("o2", new Payment("pay2", PaymentState.DECLINED));
        try (MachineRegistry registry = checkout.registry(Parcels.ticking())) {
            AfterCommitException failure = assertThrows(AfterCommitException.class,
                    () -> registry.of(Checkout.ORDERS).execute(order, OrderChange.CHECKOUT));
            TrailAssert.assertThat(failure.flow().trail())
                    .hasEntryTypesExactly(FlowEntry.Hop.class, FlowEntry.Start.class, FlowEntry.Failed.class)
                    .hasHopsExactly(OrderChange.CHECKOUT)
                    .isInTimeOrder()
                    .endsWithFailure(CallPhase.HOOK)
                    .hops().forEntity(Checkout.ORDERS, order).single()
                    .from(OrderState.CART).hasTransition(OrderChange.CHECKOUT).to(OrderState.PAYING);
        }
    }

    @Test
    void aTrailThatEndsWithAHopOrHasNoEntryEndsWithNeither() {
        List<FlowEntry> hops = shipping();
        TrailAssert trail = TrailAssert.assertThat(hops);
        assertFailure(assertThrows(GraphAssertionError.class, () -> trail.endsWithRejection(RejectionKind.GUARD)),
                "trail", "last entry", FlowEntry.Rejected.class, hops.get(0));
        assertFailure(assertThrows(GraphAssertionError.class, () -> trail.endsWithFailure(CallPhase.ACTION)),
                "trail", "last entry", FlowEntry.Failed.class, hops.get(0));
        TrailAssert empty = TrailAssert.assertThat(List.of());
        assertFailure(assertThrows(GraphAssertionError.class, () -> empty.endsWithRejection(RejectionKind.GUARD)),
                "trail", "last entry", FlowEntry.Rejected.class, "none",
                "trail: last entry expected <" + FlowEntry.Rejected.class + "> but was <none>");
        assertFailure(assertThrows(GraphAssertionError.class, () -> empty.endsWithFailure(CallPhase.ACTION)),
                "trail", "last entry", FlowEntry.Failed.class, "none");
    }

    // --- the list the view was given ---------------------------------------------------------------

    @Test
    void theViewCopiesTheListItWasGiven() {
        List<FlowEntry> mutable = new ArrayList<>(shipping());
        TrailAssert trail = TrailAssert.assertThat(mutable);
        mutable.clear();
        assertSame(trail, trail.hasSize(1).hasHopsExactly(Move.SHIP));
        assertFailure(assertThrows(GraphAssertionError.class, trail::isEmpty), "trail", "size", 0, 1);
    }

    @Test
    void aNullElementIsRefusedWhenTheViewIsBuilt() {
        List<FlowEntry> withNull = Arrays.asList(shipping().get(0), null);
        assertThrows(NullPointerException.class, () -> TrailAssert.assertThat(withNull));
    }

    // --- arguments ---------------------------------------------------------------------------------

    @Test
    void aNullArgumentIsAnArgumentErrorEvenWhenTheSubjectIsNull() {
        Class<? extends FlowEntry>[] noTypes = null;
        for (TrailAssert view : List.of(TrailAssert.assertThat(shipping()), TrailAssert.assertThat(null))) {
            assertEquals("types is marked non-null but is null", assertThrows(NullPointerException.class,
                    () -> view.hasEntryTypesExactly(noTypes)).getMessage());
            assertThrows(NullPointerException.class, () -> view.hasEntryTypesExactly(FlowEntry.Hop.class, null));
            assertEquals("transitions is marked non-null but is null", assertThrows(NullPointerException.class,
                    () -> view.hasHopsExactly((Enum<?>[]) null)).getMessage());
            assertThrows(NullPointerException.class, () -> view.hasHopsExactly(Move.SHIP, null));
            assertEquals("kind is marked non-null but is null", assertThrows(NullPointerException.class,
                    () -> view.endsWithRejection(null)).getMessage());
            assertEquals("phase is marked non-null but is null", assertThrows(NullPointerException.class,
                    () -> view.endsWithFailure(null)).getMessage());
        }
    }

    // --- one null-subject case per expectation method -----------------------------------------------

    @Test
    void nullSubjectFailsHasSize() {
        assertFailure(assertThrows(GraphAssertionError.class, () -> TrailAssert.assertThat(null).hasSize(1)),
                "trail", "size", 1, null);
    }

    @Test
    void nullSubjectFailsIsEmpty() {
        assertFailure(assertThrows(GraphAssertionError.class, () -> TrailAssert.assertThat(null).isEmpty()),
                "trail", "size", 0, null);
    }

    @Test
    void nullSubjectFailsHasEntryTypesExactly() {
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> TrailAssert.assertThat(null).hasEntryTypesExactly(FlowEntry.Hop.class)),
                "trail", "entry types", List.of(FlowEntry.Hop.class), null);
    }

    @Test
    void nullSubjectFailsHasHopsExactly() {
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> TrailAssert.assertThat(null).hasHopsExactly(Move.SHIP)),
                "trail", "hops", List.of(Move.SHIP), null);
    }

    @Test
    void nullSubjectFailsIsInTimeOrder() {
        assertFailure(assertThrows(GraphAssertionError.class, () -> TrailAssert.assertThat(null).isInTimeOrder()),
                "trail", "at", "never decreasing", null);
    }

    @Test
    void nullSubjectFailsEndsWithRejection() {
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> TrailAssert.assertThat(null).endsWithRejection(RejectionKind.GUARD)),
                "trail", "last entry", FlowEntry.Rejected.class, null);
    }

    @Test
    void nullSubjectFailsHops() {
        assertFailure(assertThrows(GraphAssertionError.class, () -> TrailAssert.assertThat(null).hops().hasSize(0)),
                "trail.hops", "size", 0, null);
    }

    @Test
    void nullSubjectFailsEndsWithFailure() {
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> TrailAssert.assertThat(null).endsWithFailure(CallPhase.ACTION)),
                "trail", "last entry", FlowEntry.Failed.class, null);
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** The trail of a new parcel shipped on a ticking clock: one hop, {@code SHIP}, one second after the epoch. */
    private static List<FlowEntry> shipping() {
        return Parcels.machine(Parcels.ticking()).execute(new Parcel("p1", Spot.PACKED), Move.SHIP).flow().trail();
    }

    /** The trail of a shipped parcel whose {@code DELIVER} its guard rejects, on a ticking clock: the rejection. */
    private static List<FlowEntry> delivering() {
        return Parcels.machine(Parcels.ticking()).execute(new Parcel("p2", Spot.SHIPPED), Move.DELIVER).flow().trail();
    }

    /** The trail of a shipped parcel whose {@code HOLD} aborts: its hop, then the failure, both on {@code clock}. */
    private static List<FlowEntry> holding(InstantSource clock) {
        Parcel shipped = new Parcel("p2", Spot.SHIPPED);
        return assertThrows(CallFailedException.class, () -> Parcels.machine(clock).execute(shipped, Move.HOLD))
                .flow().trail();
    }

    /** The trail of a new order checked out on a ticking clock: its hop, then the start of its payment. */
    private List<FlowEntry> checkingOut() {
        Order order = new Order("o1", new Payment("pay1", PaymentState.DECLINED));
        try (MachineRegistry registry = checkout.registry(Parcels.ticking())) {
            return registry.of(Checkout.ORDERS).execute(order, OrderChange.CHECKOUT).flow().trail();
        }
    }
}
