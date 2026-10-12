package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Billing.BOOKINGS;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CallFailure;
import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.MachineCall;
import io.github.graphmaquina.api.MachineObserver;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.StatesWritten;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.CallFailedException;
import io.github.graphmaquina.error.CommitFailedException;
import io.github.graphmaquina.error.TransitionAbortedException;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * A failure of the application whose message cannot be read still ends the call as the failure that describes it:
 * the call's exception carries it as its cause and prints {@code <toString threw <class name>>} in its place, the
 * observers hear the end of the call, and nothing the library builds to describe it escapes in its stead.
 */
class UnprintableFailureMessageTest {

    private static final String MARKER = "<toString threw java.lang.IllegalStateException>";

    /** A failure whose message cannot be read. */
    static final class UnreadableMessage extends RuntimeException {
        @Override
        public String getMessage() {
            throw new IllegalStateException("the message cannot be read");
        }
    }

    /** An identity that compares by name and whose hashCode runs {@link #onHash} once it is armed. */
    static final class ArmedId {
        private final String name;
        private Runnable onHash = () -> { };

        ArmedId(String name) {
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ArmedId that && that.name.equals(name);
        }

        @Override
        public int hashCode() {
            onHash.run();
            return name.hashCode();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** The doc graph, with {@code submit}, {@code discard} and {@code publish} decorating those edges. */
    private static GraphDefinition<Doc, DocStatus, DocTransition> docs(
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> submit,
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> discard,
            UnaryOperator<TransitionBuilder<Doc, DocStatus, DocTransition>> publish) {
        GraphBuilder<Doc, DocStatus, DocTransition> graph = StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived");
        graph = submit.apply(graph.transition(SUBMIT, DRAFT, REVIEW)).endTransition();
        graph = discard.apply(graph.transition(DISCARD, DRAFT, ARCHIVED)).endTransition();
        graph = publish.apply(graph.transition(PUBLISH, REVIEW, PUBLISHED)).endTransition();
        return graph.transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition().build();
    }

    private static GraphDefinition<Doc, DocStatus, DocTransition> plainDocs() {
        return docs(UnaryOperator.identity(), UnaryOperator.identity(), UnaryOperator.identity());
    }

    /** Records how each call ends for its observers. */
    private static MachineObserver recording(List<String> events) {
        return new MachineObserver() {
            @Override
            public void onStarted(MachineCall call) {
                events.add("started");
            }

            @Override
            public void onCompleted(MachineCall call, Outcome<?, ?, ?> outcome, Duration elapsed) {
                events.add("completed");
            }

            @Override
            public void onFailed(MachineCall call, CallFailure failure, Duration elapsed) {
                events.add("failed " + failure.phase() + " " + failure.error().getClass().getSimpleName());
            }
        };
    }

    private static void assertDescribes(CallFailedException thrown, UnreadableMessage failure, String what) {
        assertSame(failure, thrown.getCause());
        assertTrue(thrown.getMessage().contains(what + MARKER), thrown.getMessage());
    }

    @Test
    void anActionsUnreadableFailureIsTheCauseOfItsAbort() {
        UnreadableMessage failure = new UnreadableMessage();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw failure;
                        }),
                UnaryOperator.identity(), UnaryOperator.identity());
        TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                () -> StateMachines.from(graph).state(Doc::status, Doc::setStatus).build()
                        .execute(new Doc("1", DRAFT), SUBMIT));
        assertEquals(CallPhase.ACTION, thrown.phase());
        assertDescribes(thrown, failure, "the action Submit threw ");
    }

    @Test
    void aDeclaredFailureWhoseMessageCannotBeReadStillTakesItsRoute() {
        UnreadableMessage failure = new UnreadableMessage();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw failure;
                        }).failsOn(UnreadableMessage.class).onFailure(DISCARD),
                UnaryOperator.identity(), UnaryOperator.identity());
        Doc doc = new Doc("1", DRAFT);
        Outcome<Doc, DocStatus, DocTransition> outcome = StateMachines.from(graph).state(Doc::status, Doc::setStatus)
                .build().execute(doc, SUBMIT);
        assertInstanceOf(Outcome.Failed.class, outcome);
        Outcome.Failed<Doc, DocStatus, DocTransition> routed = (Outcome.Failed<Doc, DocStatus, DocTransition>) outcome;
        assertEquals("UnreadableMessage: <getMessage threw java.lang.IllegalStateException>", routed.reason());
        assertEquals(Optional.of(failure), routed.cause());
        assertEquals(ARCHIVED, doc.status());
    }

    @Test
    void aGuardsUnreadableFailureIsTheCauseOfItsAbort() {
        UnreadableMessage failure = new UnreadableMessage();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("Ready",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            throw failure;
                        }),
                UnaryOperator.identity(), UnaryOperator.identity());
        TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                () -> StateMachines.from(graph).state(Doc::status, Doc::setStatus).build()
                        .execute(new Doc("1", DRAFT), SUBMIT));
        assertEquals(CallPhase.GUARD, thrown.phase());
        assertDescribes(thrown, failure, "the guard Ready threw ");
    }

    @Test
    void anUnreadableFailureToReadTheRootIsTheCauseOfItsAbort() {
        UnreadableMessage failure = new UnreadableMessage();
        TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                () -> StateMachines.from(plainDocs()).state((Doc doc) -> {
                    throw failure;
                }, Doc::setStatus).build().execute(new Doc("1", DRAFT), SUBMIT));
        assertEquals(CallPhase.RESOLVE, thrown.phase());
        assertDescribes(thrown, failure, "reading the entity threw ");
    }

    @Test
    void anUnreadableFailureToReadAReevaluatedEntityIsTheCauseOfItsAbort() {
        UnreadableMessage failure = new UnreadableMessage();
        List<String> events = new ArrayList<>();
        try (MachineRegistry registry = MachineRegistry.builder().observer(recording(events))
                .add(StateMachines.from(plainDocs()).state((Doc doc) -> {
                    throw failure;
                }, Doc::setStatus).identity(Doc::id)).build()) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).reevaluate(new Doc("1", DRAFT)));
            assertEquals(CallPhase.RESOLVE, thrown.phase());
            assertDescribes(thrown, failure, "reading the entity threw ");
        }
        assertEquals(List.of("started", "failed RESOLVE TransitionAbortedException"), events);
    }

    @Test
    void aSetterWithAnUnreadableFailureEndsInACommitFailureTheObserversHear() {
        Doc root = new Doc("root", DRAFT);
        Doc peer = new Doc("peer", REVIEW);
        UnreadableMessage failure = new UnreadableMessage();
        List<String> events = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.cascade(DOCS, peer, PUBLISH);
                            return ActionOutcome.ok();
                        }),
                UnaryOperator.identity(), UnaryOperator.identity());
        try (MachineRegistry registry = MachineRegistry.builder().observer(recording(events))
                .add(StateMachines.from(graph).state(Doc::status, (Doc doc, DocStatus state) -> {
                    if (doc == peer) {
                        throw failure;
                    }
                    doc.setStatus(state);
                }).identity(Doc::id)).build()) {
            CommitFailedException thrown = assertThrows(CommitFailedException.class,
                    () -> registry.of(DOCS).execute(root, SUBMIT));
            assertEquals(StatesWritten.PARTIAL, thrown.statesWritten());
            assertDescribes(thrown, failure, "a setter threw ");
        }
        assertEquals(List.of("started", "failed COMMIT CommitFailedException"), events);
        assertEquals(List.of(REVIEW, REVIEW), List.of(root.status(), peer.status()));
    }

    @Test
    void anUnreadableFailureOfTheCommitsCheckEndsInACommitFailure() {
        Doc root = new Doc("root", DRAFT);
        UnreadableMessage failure = new UnreadableMessage();
        boolean[] acted = {false};
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            acted[0] = true;
                            return ActionOutcome.ok();
                        }),
                UnaryOperator.identity(), UnaryOperator.identity());
        CommitFailedException thrown = assertThrows(CommitFailedException.class,
                () -> StateMachines.from(graph).state((Doc doc) -> {
                    if (acted[0]) {
                        throw failure;
                    }
                    return doc.status();
                }, Doc::setStatus).build().execute(root, SUBMIT));
        assertEquals(StatesWritten.NONE, thrown.statesWritten());
        assertDescribes(thrown, failure, "preparing the commit threw ");
        assertEquals(DRAFT, root.status());
    }

    @Test
    void anUnreadableFailureTheEngineLetsThroughIsTheCauseOfItsAbort() {
        Doc root = new Doc("root", DRAFT);
        Doc peer = new Doc("peer", REVIEW);
        ArmedId peerId = new ArmedId("peer");
        UnreadableMessage failure = new UnreadableMessage();
        List<String> events = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Submit",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.cascade(DOCS, peer, PUBLISH);
                            peerId.onHash = () -> {
                                throw failure;
                            };
                            return ActionOutcome.failed("route it");
                        }).onFailure(DISCARD),
                UnaryOperator.identity(), UnaryOperator.identity());
        try (MachineRegistry registry = MachineRegistry.builder().observer(recording(events))
                .add(StateMachines.from(graph).state(Doc::status, Doc::setStatus)
                        .identity((Doc doc) -> doc == peer ? peerId : doc.id())).build()) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(root, SUBMIT));
            assertEquals(CallPhase.ACTION, thrown.phase());
            assertDescribes(thrown, failure, "the engine could not go on: ");
        }
        assertEquals(List.of("started", "failed ACTION TransitionAbortedException"), events);
        assertEquals(List.of(DRAFT, REVIEW), List.of(root.status(), peer.status()));
    }

    @Test
    void aChildOfWithAnUnreadableFailureAtTheEntryIsTheCauseOfItsAbort() {
        Billing billing = new Billing();
        Booking booking = new Booking("b1", BookingStatus.REQUESTED)
                .attachDeposit(new Payment("d1", PaymentStatus.CAPTURED));
        UnreadableMessage failure = new UnreadableMessage();
        billing.depositOf = (Booking entered) -> {
            throw failure;
        };
        try (MachineRegistry registry = billing.registry()) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(BOOKINGS).execute(booking, BookingTransition.BOOK));
            assertDescribes(thrown, failure, "the childOf of Booking/BookingStatus.DEPOSIT threw ");
        }
    }

    @Test
    void aParentOfWithAnUnreadableFailureIsTheCauseOfItsAbort() {
        Billing billing = new Billing();
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        UnreadableMessage failure = new UnreadableMessage();
        billing.bookingOf = (Payment payment) -> {
            throw failure;
        };
        try (MachineRegistry registry = billing.registry()) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(PAYMENTS).execute(deposit, PaymentTransition.HOLD));
            assertDescribes(thrown, failure, "the parentOf of Booking/BookingStatus.DEPOSIT threw ");
        }
    }

    @Test
    void aChildOfWithAnUnreadableFailureDuringTheCheckIsTheCauseOfItsAbort() {
        Billing billing = new Billing();
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        UnreadableMessage failure = new UnreadableMessage();
        billing.depositOf = (Booking booking) -> {
            throw failure;
        };
        try (MachineRegistry registry = billing.registry()) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(PAYMENTS).execute(deposit, PaymentTransition.HOLD));
            assertDescribes(thrown, failure, "the childOf of Booking/BookingStatus.DEPOSIT threw ");
        }
    }

    @Test
    void aParentIdentityWithAnUnreadableFailureIsTheCauseOfItsAbort() {
        Billing billing = new Billing();
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        UnreadableMessage failure = new UnreadableMessage();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(billing.bookings).state(Booking::status, Booking::setStatus)
                        .identity((Booking booking) -> {
                            throw failure;
                        }))
                .add(billing.paymentMachine()).build()) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(PAYMENTS).execute(deposit, PaymentTransition.HOLD));
            assertDescribes(thrown, failure, "checking the binding Booking/BookingStatus.DEPOSIT threw ");
        }
    }

    @Test
    void anUnreadableFailureToReadTheParentIsTheCauseOfItsAbort() {
        Billing billing = new Billing();
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit);
        UnreadableMessage failure = new UnreadableMessage();
        try (MachineRegistry registry = MachineRegistry.builder()
                .add(StateMachines.from(billing.bookings).state((Booking booking) -> {
                    throw failure;
                }, Booking::setStatus).identity(Booking::id))
                .add(billing.paymentMachine()).build()) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(PAYMENTS).execute(deposit, PaymentTransition.HOLD));
            assertDescribes(thrown, failure, "checking the binding Booking/BookingStatus.DEPOSIT threw ");
        }
    }

    @Test
    void anUnreadableFailureToReadTheChildAtTheEntryIsTheCauseOfItsAbort() {
        Billing billing = new Billing();
        Booking booking = new Booking("b1", BookingStatus.REQUESTED)
                .attachDeposit(new Payment("d1", PaymentStatus.AUTHORIZING));
        UnreadableMessage failure = new UnreadableMessage();
        try (MachineRegistry registry = MachineRegistry.builder().add(billing.bookingMachine())
                .add(StateMachines.from(billing.payments).state((Payment payment) -> {
                    throw failure;
                }, Payment::setStatus).identity(Payment::id)).build()) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(BOOKINGS).execute(booking, BookingTransition.BOOK));
            assertDescribes(thrown, failure, "reading the sub-machine of Booking/BookingStatus.DEPOSIT threw ");
        }
    }
}
