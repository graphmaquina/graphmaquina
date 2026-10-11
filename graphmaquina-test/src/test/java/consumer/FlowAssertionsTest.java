package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.CommitContext;
import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.HookRef;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.Reaction;
import io.github.graphmaquina.api.TransitionKind;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.runtime.MachineRegistry;
import io.github.graphmaquina.test.FlowAssert;
import io.github.graphmaquina.test.GraphAssertionError;
import io.github.graphmaquina.test.GraphAssertions;
import io.github.graphmaquina.test.OutcomeAssert;
import io.github.graphmaquina.test.ReactionsAssert;
import io.github.graphmaquina.test.TransitionFailureAssert;
import io.github.graphmaquina.test.fixtures.Checkout;
import io.github.graphmaquina.test.fixtures.Checkout.Order;
import io.github.graphmaquina.test.fixtures.Checkout.OrderChange;
import io.github.graphmaquina.test.fixtures.Checkout.Payment;
import io.github.graphmaquina.test.fixtures.Checkout.PaymentState;
import io.github.graphmaquina.test.fixtures.Parcels;
import io.github.graphmaquina.test.fixtures.Parcels.Move;
import io.github.graphmaquina.test.fixtures.Parcels.Parcel;
import io.github.graphmaquina.test.fixtures.Parcels.Spot;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Contract of the flow view, over calls real machines ran on the parcel and checkout fixtures. The entity is compared
 * by reference and a value by equality, a key that holds no value is told apart from one that holds another value, and
 * the keys keep the order of the writes that set them. The view opens from an outcome view, from a call-failure view,
 * from the outcome of a watch reaction and on its own, each naming where it came from, and the trail opens from it
 * under that name. Every mismatch is read off the fields of the {@link GraphAssertionError}, its message too where the
 * message is deterministic, and every expectation gets its own null-subject case.
 */
class FlowAssertionsTest {

    private final Parcel parcel = new Parcel("p1", Spot.PACKED);

    // --- what a call leaves in its flow ------------------------------------------------------------

    @Test
    void theFlowOfAnOutcomeHoldsTheRootEntityAndTheValuesItsStepsPut() {
        FlowAssert flow = OutcomeAssert.assertThat(Parcels.machine().execute(parcel, Move.SHIP)).isApplied().flow();
        assertSame(flow, flow.hasEntitySameAs(parcel));
        assertSame(flow, flow.hasValue(Parcels.TRACKING, "T-1"));
        assertSame(flow, flow.hasValue(Parcels.WEIGHT, 3));
        assertSame(flow, flow.hasNoValue(Parcels.NOTE));
        assertSame(flow, flow.hasKeysExactly(Parcels.TRACKING, Parcels.WEIGHT));
    }

    /** Two distinct parcels that print alike: the failure names each by its class and identity. */
    @Test
    void theEntityIsComparedByReference() {
        Parcel twin = new Parcel("p1", Spot.SHIPPED);
        assertEquals(parcel, twin);
        assertNotSame(parcel, twin);
        FlowAssert flow = OutcomeAssert.assertThat(Parcels.machine().execute(parcel, Move.SHIP)).flow();
        assertFailure(assertThrows(GraphAssertionError.class, () -> flow.hasEntitySameAs(twin)),
                "outcome.flow", "entity", twin, parcel,
                "outcome.flow: entity expected <Parcel#p1> but was <Parcel#p1> (different instances: expected "
                        + instance(twin) + ", actual " + instance(parcel) + ")");
    }

    /** A call that wrote nothing and records no trail hands out the empty flow, which holds no entity. */
    @Test
    void theEmptyFlowHoldsNoEntityAndNoKey() {
        Parcel shipped = new Parcel("p2", Spot.SHIPPED);
        FlowAssert flow =
                OutcomeAssert.assertThat(Parcels.machine().execute(shipped, Move.DELIVER)).isRejected().flow();
        assertSame(flow, flow.hasKeysExactly());
        assertFailure(assertThrows(GraphAssertionError.class, () -> flow.hasEntitySameAs(shipped)),
                "rejected outcome.flow", "entity", shipped, "none",
                "rejected outcome.flow: entity expected <Parcel#p2> but was <none>");
    }

    @Test
    void aValueIsComparedByEqualityAndReadThroughAnyEqualKey() {
        Outcome<Parcel, Spot, Move> shipped = Parcels.machine().execute(parcel, Move.SHIP);
        String equal = String.join("-", "T", "1");
        assertNotSame(shipped.flow().get(Parcels.TRACKING).orElseThrow(), equal);
        OutcomeAssert.assertThat(shipped).flow()
                .hasValue(Parcels.TRACKING, equal)
                .hasValue(FlowKey.visible("tracking", String.class), "T-1");
    }

