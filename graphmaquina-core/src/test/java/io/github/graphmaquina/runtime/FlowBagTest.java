package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Billing.PAYMENTS;
import static io.github.graphmaquina.fixtures.DocGraphs.docs;
import static io.github.graphmaquina.fixtures.DocMachines.registry;
import static io.github.graphmaquina.fixtures.DocStatus.ARCHIVED;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocStatus.PUBLISHED;
import static io.github.graphmaquina.fixtures.DocStatus.REVIEW;
import static io.github.graphmaquina.fixtures.DocTransition.DISCARD;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static io.github.graphmaquina.fixtures.LogLines.lines;
import static io.github.graphmaquina.fixtures.Thrown.describe;
import static io.github.graphmaquina.fixtures.Thrown.thrownBy;
import static io.github.graphmaquina.fixtures.Thrown.thrownOnAnotherThread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.FlowContext;
import io.github.graphmaquina.api.FlowKey;
import io.github.graphmaquina.api.FlowView;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.Outcome;
import io.github.graphmaquina.api.OutcomeCode;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.TransitionBuilder;
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
import io.github.graphmaquina.fixtures.Item;
import io.github.graphmaquina.fixtures.LibraryLog;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;

/**
 * The flow of a call as its steps reach it: an action reads and writes it through {@link TransitionContext#flow()}, a
 * guard reads it through {@link GuardContext#flow()}, and every step of the call, cascaded or the exit of a
 * sub-machine, reaches the same flow and the same root entity. Every operation of a handle runs its context's own
 * check first. Each write is logged at TRACE.
 */
class FlowBagTest {

    private static final FlowKey<String> BATCH = FlowKey.of("batch", String.class);
    private static final FlowKey<String> NOTE = FlowKey.visible("note", String.class);
    private static final FlowKey<Integer> COUNT = FlowKey.of("count", Integer.class);
    private static final FlowKey<Odd> ODD = FlowKey.visible("odd", Odd.class);
    private static final FlowKey<Counted> COUNTED = FlowKey.visible("counted", Counted.class);

    /** A value whose {@code toString()} throws. */
    private static final class Odd {
        @Override
        public String toString() {
            throw new IllegalStateException("no text");
        }
    }

    /** A value that counts how many times it was printed. */
    private static final class Counted {
        private final AtomicInteger printed = new AtomicInteger();

        @Override
        public String toString() {
            printed.incrementAndGet();
            return "counted";
        }
    }

    /** Every operation of {@code flow}, by name: its reads, and its writes when it is writable. */
    private static Map<String, Runnable> operations(FlowView flow) {
        Map<String, Runnable> operations = new LinkedHashMap<>();
        operations.put("get", () -> flow.get(BATCH));
        operations.put("keys", flow::keys);
        operations.put("entity", flow::entity);
        operations.put("entity(Class)", () -> flow.entity(Doc.class));
        operations.put("trail", flow::trail);
        if (flow instanceof FlowContext writable) {
            operations.put("put", () -> writable.put(BATCH, "late"));
            operations.put("remove", () -> writable.remove(BATCH));
        }
        return operations;
    }

    /** {@code describe} of each of {@code names}, all mapped to the same {@code text}, in order. */
    private static Map<String, String> each(List<String> names, String text) {
        Map<String, String> expected = new LinkedHashMap<>();
        for (String name : names) {
            expected.put(name, text);
        }
        return expected;
    }

