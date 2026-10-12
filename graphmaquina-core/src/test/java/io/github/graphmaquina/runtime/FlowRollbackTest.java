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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.CascadePolicy;
import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.GraphBuilder;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.CascadeException;
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
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Spec §9.1 item 3, the rollback matrix of spec R12: a step's writes are dropped with the step, strictly after its
 * savepoint, so a failure route or a COLLECT element undoes its own writes, those of the steps it ran, and nobody
 * else's. Each test reads the flow from inside the call, where the writes that survived are visible.
 */
class FlowRollbackTest {

    private static final FlowKey<String> PARENT = FlowKey.of("parent", String.class);
    private static final FlowKey<String> CHILD = FlowKey.of("child", String.class);
    private static final FlowKey<String> RECOVERED = FlowKey.of("recovered", String.class);
    @SuppressWarnings("rawtypes")
    private static final FlowKey<List> LINES = FlowKey.of("lines", List.class);

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

    private static MachineRegistry registry(GraphDefinition<Doc, DocStatus, DocTransition> graph) {
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Doc::status, Doc::setStatus).identity(Doc::id)
                        .asyncTimeout(Duration.ofSeconds(5)))
                .build();
    }

    /** The root's own failure route: the failed action's writes are gone, and the recovery action writes anew. */
    @Test
    void aFailureRouteDropsTheWritesOfTheFailedAction() {
        Doc doc = new Doc("1", DRAFT);
        List<Object> recovered = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(PARENT, "p");
                            ctx.flow().put(CHILD, "c");
                            ctx.flow().remove(CHILD);
                            return ActionOutcome.failed("no reviewer");
                        })
                        .onFailure(DISCARD),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.action("Archive",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            recovered.add(List.copyOf(ctx.flow().keys()));
                            ctx.flow().put(RECOVERED, "r");
                            recovered.add(List.copyOf(ctx.flow().keys()));
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph)) {
            assertEquals(OutcomeCode.FAILURE_ROUTED, registry.of(DOCS).execute(doc, SUBMIT).code());
        }
        assertEquals(List.of(List.of(), List.of(RECOVERED)), recovered);
        assertEquals(ARCHIVED, doc.status());
    }

    /**
     * Review Focus 5: values are stored by reference. The failed action's write of the list is rolled back, so the
     * recovery action finds no list, but the list object keeps what the action added to it.
     */
    @Test
    void aRolledBackListKeepsWhatWasAddedToIt() {
        Doc doc = new Doc("1", DRAFT);
        List<String> lines = new ArrayList<>();
        List<Boolean> recovered = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Reserve", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(LINES, lines);
                            lines.add("reserved");
                            return ActionOutcome.failed("out of stock");
                        })
                        .onFailure(DISCARD),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.action("Archive",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            recovered.add(ctx.flow().get(LINES).isPresent());
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph)) {
            assertEquals(OutcomeCode.FAILURE_ROUTED, registry.of(DOCS).execute(doc, SUBMIT).code());
        }
        assertEquals(List.of(false), recovered);
        assertEquals(List.of("reserved"), lines);
    }

    /**
     * A COLLECT element that aborts is rolled back to its savepoint, which equals the position of the write its
     * parent action made before cascading: that write stays, the element's own is gone, and the next element's
     * stays. The aggregate then poisons the call, which throws it.
     */
    @Test
    void aCollectElementThatAbortsKeepsTheWriteItsParentMadeBefore() {
        Doc parent = new Doc("1", DRAFT);
        Doc failing = new Doc("2", DRAFT);
        Doc passing = new Doc("3", DRAFT);
        List<Object> seen = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Review",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            seen.add(ctx.entity() + " sees " + ctx.flow().keys());
                            ctx.flow().put(CHILD, ctx.entity().id());
                            return ctx.entity() == failing ? ActionOutcome.failed("no reviewer") : ActionOutcome.ok();
                        }),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.action("Collect",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(PARENT, "p");
                            try {
                                ctx.cascadeAll(DOCS, List.of(failing, passing), SUBMIT, CascadePolicy.COLLECT);
                            } catch (CascadeException collected) {
                                seen.add(List.copyOf(ctx.flow().keys()));
                                seen.add(ctx.flow().get(CHILD));
                            }
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph)) {
            assertThrows(CascadeException.class, () -> registry.of(DOCS).execute(parent, DISCARD));
        }
        assertEquals(List.of("Doc#2 sees [parent:String]", "Doc#3 sees [parent:String]", List.of(PARENT, CHILD),
                Optional.of("3")), seen);
    }

    /**
     * A failure route inside a cascade: the cascaded step's savepoint equals the position of the write its parent
     * action made before cascading. The route drops the cascaded action's write and keeps the parent's, the
     * cascaded recovery action writes, and the parent reads both once the cascade returns.
     */
    @Test
    void aCascadedStepsRouteKeepsTheWriteItsParentMadeBefore() {
        Doc parent = new Doc("1", DRAFT);
        Doc child = new Doc("2", DRAFT);
        List<Object> seen = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(CHILD, "c");
                            return ActionOutcome.failed("no reviewer");
                        })
                        .onFailure(DISCARD),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.action("Collect",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            if (ctx.entity() == child) {
                                seen.add("recovery sees " + ctx.flow().keys());
                                ctx.flow().put(RECOVERED, "r");
                                return ActionOutcome.ok();
                            }
                            ctx.flow().put(PARENT, "p");
                            Outcome<Doc, DocStatus, DocTransition> cascaded = ctx.cascade(DOCS, child, SUBMIT);
                            seen.add(cascaded.code());
                            seen.add("parent sees " + ctx.flow().keys());
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph)) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(parent, DISCARD));
        }
        assertEquals(List.of("recovery sees [parent:String]", OutcomeCode.FAILURE_ROUTED,
                "parent sees [parent:String, recovered:String]"), seen);
        assertEquals(ARCHIVED, child.status());
    }

    /** The root's failure route also drops what the steps its failed action cascaded to wrote. */
    @Test
    void aFailureRouteDropsTheWritesOfTheCascadesItsActionRan() {
        Doc parent = new Doc("1", DRAFT);
        Doc child = new Doc("2", DRAFT);
        List<Object> seen = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            if (ctx.entity() == child) {
                                ctx.flow().put(CHILD, "c");
                                return ActionOutcome.ok();
                            }
                            ctx.flow().put(PARENT, "p");
                            ctx.cascade(DOCS, child, SUBMIT);
                            seen.add("failing sees " + ctx.flow().keys());
                            return ActionOutcome.failed("no reviewer");
                        })
                        .onFailure(DISCARD),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.action("Archive",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            seen.add("recovery sees " + ctx.flow().keys());
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph)) {
            assertEquals(OutcomeCode.FAILURE_ROUTED, registry.of(DOCS).execute(parent, SUBMIT).code());
        }
        assertEquals(List.of("failing sees [parent:String, child:String]", "recovery sees []"), seen);
        assertEquals(DRAFT, child.status());
        assertEquals(ARCHIVED, parent.status());
    }

    /**
     * The exit of a sub-machine is a step one level deeper, whose savepoint equals the position of the write the
     * child's action made: the exit's failure route drops the exit action's write and keeps the child's.
     */
    @Test
    void theRouteOfASubmachineExitKeepsTheWriteOfTheStepThatEndedTheChild() {
        Billing billing = new Billing();
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Payment balance = new Payment("p2", PaymentStatus.CAPTURED);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit).attachBalance(balance);
        List<String> seen = new ArrayList<>();
        billing.paymentActions.behaviour = (TransitionContext<Payment, PaymentStatus, PaymentTransition> ctx) -> {
            ctx.flow().put(CHILD, "captured");
            return ActionOutcome.ok();
        };
        billing.bookingActions.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            if (ctx.transition() == BookingTransition.DEPOSIT_PAID) {
                ctx.flow().put(PARENT, "settled");
                return ActionOutcome.failed("the deposit cannot be settled");
            }
            seen.add(ctx.transition() + " sees " + ctx.flow().keys());
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            Outcome<Payment, PaymentStatus, PaymentTransition> outcome =
                    registry.of(PAYMENTS).execute(deposit, PaymentTransition.CAPTURE);
            assertInstanceOf(Outcome.Applied.class, outcome);
        }
        assertEquals(List.of("CANCEL sees [child:String]"), seen);
        assertEquals(BookingStatus.CANCELLED, booking.status());
        assertEquals(PaymentStatus.CAPTURED, deposit.status());
    }

    /**
     * An async action writes before it returns its stage; when the stage completes with a failure, the route drops
     * that write as it drops a synchronous action's.
     */
    @Test
    void aFailureRouteDropsTheWritesOfAnAsyncAction() {
        Doc doc = new Doc("1", DRAFT);
        List<Object> recovered = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .asyncAction("Fetch", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(PARENT, "requested");
                            return CompletableFuture.supplyAsync(() -> ActionOutcome.failed("the service declined"));
                        })
                        .onFailure(DISCARD),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.action("Archive",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            recovered.add(ctx.flow().get(PARENT));
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph)) {
            assertEquals(OutcomeCode.FAILURE_ROUTED, registry.of(DOCS).execute(doc, SUBMIT).code());
        }
        assertEquals(List.of(Optional.empty()), recovered);
    }
}