    @Test
    void aKeyWithoutAValueIsToldApartFromAKeyWithAnotherValue() {
        FlowAssert flow = OutcomeAssert.assertThat(Parcels.machine().execute(parcel, Move.SHIP)).flow();
        assertFailure(assertThrows(GraphAssertionError.class, () -> flow.hasValue(Parcels.TRACKING, "T-2")),
                "outcome.flow", "value of tracking:String", Optional.of("T-2"), Optional.of("T-1"),
                "outcome.flow: value of tracking:String expected <Optional[T-2]> but was <Optional[T-1]>");
        assertFailure(assertThrows(GraphAssertionError.class, () -> flow.hasValue(Parcels.NOTE, "customs")),
                "outcome.flow", "value of note:String", Optional.of("customs"), Optional.empty(),
                "outcome.flow: value of note:String expected <Optional[customs]> but was <Optional.empty>");
    }

    @Test
    void aKeyThatHoldsAValueFailsHasNoValue() {
        FlowAssert flow = OutcomeAssert.assertThat(Parcels.machine().execute(parcel, Move.SHIP)).flow();
        assertFailure(assertThrows(GraphAssertionError.class, () -> flow.hasNoValue(Parcels.TRACKING)),
                "outcome.flow", "value of tracking:String", Optional.empty(), Optional.of("T-1"),
                "outcome.flow: value of tracking:String expected <Optional.empty> but was <Optional[T-1]>");
    }