    @Test
    void anActionPutsReadsAndRemovesValues() {
        Doc doc = new Doc("1", DRAFT);
        List<Object> seen = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Review",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            FlowContext flow = ctx.flow();
                            seen.add(flow.get(BATCH));
                            flow.put(BATCH, "b-1");
                            flow.put(COUNT, 2);
                            seen.add(flow.get(BATCH));
                            seen.add(List.copyOf(flow.keys()));
                            flow.remove(BATCH);
                            seen.add(ctx.flow().get(BATCH));
                            ctx.flow().put(BATCH, "b-2");
                            seen.add(List.copyOf(ctx.flow().keys()));
                            seen.add(flow.get(BATCH));
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph)) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(doc, SUBMIT));
        }
        assertEquals(List.of(Optional.empty(), Optional.of("b-1"), List.of(BATCH, COUNT), Optional.empty(),
                List.of(COUNT, BATCH), Optional.of("b-2")), seen);
    }

    /** Review Focus 3: the flow belongs to the call, so a cascaded child's guard reads what its parent put. */
    @Test
    void aCascadedChildsGuardReadsWhatTheParentActionPut() {
        Doc parent = new Doc("1", DRAFT);
        Doc child = new Doc("2", DRAFT);
        List<Optional<String>> read = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("Batched",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            Optional<String> batch = ctx.flow().get(BATCH);
                            read.add(batch);
                            return batch.isPresent() ? GuardResult.pass() : GuardResult.reject("no batch");
                        }),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.action("Collect",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(BATCH, "b-1");
                            ctx.cascade(DOCS, child, SUBMIT);
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph)) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(parent, DISCARD));
        }
        assertEquals(List.of(Optional.of("b-1")), read);
        assertEquals(REVIEW, child.status());
        assertEquals(ARCHIVED, parent.status());
    }

    /** A cascaded step's writes reach the action that cascaded, once the cascade returns. */
    @Test
    void aParentReadsWhatItsCascadedChildPut() {
        Doc parent = new Doc("1", DRAFT);
        Doc child = new Doc("2", DRAFT);
        List<Object> seen = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Review",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            seen.add(ctx.flow().get(BATCH));
                            ctx.flow().put(COUNT, 1);
                            return ActionOutcome.ok();
                        }),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.action("Collect",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(BATCH, "b-1");
                            ctx.cascade(DOCS, child, SUBMIT);
                            seen.add(List.copyOf(ctx.flow().keys()));
                            seen.add(ctx.flow().get(COUNT));
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph)) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(parent, DISCARD));
        }
        assertEquals(List.of(Optional.of("b-1"), List.of(BATCH, COUNT), Optional.of(1)), seen);
    }

    @Test
    void aNullKeyOrValueIsRefusedByName() {
        Doc doc = new Doc("1", DRAFT);
        List<String> refused = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("Reads", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            refused.add("guard get: " + describe(thrownBy(() -> ctx.flow().get(null))));
                            return GuardResult.pass();
                        })
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            FlowContext flow = ctx.flow();
                            refused.add("get: " + describe(thrownBy(() -> flow.get(null))));
                            refused.add("put key: " + describe(thrownBy(() -> flow.put(null, "b-1"))));
                            refused.add("put value: " + describe(thrownBy(() -> flow.put(BATCH, null))));
                            refused.add("remove: " + describe(thrownBy(() -> flow.remove(null))));
                            refused.add("keys: " + flow.keys());
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph)) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(doc, SUBMIT));
        }
        assertEquals(List.of("guard get: NullPointerException: key", "get: NullPointerException: key",
                "put key: NullPointerException: key", "put value: NullPointerException: value",
                "remove: NullPointerException: key", "keys: []"), refused);
    }

    /** D13: a value that is not an instance of the key's type is refused, as {@code Class.cast} refuses it. */
    @Test
    void aValueOfAnotherTypeIsRefusedWithClassCastException() {
        Doc doc = new Doc("1", DRAFT);
        @SuppressWarnings("unchecked")
        FlowKey<Object> loose = (FlowKey<Object>) (FlowKey<?>) BATCH;
        List<String> seen = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Review",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            seen.add(describe(thrownBy(() -> ctx.flow().put(loose, 5))));
                            seen.add(String.valueOf(ctx.flow().get(BATCH)));
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph)) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(doc, SUBMIT));
        }
        assertEquals(List.of("ClassCastException: Cannot cast java.lang.Integer to java.lang.String",
                "Optional.empty"), seen);
    }

    /**
     * A value is refused only when it is not an instance of its key's type: a key of an abstract type takes a value
     * of any subclass, and a read answers that very object.
     */
    @Test
    void aValueOfASubclassOfTheKeysTypeIsAccepted() {
        FlowKey<Number> amount = FlowKey.of("amount", Number.class);
        BigDecimal price = new BigDecimal("3.50");
        List<Optional<Number>> read = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Review",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(amount, price);
                            read.add(ctx.flow().get(amount));
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph)) {
            assertEquals(OutcomeCode.APPLIED, registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT).code());
        }
        assertSame(price, read.get(0).orElseThrow());
    }

    /**
     * The keys a read returns do not follow later writes: the set an action read before its next put and remove
     * stays as it was, and a new read answers the keys as they are now.
     */
    @Test
    void theKeysAReadReturnsDoNotFollowLaterWrites() {
        List<Set<FlowKey<?>>> read = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Review",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(BATCH, "b-1");
                            Set<FlowKey<?>> before = ctx.flow().keys();
                            ctx.flow().put(COUNT, 1);
                            ctx.flow().remove(BATCH);
                            read.add(before);
                            read.add(ctx.flow().keys());
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph)) {
            registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT);
        }
        assertEquals(List.of(Set.of(BATCH), Set.of(COUNT)), read);
    }

    @Test
    void theRootStepsGuardAndActionReachTheRootEntity() {
        Doc doc = new Doc("1", DRAFT);
        List<Object> seen = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("Reads", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            seen.add(ctx.flow().entity().orElseThrow());
                            seen.add(ctx.flow().entity(Doc.class).orElseThrow());
                            return GuardResult.pass();
                        })
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            seen.add(ctx.flow().entity().orElseThrow());
                            seen.add(ctx.flow().entity(Doc.class).orElseThrow());
                            seen.add(ctx.flow().entity(Object.class).orElseThrow());
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph)) {
            registry.of(DOCS).execute(doc, SUBMIT);
        }
        assertEquals(5, seen.size());
        for (Object entity : seen) {
            assertSame(doc, entity);
        }
    }

    /** A cascaded step reaches the entity of the root call through its flow; its own stays in ctx.entity(). */
    @Test
    void aCascadedStepReachesTheRootEntityAndKeepsItsOwnInItsContext() {
        Doc parent = new Doc("1", DRAFT);
        Doc child = new Doc("2", DRAFT);
        List<Object> roots = new ArrayList<>();
        List<Object> owns = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("Reads", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            roots.add(ctx.flow().entity(Doc.class).orElseThrow());
                            owns.add(ctx.entity());
                            return GuardResult.pass();
                        })
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            roots.add(ctx.flow().entity().orElseThrow());
                            owns.add(ctx.entity());
                            return ActionOutcome.ok();
                        }),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.action("Collect",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.cascade(DOCS, child, SUBMIT);
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph)) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(parent, DISCARD));
        }
        assertEquals(2, roots.size());
        assertSame(parent, roots.get(0));
        assertSame(parent, roots.get(1));
        assertSame(child, owns.get(0));
        assertSame(child, owns.get(1));
    }

    /**
     * A payment captured in its booking's DEPOSIT runs the booking's exit: its guard and its action reach the payment,
     * the root entity of the call, through the flow, and their own entity, the booking, through their context.
     */
    @Test
    void theExitOfASubmachineReachesTheRootEntity() {
        Billing billing = new Billing();
        Payment deposit = new Payment("d1", PaymentStatus.AUTHORIZING);
        Payment balance = new Payment("p2", PaymentStatus.CAPTURED);
        Booking booking = new Booking("b1", BookingStatus.DEPOSIT).attachDeposit(deposit).attachBalance(balance);
        List<Object> roots = new ArrayList<>();
        List<Object> owns = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        billing.paymentActions.behaviour = (TransitionContext<Payment, PaymentStatus, PaymentTransition> ctx) -> {
            roots.add(ctx.flow().entity(Payment.class).orElseThrow());
            return ActionOutcome.ok();
        };
        billing.settled.verdict = (GuardContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            roots.add(ctx.flow().entity(Payment.class).orElseThrow());
            owns.add(ctx.entity());
            return GuardResult.pass();
        };
        billing.bookingActions.behaviour = (TransitionContext<Booking, BookingStatus, BookingTransition> ctx) -> {
            roots.add(ctx.flow().entity().orElseThrow());
            owns.add(ctx.entity());
            refused.add(describe(thrownBy(() -> ctx.flow().entity(Booking.class))));
            return ActionOutcome.ok();
        };
        try (MachineRegistry registry = billing.registry()) {
            assertInstanceOf(Outcome.Applied.class, registry.of(PAYMENTS).execute(deposit, PaymentTransition.CAPTURE));
        }
        assertEquals(BookingStatus.BALANCE, booking.status());
        assertEquals(3, roots.size());
        for (Object root : roots) {
            assertSame(deposit, root);
        }
        assertSame(booking, owns.get(0));
        assertSame(booking, owns.get(1));
        assertEquals(List.of("ClassCastException: Cannot cast " + Payment.class.getName() + " to "
                + Booking.class.getName()), refused);
    }

    @Test
    void theEntityOfAClassRefusesANullAPrimitiveAndAnotherClass() {
        Doc doc = new Doc("1", DRAFT);
        List<String> refused = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("Reads", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            refused.add("guard null: " + describe(thrownBy(() -> ctx.flow().entity(null))));
                            refused.add("guard int: " + describe(thrownBy(() -> ctx.flow().entity(int.class))));
                            refused.add("guard Item: " + describe(thrownBy(() -> ctx.flow().entity(Item.class))));
                            return GuardResult.pass();
                        })
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            refused.add("null: " + describe(thrownBy(() -> ctx.flow().entity(null))));
                            refused.add("void: " + describe(thrownBy(() -> ctx.flow().entity(void.class))));
                            refused.add("Item: " + describe(thrownBy(() -> ctx.flow().entity(Item.class))));
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph)) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(doc, SUBMIT));
        }
        String primitive = "IllegalArgumentException: entity type must be a reference type; use its wrapper class";
        String cast = "ClassCastException: Cannot cast " + Doc.class.getName() + " to " + Item.class.getName();
        assertEquals(List.of("guard null: NullPointerException: type", "guard int: " + primitive,
                "guard Item: " + cast, "null: NullPointerException: type", "void: " + primitive, "Item: " + cast),
                refused);
    }

    /**
     * A change made to the root entity through the flow is immediate and never undone, not even when the call fails:
     * an action that changes the entity {@code flow().entity(Class)} answers and then throws leaves the change on the
     * entity after the abort.
     */
    @Test
    void aChangeMadeThroughTheFlowsEntityStaysWhenTheCallFails() {
        Doc doc = new Doc("1", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Review",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().entity(Doc.class).orElseThrow().setStatus(PUBLISHED);
                            throw new IllegalStateException("reviewer unavailable");
                        }));
        try (MachineRegistry registry = registry(graph)) {
            assertThrows(TransitionAbortedException.class, () -> registry.of(DOCS).execute(doc, SUBMIT));
        }
        assertEquals(PUBLISHED, doc.status());
    }

    /**
     * Spec R13.1: the flow of an action, and every operation of it, runs the action context's own check first: off the
     * thread running process() each throws its WrongThreadException, and once the call ended its
     * StaleContextException. A write refused off the thread stores nothing.
     */
    @Test
    void everyOperationOfAnActionsFlowChecksItsThreadAndItsLifetime() {
        Doc doc = new Doc("1", DRAFT);
        AtomicReference<Optional<TransitionContext<Doc, DocStatus, DocTransition>>> context =
                new AtomicReference<>(Optional.empty());
        AtomicReference<Optional<FlowContext>> captured =
                new AtomicReference<>(Optional.empty());
        AtomicReference<Optional<String>> owner =
                new AtomicReference<>(Optional.empty());
        Map<String, String> offThread = new LinkedHashMap<>();
        List<Object> afterwards = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Review",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            owner.set(Optional.of(Thread.currentThread().getName()));
                            context.set(Optional.of(ctx));
                            FlowContext flow = ctx.flow();
                            captured.set(Optional.of(flow));
                            offThread.put("flow()", describe(thrownOnAnotherThread(ctx::flow)));
                            operations(flow).forEach((String name, Runnable operation) ->
                                    offThread.put(name, describe(thrownOnAnotherThread(operation))));
                            afterwards.add(flow.get(BATCH));
                            afterwards.add(flow.keys());
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph)) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(doc, SUBMIT));
        }
        Map<String, String> stale = new LinkedHashMap<>();
        stale.put("flow()", describe(thrownBy(() -> context.get().orElseThrow().flow())));
        operations(captured.get().orElseThrow()).forEach((String name, Runnable operation) ->
                stale.put(name, describe(thrownBy(operation))));
        List<String> names = List.of("flow()", "get", "keys", "entity", "entity(Class)", "trail", "put", "remove");
        assertEquals(each(names, "WrongThreadException: the context of Doc/DocStatus can only be used from the thread"
                + " running process(): " + owner.get().orElseThrow()), offThread);
        assertEquals(each(names, "StaleContextException: the context of Doc/DocStatus is no longer valid: the"
                + " transaction ended"), stale);
        assertEquals(List.of(Optional.empty(), Set.of()), afterwards);
    }

    /** As for an action, with the guard context's own check, which names test(). */
    @Test
    void everyOperationOfAGuardsFlowChecksItsThreadAndItsLifetime() {
        Doc doc = new Doc("1", DRAFT);
        AtomicReference<Optional<GuardContext<Doc, DocStatus, DocTransition>>> context =
                new AtomicReference<>(Optional.empty());
        AtomicReference<Optional<FlowView>> captured =
                new AtomicReference<>(Optional.empty());
        AtomicReference<Optional<String>> owner =
                new AtomicReference<>(Optional.empty());
        Map<String, String> offThread = new LinkedHashMap<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("Reads",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            owner.set(Optional.of(Thread.currentThread().getName()));
                            context.set(Optional.of(ctx));
                            FlowView flow = ctx.flow();
                            captured.set(Optional.of(flow));
                            offThread.put("flow()", describe(thrownOnAnotherThread(ctx::flow)));
                            operations(flow).forEach((String name, Runnable operation) ->
                                    offThread.put(name, describe(thrownOnAnotherThread(operation))));
                            return GuardResult.pass();
                        }));
        try (MachineRegistry registry = registry(graph)) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(doc, SUBMIT));
        }
        Map<String, String> stale = new LinkedHashMap<>();
        stale.put("flow()", describe(thrownBy(() -> context.get().orElseThrow().flow())));
        operations(captured.get().orElseThrow()).forEach((String name, Runnable operation) ->
                stale.put(name, describe(thrownBy(operation))));
        List<String> names = List.of("flow()", "get", "keys", "entity", "entity(Class)", "trail");
        assertEquals(each(names, "WrongThreadException: the context of Doc/DocStatus can only be used from the thread"
                + " running test(): " + owner.get().orElseThrow()), offThread);
        assertEquals(each(names, "StaleContextException: the context of Doc/DocStatus is no longer valid: the"
                + " transaction ended"), stale);
    }

    /** A guard decides and does not mutate: its flow has no write, and no cast gives it one. */
    @Test
    void aGuardsFlowCannotWriteEvenWhenCast() {
        Doc doc = new Doc("1", DRAFT);
        AtomicReference<Optional<FlowView>> captured =
                new AtomicReference<>(Optional.empty());
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("Reads",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            captured.set(Optional.of(ctx.flow()));
                            return GuardResult.pass();
                        }));
        try (MachineRegistry registry = registry(graph)) {
            registry.of(DOCS).execute(doc, SUBMIT);
        }
        FlowView view = captured.get().orElseThrow();
        assertFalse(view instanceof FlowContext);
        assertThrows(ClassCastException.class, () -> ((FlowContext) view).put(BATCH, "b-1"));
    }

    /**
     * The flow of a context is a handle of its own, equal only to itself: two calls of {@code ctx.flow()} answer two
     * handles over the same flow, and neither equals the other, in a guard as in an action.
     */
    @Test
    void theFlowOfAContextIsEqualOnlyToItself() {
        List<Boolean> equalities = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("Reads", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            FlowView view = ctx.flow();
                            equalities.add(view.equals(view));
                            equalities.add(view.equals(ctx.flow()));
                            return GuardResult.pass();
                        })
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            FlowContext flow = ctx.flow();
                            equalities.add(flow.equals(flow));
                            equalities.add(flow.equals(ctx.flow()));
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph)) {
            registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT);
        }
        assertEquals(List.of(true, false, true, false), equalities);
    }

    /**
     * Every operation runs its context's check before its own argument checks: off the thread, a guard's read with a
     * null key and an action's put of a null key and value throw the context's WrongThreadException, not
     * NullPointerException, and once the call ended, its StaleContextException.
     */
    @Test
    void theContextsCheckRunsBeforeTheArgumentChecks() {
        AtomicReference<Optional<FlowView>> guard =
                new AtomicReference<>(Optional.empty());
        AtomicReference<Optional<FlowContext>> action =
                new AtomicReference<>(Optional.empty());
        AtomicReference<Optional<String>> owner =
                new AtomicReference<>(Optional.empty());
        List<String> refused = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .guard("Reads", (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            owner.set(Optional.of(Thread.currentThread().getName()));
                            guard.set(Optional.of(ctx.flow()));
                            refused.add(describe(thrownOnAnotherThread(() -> guard.get().orElseThrow().get(null))));
                            return GuardResult.pass();
                        })
                        .action("Review", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            action.set(Optional.of(ctx.flow()));
                            refused.add(describe(thrownOnAnotherThread(() ->
                                    action.get().orElseThrow().put(null, null))));
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph)) {
            registry.of(DOCS).execute(new Doc("1", DRAFT), SUBMIT);
        }
        refused.add(describe(thrownBy(() -> guard.get().orElseThrow().get(null))));
        refused.add(describe(thrownBy(() -> action.get().orElseThrow().put(null, null))));
        String stale = "StaleContextException: the context of Doc/DocStatus is no longer valid: the transaction ended";
        assertEquals(List.of(
                "WrongThreadException: the context of Doc/DocStatus can only be used from the thread running test(): "
                        + owner.get().orElseThrow(),
                "WrongThreadException: the context of Doc/DocStatus can only be used from the thread running"
                        + " process(): " + owner.get().orElseThrow(),
                stale, stale), refused);
    }

    /**
     * Review Focus 2: a callback of the stage may run on another thread. A write there throws WrongThreadException,
     * which completes the stage exceptionally and aborts the call with it as cause.
     */
    @Test
    void anAsyncActionThatWritesInACallbackOffItsThreadAbortsTheCall() {
        Doc doc = new Doc("1", DRAFT);
        String owner = Thread.currentThread().getName();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.asyncAction("Fetch",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            FlowContext flow = ctx.flow();
                            CompletableFuture<String> fetched = new CompletableFuture<>();
                            CompletionStage<ActionOutcome> stage = fetched.thenApply((String batch) -> {
                                flow.put(BATCH, batch);
                                return ActionOutcome.ok();
                            });
                            Thread.ofPlatform().start(() -> fetched.complete("b-1"));
                            return stage;
                        }));
        try (MachineRegistry registry = registry(graph, Duration.ofSeconds(5))) {
            TransitionAbortedException error = assertThrows(TransitionAbortedException.class,
                    () -> registry.of(DOCS).execute(doc, SUBMIT));
            WrongThreadException cause = assertInstanceOf(WrongThreadException.class, error.getCause());
            assertEquals("the context of Doc/DocStatus can only be used from the thread running process(): "
                    + owner, cause.getMessage());
        }
        assertEquals(DRAFT, doc.status());
    }

    /** Review Focus 2, routed: a type the edge declares accepts the WrongThreadException, and the route runs. */
    @Test
    void anAsyncActionThatWritesInACallbackOffItsThreadTakesTheRouteADeclaredTypeGives() {
        Doc doc = new Doc("1", DRAFT);
        List<Optional<String>> recovered = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit
                        .asyncAction("Fetch", (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            CompletableFuture<String> fetched = new CompletableFuture<>();
                            CompletionStage<ActionOutcome> stage = fetched.thenApply((String batch) -> {
                                ctx.flow().put(BATCH, batch);
                                return ActionOutcome.ok();
                            });
                            Thread.ofPlatform().start(() -> fetched.complete("b-1"));
                            return stage;
                        })
                        .failsOn(WrongThreadException.class).onFailure(DISCARD),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.action("Archive",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            recovered.add(ctx.flow().get(BATCH));
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph, Duration.ofSeconds(5))) {
            assertEquals(OutcomeCode.FAILURE_ROUTED, registry.of(DOCS).execute(doc, SUBMIT).code());
        }
        assertEquals(List.of(Optional.<String>empty()), recovered);
        assertEquals(ARCHIVED, doc.status());
    }

    /**
     * Review Focus 2, stored: an async action writes before it returns its stage, and that write stays; a write in a
     * callback on another thread is refused and stores nothing, even when the step then ends well and nothing is
     * rolled back.
     */
    @Test
    void anAsyncActionWritesBeforeItReturnsItsStageAndACallbackOffItsThreadStoresNothing() {
        Doc parent = new Doc("1", DRAFT);
        Doc child = new Doc("2", DRAFT);
        List<RuntimeException> refusals = new ArrayList<>();
        List<Object> seen = new ArrayList<>();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.asyncAction("Fetch",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            FlowContext flow = ctx.flow();
                            flow.put(NOTE, "requested");
                            CompletableFuture<String> fetched = new CompletableFuture<>();
                            CompletionStage<ActionOutcome> stage = fetched.thenApply((String batch) -> {
                                try {
                                    flow.put(BATCH, batch);
                                } catch (WrongThreadException refused) {
                                    synchronized (refusals) {
                                        refusals.add(refused);
                                    }
                                }
                                return ActionOutcome.ok();
                            });
                            Thread.ofPlatform().start(() -> fetched.complete("b-1"));
                            return stage;
                        }),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.action("Collect",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.cascade(DOCS, child, SUBMIT);
                            seen.add(ctx.flow().get(NOTE));
                            seen.add(ctx.flow().get(BATCH));
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph, Duration.ofSeconds(5))) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(parent, DISCARD));
        }
        assertEquals(List.of(Optional.of("requested"), Optional.empty()), seen);
        synchronized (refusals) {
            assertEquals(1, refusals.size());
        }
        assertEquals(REVIEW, child.status());
    }

    /**
     * Every put and remove is logged at TRACE, at the depth of the step that wrote it: a key prints as name:Type, and
     * a visible key adds the value, or a fixed placeholder when its toString() throws, and the call goes on.
     */
    @Test
    void eachWriteIsTracedAtTheDepthOfItsStep() {
        Doc parent = new Doc("1", DRAFT);
        Doc child = new Doc("2", DRAFT);
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Review",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(NOTE, "n-2");
                            ctx.flow().put(ODD, new Odd());
                            ctx.flow().remove(NOTE);
                            return ActionOutcome.ok();
                        }),
                (TransitionBuilder<Doc, DocStatus, DocTransition> discard) -> discard.action("Collect",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(BATCH, "b-1");
                            ctx.flow().put(NOTE, "n-1");
                            ctx.flow().remove(BATCH);
                            ctx.cascade(DOCS, child, SUBMIT);
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(parent, DISCARD));
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus DISCARD",
                    "FINER call=A depth=0 resolved Doc/DocStatus#1 DRAFT -DISCARD-> ARCHIVED kind=ACTION",
                    "FINER call=A depth=0 action Collect start mode=sync",
                    "FINER call=A depth=0 flow-put batch:String",
                    "FINER call=A depth=0 flow-put note:String=n-1",
                    "FINER call=A depth=0 flow-remove batch:String",
                    "FINER call=A depth=0 cascade Doc/DocStatus#2 SUBMIT",
                    "FINER call=A depth=1 resolved Doc/DocStatus#2 DRAFT -SUBMIT-> REVIEW kind=ACTION",
                    "FINER call=A depth=1 action Review start mode=sync",
                    "FINER call=A depth=1 flow-put note:String=n-2",
                    "FINER call=A depth=1 flow-put odd:Odd=<toString threw java.lang.IllegalStateException>",
                    "FINER call=A depth=1 flow-remove note:String",
                    "FINER call=A depth=1 action Review OK elapsed=<us>",
                    "FINER call=A depth=0 cascade-result Doc/DocStatus#2 SUBMIT code=APPLIED",
                    "FINER call=A depth=0 action Collect OK elapsed=<us>",
                    "FINER call=A commit-check entities=2 hops=2",
                    "FINER call=A setter Doc/DocStatus#1 DRAFT -> ARCHIVED entries=1",
                    "FINER call=A setter Doc/DocStatus#2 DRAFT -> REVIEW entries=1",
                    "FINE call=A committed Doc/DocStatus#1 DRAFT -DISCARD-> ARCHIVED depth=0",
                    "FINE call=A committed Doc/DocStatus#2 DRAFT -SUBMIT-> REVIEW depth=1",
                    "FINE call=A end code=APPLIED state=ARCHIVED entity=Doc/DocStatus#1 records=2 starts=0"
                            + " reactions=0 aborted=0 elapsed=<us>"), lines(log));
        }
    }

    /** A call that does not log at TRACE builds no line for a write, and never prints a visible value. */
    @Test
    void aWriteBuildsNoLineWithoutTrace() {
        Doc doc = new Doc("1", DRAFT);
        Counted counted = new Counted();
        GraphDefinition<Doc, DocStatus, DocTransition> graph = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.action("Review",
                        (TransitionContext<Doc, DocStatus, DocTransition> ctx) -> {
                            ctx.flow().put(COUNTED, counted);
                            ctx.flow().remove(COUNTED);
                            return ActionOutcome.ok();
                        }));
        try (MachineRegistry registry = registry(graph); LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            assertInstanceOf(Outcome.Applied.class, registry.of(DOCS).execute(doc, SUBMIT));
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINE call=A committed Doc/DocStatus#1 DRAFT -SUBMIT-> REVIEW depth=0",
                    "FINE call=A end code=APPLIED state=REVIEW entity=Doc/DocStatus#1 records=1 starts=0 reactions=0"
                            + " aborted=0 elapsed=<us>"), lines(log));
        }
        assertEquals(0, counted.printed.get());
    }
}
