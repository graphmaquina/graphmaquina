package io.github.graphmaquina.runtime;

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
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.CallPhase;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.PeerView;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Billing;
import io.github.graphmaquina.fixtures.Billing.Booking;
import io.github.graphmaquina.fixtures.Billing.BookingStatus;
import io.github.graphmaquina.fixtures.Billing.Payment;
import io.github.graphmaquina.fixtures.Billing.PaymentStatus;
import io.github.graphmaquina.fixtures.Billing.PaymentTransition;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import java.time.InstantSource;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A rejected call whose trail clock cannot date its Rejected entry fails, and its abort still describes the step the
 * call took: the state it read, its identifier, a reevaluation's included, and the destination of the edge it had
 * selected, which a rejection by a guard or for a reserved exit has, and one with no edge or a detached sub-machine
 * has not.
 */
class RejectedClockFailureTest {

    private static final InstantSource STOPPED = () -> {
        throw new IllegalStateException("clock stopped");
    };

    /** The doc graph: SUBMIT is guarded by Ready, which rejects, and a watch from DRAFT always selects SUBMIT. */
    private static GraphDefinition<Doc, DocStatus, DocTransition> guarded() {
        return StateGraph.define(DOCS).initial(DRAFT).terminal(ARCHIVED)
                .statePolicy(DRAFT, "a draft is submitted or discarded").statePolicy(REVIEW, "a review is published")
                .statePolicy(PUBLISHED, "a publication is archived")
                .transition(SUBMIT, DRAFT, REVIEW)
                    .guard("Ready",
                            (GuardContext<Doc, DocStatus, DocTransition> ctx) -> GuardResult.reject("not ready"))
                    .endTransition()
                .transition(DISCARD, DRAFT, ARCHIVED).endTransition()
                .transition(PUBLISH, REVIEW, PUBLISHED).endTransition()
                .transition(ARCHIVE, PUBLISHED, ARCHIVED).endTransition()
                .watch(DRAFT, DOCS, (Doc owner) -> List.of(owner), (Doc moved) -> Optional.of(moved))
                    .when("always", (Doc owner, Collection<? extends Doc> peers,
                            PeerView<Doc, DocStatus, DocTransition> view) -> true).execute(SUBMIT)
                    .endWatch()
                .build();
    }

    private static void assertDescribes(TransitionAbortedException thrown, Enum<?> from, Enum<?> transition,
                                        Optional<Enum<?>> to) {
        assertEquals(Optional.of(from), thrown.from());
        assertEquals(Optional.of(transition), thrown.transition());
        assertEquals(to, thrown.to());
        assertEquals("clock stopped", thrown.getCause().getMessage());
    }

    @Test
    void aGuardRejectionNamesTheStateItReadAndTheDestinationOfItsEdge() {
        TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                () -> StateMachines.from(guarded()).state(Doc::status, Doc::setStatus).recordTrail(STOPPED).build()
                        .execute(new Doc("1", DRAFT), SUBMIT));
        assertEquals(CallPhase.GUARD, thrown.phase());
        assertDescribes(thrown, DRAFT, SUBMIT, Optional.of(REVIEW));
    }

    @Test
    void aRequestWithNoEdgeNamesTheStateItReadAndNoDestination() {
        TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                () -> StateMachines.from(guarded()).state(Doc::status, Doc::setStatus).recordTrail(STOPPED).build()
                        .execute(new Doc("1", DRAFT), PUBLISH));
        assertDescribes(thrown, DRAFT, PUBLISH, Optional.empty());
    }

    @Test
    void aRejectedReevaluationNamesTheIdentifierItsWatchSelected() {
        TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                () -> StateMachines.from(guarded()).state(Doc::status, Doc::setStatus).recordTrail(STOPPED).build()
                        .reevaluate(new Doc("1", DRAFT)));
        assertDescribes(thrown, DRAFT, SUBMIT, Optional.of(REVIEW));
    }

    @Test
    void aDetachedSubMachineNamesNoDestination() {
        Billing billing = new Billing();
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        new Booking("b1", BookingStatus.CONFIRMED).attachDeposit(deposit);
        try (MachineRegistry registry = MachineRegistry.builder().add(billing.bookingMachine())
                .add(billing.paymentMachine().recordTrail(STOPPED)).build()) {
            TransitionAbortedException thrown = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(Billing.PAYMENTS).execute(deposit, PaymentTransition.HOLD));
            assertDescribes(thrown, PaymentStatus.AUTHORIZING, PaymentTransition.HOLD, Optional.empty());
        }
    }
}