    /** The keys are those of {@code keys()}, in the order of the writes that set them; none expects an empty flow. */
    @Test
    void theKeysAreComparedInTheOrderOfTheirWrites() {
        FlowAssert flow = OutcomeAssert.assertThat(Parcels.machine().execute(parcel, Move.SHIP)).flow();
        List<FlowKey<?>> written = List.of(Parcels.TRACKING, Parcels.WEIGHT);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> flow.hasKeysExactly(Parcels.WEIGHT, Parcels.TRACKING)),
                "outcome.flow", "keys", List.of(Parcels.WEIGHT, Parcels.TRACKING), written,
                "outcome.flow: keys expected <[weight:Integer, tracking:String]> but was "
                        + "<[tracking:String, weight:Integer]>");
        assertFailure(assertThrows(GraphAssertionError.class, () -> flow.hasKeysExactly(Parcels.TRACKING)),
                "outcome.flow", "keys", List.of(Parcels.TRACKING), written,
                "outcome.flow: keys expected <[tracking:String]> but was <[tracking:String, weight:Integer]>");
        assertFailure(assertThrows(GraphAssertionError.class, () -> flow.hasKeysExactly()),
                "outcome.flow", "keys", List.of(), written,
                "outcome.flow: keys expected <[]> but was <[tracking:String, weight:Integer]>");
    }

    // --- where the view opens ----------------------------------------------------------------------

    /** An abort undoes no write, so the flow of its failure keeps what the aborted step put. */
    @Test
    void aCallFailureViewOpensTheFlowOfTheFailedCall() {
        Parcel shipped = new Parcel("p2", Spot.SHIPPED);
        Throwable failure = GraphAssertions.captureFailure(() -> Parcels.machine().execute(shipped, Move.HOLD));
        TransitionFailureAssert.assertThat(failure).isAbort().hasPhase(CallPhase.ACTION).flow()
                .hasEntitySameAs(shipped)
                .hasValue(Parcels.NOTE, "customs")
                .hasKeysExactly(Parcels.NOTE);
        TransitionFailureAssert.assertThat(failure).isCallFailure().flow().hasValue(Parcels.NOTE, "customs");
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> TransitionFailureAssert.assertThat(failure).isAbort().flow().hasNoValue(Parcels.NOTE)),
                "abort.flow", "value of note:String", Optional.empty(), Optional.of("customs"));
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> TransitionFailureAssert.assertThat(failure).isCallFailure().flow()
                                .hasValue(Parcels.TRACKING, "T-1")),
                "call failure.flow", "value of tracking:String", Optional.of("T-1"), Optional.empty());
    }

    /**
     * The trail opens from the flow of any view and extends its name, and so do the records of its hops, which say
     * where each hop went, a failure's included; a machine without the trail records none.
     */
    @Test
    void theTrailOpensFromTheFlowAndExtendsItsName() {
        Outcome<Parcel, Spot, Move> shipped = Parcels.machine(Parcels.ticking()).execute(parcel, Move.SHIP);
        OutcomeAssert.assertThat(shipped).isApplied().flow().trail().hasHopsExactly(Move.SHIP)
                .hops().forEntity(Parcels.PARCELS, parcel).single()
                .from(Spot.PACKED).hasTransition(Move.SHIP).to(Spot.SHIPPED);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> OutcomeAssert.assertThat(shipped).isApplied().flow().trail().isEmpty()),
                "applied outcome.flow.trail", "size", 0, 1);
        Parcel held = new Parcel("p2", Spot.SHIPPED);
        Throwable failure =
                GraphAssertions.captureFailure(() -> Parcels.machine(Parcels.ticking()).execute(held, Move.HOLD));
        TransitionFailureAssert.assertThat(failure).isAbort().flow().trail().endsWithFailure(CallPhase.ACTION)
                .hops().forEntity(Parcels.PARCELS, held).single()
                .from(Spot.SHIPPED).hasTransition(Move.HOLD).to(Spot.HELD);
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> TransitionFailureAssert.assertThat(failure).isAbort().flow().trail().hops()
                                .forEntity(Parcels.PARCELS, held).single().to(Spot.DELIVERED)),
                "abort.flow.trail.hops for Parcel/Spot[0]", "to", Spot.DELIVERED, Spot.HELD,
                "abort.flow.trail.hops for Parcel/Spot[0]: to expected <DELIVERED> but was <HELD>");
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> TransitionFailureAssert.assertThat(failure).isCallFailure().flow().trail()
                                .endsWithFailure(CallPhase.COMMIT)),
                "call failure.flow.trail[1]", "phase", CallPhase.COMMIT, CallPhase.ACTION);
        OutcomeAssert.assertThat(Parcels.machine().execute(new Parcel("p3", Spot.PACKED), Move.SHIP)).flow()
                .hasValue(Parcels.TRACKING, "T-1")
                .trail().isEmpty();
    }

    /** A hook that throws after the commit: its failure's flow holds the root entity, and its trail ends with it. */
    @Test
    void anAfterCommitFailureOpensTheFlowOfItsCall() {
        Checkout checkout = new Checkout();
        checkout.audit = (Order audited) -> {
            throw new IllegalStateException("audit down");
        };
        Order order = new Order("o1", new Payment("pay1", PaymentState.DECLINED));
        try (MachineRegistry registry = checkout.registry(Parcels.ticking())) {
            TransitionFailureAssert.assertThat(GraphAssertions.captureFailure(
                            () -> registry.of(Checkout.ORDERS).execute(order, OrderChange.CHECKOUT)))
                    .isAfterCommit().flow()
                    .hasEntitySameAs(order)
                    .hasKeysExactly()
                    .trail()
                    .hasHopsExactly(OrderChange.CHECKOUT)
                    .endsWithFailure(CallPhase.HOOK);
        }
    }

    /** A reaction is a call of its own: its outcome hands out its own flow, and so does the failure of an abort. */
    @Test
    void aReactionsOutcomeOpensTheFlowOfTheReactionAndAnAbortedOnesCauseItsOwn() {
        Outcome<Parcel, Spot, Move> shipped = Parcels.machine().execute(parcel, Move.SHIP);
        ReactionsAssert done = ReactionsAssert.assertThat(List.of(new Reaction.Done(Parcels.PARCELS, parcel, shipped)));
        done.forEntity(Parcels.PARCELS, parcel).single().isDone().outcome().flow().hasValue(Parcels.TRACKING, "T-1");
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> done.forEntity(Parcels.PARCELS, parcel).single().isDone().outcome().flow()
                                .hasNoValue(Parcels.TRACKING)),
                "reactions for " + Parcels.PARCELS + "[0].outcome.flow", "value of tracking:String",
                Optional.empty(), Optional.of("T-1"));

        Parcel held = new Parcel("p2", Spot.SHIPPED);
        CallFailedException failure =
                assertThrows(CallFailedException.class, () -> Parcels.machine().execute(held, Move.HOLD));
        Reaction.Aborted aborted = new Reaction.Aborted(Parcels.PARCELS, held, failure);
        ReactionsAssert.assertThat(List.of(aborted)).forEntity(Parcels.PARCELS, held).single().isAborted()
                .hasCauseSameAs(failure);
        GraphAssertions.assertThat(aborted.cause()).isCallFailure().flow().hasValue(Parcels.NOTE, "customs");
    }

    /**
     * A hook called by hand gets a flow of no call: the view, built before the hook runs, reads what the hook wrote
     * when each expectation runs, and finds no entity.
     */
    @Test
    void theFlowOfACommitContextBuiltByHandIsASubjectOfItsOwn() {
        HookRef<Parcel, Spot, Move> stamp = new HookRef<>("stamp",
                (Parcel stamped, CommitContext<Spot, Move> commit) -> commit.flow().put(Parcels.TRACKING, "T-9"));
        CommitContext<Spot, Move> commit =
                new CommitContext<>(Spot.PACKED, Move.SHIP, Spot.SHIPPED, TransitionKind.ACTION, false);
        FlowAssert flow = GraphAssertions.assertThat(commit.flow());
        assertSame(flow, flow.hasKeysExactly());
        stamp.run(parcel, commit);
        assertSame(flow, flow.hasValue(Parcels.TRACKING, "T-9").hasKeysExactly(Parcels.TRACKING));
        flow.trail().isEmpty();
        assertFailure(assertThrows(GraphAssertionError.class, () -> flow.hasEntitySameAs(parcel)),
                "flow", "entity", parcel, "none", "flow: entity expected <Parcel#p1> but was <none>");
    }

    // --- arguments ---------------------------------------------------------------------------------

    @Test
    void aNullArgumentIsAnArgumentErrorEvenWhenTheSubjectIsNull() {
        FlowAssert shipped = OutcomeAssert.assertThat(Parcels.machine().execute(parcel, Move.SHIP)).flow();
        for (FlowAssert view : List.of(shipped, FlowAssert.assertThat(null))) {
            assertEquals("expected", assertThrows(NullPointerException.class,
                    () -> view.hasEntitySameAs(null)).getMessage());
            assertEquals("key", assertThrows(NullPointerException.class,
                    () -> view.hasValue(null, "T-1")).getMessage());
            assertEquals("expected", assertThrows(NullPointerException.class,
                    () -> view.hasValue(Parcels.TRACKING, null)).getMessage());
            assertEquals("key", assertThrows(NullPointerException.class,
                    () -> view.hasNoValue(null)).getMessage());
            assertEquals("keys", assertThrows(NullPointerException.class,
                    () -> view.hasKeysExactly((FlowKey<?>[]) null)).getMessage());
            assertThrows(NullPointerException.class, () -> view.hasKeysExactly(Parcels.TRACKING, null));
        }
    }

    // --- one null-subject case per expectation method -----------------------------------------------

    @Test
    void nullSubjectFailsHasEntitySameAs() {
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> FlowAssert.assertThat(null).hasEntitySameAs(parcel)),
                "flow", "entity", parcel, null);
    }

    @Test
    void nullSubjectFailsHasValue() {
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> FlowAssert.assertThat(null).hasValue(Parcels.TRACKING, "T-1")),
                "flow", "value of tracking:String", Optional.of("T-1"), null);
    }

    @Test
    void nullSubjectFailsHasNoValue() {
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> FlowAssert.assertThat(null).hasNoValue(Parcels.TRACKING)),
                "flow", "value of tracking:String", Optional.empty(), null);
    }

    @Test
    void nullSubjectFailsHasKeysExactly() {
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> FlowAssert.assertThat(null).hasKeysExactly(Parcels.TRACKING)),
                "flow", "keys", List.of(Parcels.TRACKING), null);
    }

    @Test
    void nullSubjectFailsTrail() {
        assertFailure(assertThrows(GraphAssertionError.class, () -> FlowAssert.assertThat(null).trail().isEmpty()),
                "flow.trail", "size", 0, null);
    }

    @Test
    void nullOutcomeFailsFlow() {
        Outcome<Parcel, Spot, Move> none = null;
        assertFailure(assertThrows(GraphAssertionError.class,
                        () -> OutcomeAssert.assertThat(none).flow().hasValue(Parcels.TRACKING, "T-1")),
                "outcome.flow", "value of tracking:String", Optional.of("T-1"), null);
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** How a failure names an instance whose rendering it cannot tell from another's: class and identity hash. */
    private static String instance(Object value) {
        return value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }

    private static void assertFailure(GraphAssertionError error, String context, String property,
            Object expected, Object actual) {
        assertEquals(context, error.context());
        assertEquals(property, error.property());
        assertEquals(expected, error.expected());
        assertEquals(actual, error.actual());
    }

    /** The fields of the error, then the message they render. */
    private static void assertFailure(GraphAssertionError error, String context, String property,
            Object expected, Object actual, String message) {
        assertFailure(error, context, property, expected, actual);
        assertEquals(message, error.getMessage());
    }
}
