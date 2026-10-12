package io.github.graphmaquina.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The companions and the set generated for annotated sub-machines, compiled and run. {@code Bookings} binds
 * {@code Payments} in two states, finding the parent through a static lookup in one and an instance lookup in the
 * other; {@code Orders} binds the same {@code Payments} through a property of each entity, and {@code Reviews}, a
 * machine over the orders themselves. The set builds the definition of each child once and hands it to every parent,
 * it treats as asynchronous exactly the machines the registry requires a timeout of, and, built with
 * {@code recordTrail}, it lists the exits and the starts a call runs in that call's trail.
 */
class SubmachineGenerationTest {

    @Test
    void theParentsCompanionTakesEachChildsDefinitionAndBindsIt() {
        Compilation.Result result = Compilation.compileTyped(billing());

        assertTrue(result.success(), result.describe());
        String bookings = result.generated("fx.BookingsRuntime");
        assertTrue(bookings.contains("/** The definition, built from the annotations of {@link Bookings}. One parameter"
                + " per machine it binds as a sub-machine, which takes its definition, then one per class with instance"
                + " members. */"), bookings);
        assertTrue(bookings.contains("public static GraphDefinition<Booking, BookingStatus, BookingTransition> graph("
                + "GraphDefinition<Payment, PaymentStatus, PaymentTransition> paymentsGraph, Ledger ledger)"),
                bookings);
        String chain = " ".repeat(16);
        assertTrue(bookings.contains(chain + ".submachine(BookingStatus.DEPOSIT, paymentsGraph, (Booking booking) ->"
                + " booking.deposit(), (Payment payment) -> BookingLookups.depositOf(payment))\n"
                + chain + "    .exit(PaymentStatus.CAPTURED, BookingTransition.DEPOSIT_PAID)\n"
                + chain + "    .exit(PaymentStatus.DECLINED, BookingTransition.DECLINE)\n"
                + chain + "    .endSubmachine()\n"), bookings);
        assertTrue(bookings.contains(".submachine(BookingStatus.BALANCE, paymentsGraph, (Booking booking) ->"
                + " booking.balance(), (Payment payment) -> ledger.balanceOf(payment))"), bookings);
        String orders = result.generated("fx.OrdersRuntime");
        assertTrue(orders.contains("graph(GraphDefinition<Order, ReviewStatus, ReviewTransition> reviewsGraph,"
                + " GraphDefinition<Payment, PaymentStatus, PaymentTransition> paymentsGraph)"), orders);
        assertTrue(orders.contains(".submachine(OrderStatus.REVIEW, reviewsGraph)\n"), orders);
        assertTrue(orders.contains(".submachine(OrderStatus.PAYING, paymentsGraph, (Order order) -> order.payment(),"
                + " (Payment payment) -> payment.order())"), orders);
        assertTrue(result.generated("fx.PaymentsRuntime").contains(
                "public static GraphDefinition<Payment, PaymentStatus, PaymentTransition> graph()"), orders);
    }

    /** The set builds each child's definition once, before its parents', and passes that very instance around. */
    @Test
    void theSetBuildsEachChildsDefinitionOnceBeforeItsParents() {
        Compilation.Result result = Compilation.compileTyped(billing());

        assertTrue(result.success(), result.describe());
        String set = result.generated("fx.BillingRuntime");
        assertTrue(set.contains("public static Builder builder(Ledger ledger)"), set);
        String constructor = "\n" + " ".repeat(12);
        assertTrue(set.contains(constructor + "GraphDefinition<Payment, PaymentStatus, PaymentTransition> paymentsGraph"
                + " = PaymentsRuntime.graph();" + constructor + "GraphDefinition<Order, ReviewStatus, ReviewTransition>"
                + " reviewsGraph = ReviewsRuntime.graph();" + constructor + "this.bookingsBuilder ="
                + " StateMachines.from(BookingsRuntime.graph(paymentsGraph, ledger))\n"), set);
        assertTrue(set.contains("this.ordersBuilder = StateMachines.from(OrdersRuntime.graph(reviewsGraph,"
                + " paymentsGraph))"), set);
        assertTrue(set.contains("this.paymentsBuilder = StateMachines.from(paymentsGraph)"), set);
        assertTrue(set.contains("this.reviewsBuilder = StateMachines.from(reviewsGraph)"), set);
        assertEquals(1, set.split("PaymentsRuntime\\.graph\\(", -1).length - 1, set);
        assertFalse(set.contains("asyncTimeout"), set);
        String comment = "\n" + " ".repeat(9) + "* ";
        assertTrue(set.contains(comment + "Bounds how deep a cascade may go, the root being at depth 0. The exit of"
                + " a sub-machine counts at its" + comment + "own depth: one level below the step that ended the child"
                + " or, when the child stood in a terminal" + comment + "state while its parent was still running the"
                + " step that entered the bound state, one level below that" + comment + "step. The default is 10, and"
                + " a value below 1 is refused.\n"), set);
    }

    /**
     * Booking a stay enters {@code DEPOSIT}, which starts the deposit; capturing it exits to {@code BALANCE} in the
     * same call, which starts the balance; capturing that one confirms the booking. The deposit finds its booking
     * through the static lookup, the balance through the instance one.
     */
    @Test
    void aBookingRunsBothPaymentsThroughTheirLookups() throws Exception {
        assertEquals("book APPLIED REQUESTED-BOOK->DEPOSIT starts DEPOSIT:PENDING"
                        + " | deposit APPLIED PENDING-CAPTURE->CAPTURED DEPOSIT-DEPOSIT_PAID->BALANCE"
                        + " starts BALANCE:PENDING"
                        + " | balance APPLIED PENDING-CAPTURE->CAPTURED BALANCE-BALANCE_PAID->CONFIRMED starts"
                        + " | CONFIRMED",
                run(billing(), "builder(ledger)", """
                        Payment deposit = new Payment("deposit");
                        Payment balance = new Payment("balance");
                        Booking booking = new Booking("booking", deposit, balance);
                        BookingLookups.register(booking);
                        ledger.register(booking);
                        Outcome<Booking, BookingStatus, BookingTransition> booked = billing.bookings().book(booking);
                        Outcome<Payment, PaymentStatus, PaymentTransition> first = billing.payments().capture(deposit);
                        Outcome<Payment, PaymentStatus, PaymentTransition> second = billing.payments().capture(balance);
                        return "book " + describe(booked) + " | deposit " + describe(first) + " | balance "
                                + describe(second) + " | " + booking.status();
                        """));
    }

    /**
     * Submitting an order starts its review, a machine over the order itself; approving it exits to {@code PAYING},
     * which starts the payment; capturing the payment, which finds its order through a property, settles the order.
     */
    @Test
    void anOrderRunsItsReviewAndItsPaymentThroughProperties() throws Exception {
        assertEquals("submit APPLIED NEW-SUBMIT->REVIEW starts REVIEW:PENDING"
                        + " | approve APPLIED PENDING-APPROVE->APPROVED REVIEW-ACCEPT->PAYING starts PAYING:PENDING"
                        + " | capture APPLIED PENDING-CAPTURE->CAPTURED PAYING-SETTLE->SETTLED starts"
                        + " | SETTLED APPROVED CAPTURED",
                run(billing(), "builder(ledger)", """
                        Payment payment = new Payment("payment");
                        Order order = new Order("order", payment);
                        Outcome<Order, OrderStatus, OrderTransition> submitted = billing.orders().submit(order);
                        Outcome<Order, ReviewStatus, ReviewTransition> approved = billing.reviews().approve(order);
                        Outcome<Payment, PaymentStatus, PaymentTransition> captured =
                                billing.payments().capture(payment);
                        return "submit " + describe(submitted) + " | approve " + describe(approved) + " | capture "
                                + describe(captured) + " | " + order.status() + " " + order.review() + " "
                                + payment.status();
                        """));
    }

    /**
     * A set built with {@code recordTrail(clock)} records the trail of a deposit's capture on that clock: the
     * capture, the exit it runs on its booking and the start of the balance that exit enters, in the order the call
     * took them.
     */
    @Test
    void aSetThatRecordsItsTrailListsTheExitAndTheStartACaptureRuns() throws Exception {
        String noon = "2026-10-10T12:00:00Z";
        assertEquals("PENDING-CAPTURE->CAPTURED@" + noon + " DEPOSIT-DEPOSIT_PAID->BALANCE@" + noon
                        + " BALANCE:PENDING@" + noon,
                run(billing(), "builder(ledger).recordTrail(java.time.InstantSource.fixed(java.time.Instant.parse(\""
                        + noon + "\")))", """
                        Payment deposit = new Payment("deposit");
                        Booking booking = new Booking("booking", deposit, new Payment("balance"));
                        BookingLookups.register(booking);
                        ledger.register(booking);
                        billing.bookings().book(booking);
                        Outcome<Payment, PaymentStatus, PaymentTransition> captured =
                                billing.payments().capture(deposit);
                        StringBuilder text = new StringBuilder();
                        for (io.github.graphmaquina.api.FlowEntry entry : captured.flow().trail()) {
                            if (entry instanceof io.github.graphmaquina.api.FlowEntry.Hop hop) {
                                text.append(' ').append(hop.hop().from()).append('-')
                                        .append(hop.hop().transition()).append("->").append(hop.hop().to());
                            } else if (entry instanceof io.github.graphmaquina.api.FlowEntry.Start start) {
                                text.append(' ').append(start.start().node()).append(':')
                                        .append(start.start().initial());
                            }
                            text.append('@').append(entry.at());
                        }
                        return text.toString().strip();
                        """));
    }

    /** A deposit whose booking was cancelled is detached: its parent is no longer in {@code DEPOSIT}. */
    @Test
    void aPaymentWhoseBookingLeftTheBoundStateIsDetached() throws Exception {
        assertEquals("REJECTED DETACHED no parent holds it in DEPOSIT | CANCELLED PENDING",
                run(billing(), "builder(ledger)", """
                        Payment deposit = new Payment("deposit");
                        Booking booking = new Booking("booking", deposit, new Payment("balance"));
                        BookingLookups.register(booking);
                        ledger.register(booking);
                        billing.bookings().book(booking);
                        billing.bookings().cancel(booking);
                        Outcome<Payment, PaymentStatus, PaymentTransition> captured =
                                billing.payments().capture(deposit);
                        Outcome.Rejected<?, ?, ?> rejected = (Outcome.Rejected<?, ?, ?>) captured;
                        return rejected.status() + " " + rejected.kind() + " " + rejected.reason() + " | "
                                + booking.status() + " " + deposit.status();
                        """));
    }

    /**
     * The exit {@code DEPOSIT_PAID} is asynchronous, so a call of {@code Payments}, synchronous itself, can run it:
     * the set gives {@code Payments} the shared timeout, as the registry requires, and accepts its own override. The
     * implicit work of {@code Reviews} is synchronous all the way, through the exit of its order and the exits of the
     * payment that exit enters, so it gets no timeout, and neither does {@code Orders}.
     */
    @Test
    void theSetTimesOutTheMachinesThatCanRunAnAsynchronousExit() throws Exception {
        Map<String, String> sources = asynchronous();
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result.describe());
        String set = result.generated("fx.BillingRuntime");
        assertTrue(set.contains("public static Builder builder(Duration asyncTimeout, BookingActions bookingActions,"
                + " Ledger ledger)"), set);
        String comment = "\n" + " ".repeat(9) + "* ";
        assertTrue(set.contains(comment + "Overrides the shared timeout for one asynchronous machine: one whose own"
                + " graph has an asynchronous" + comment + "edge, or whose calls can run one implicitly, such as the"
                + " exit of a sub-machine.\n"), set);
        assertTrue(set.contains("bookingsBuilder.asyncTimeout(asyncTimeout);"), set);
        assertTrue(set.contains("paymentsBuilder.asyncTimeout(asyncTimeout);"), set);
        assertFalse(set.contains("ordersBuilder.asyncTimeout(asyncTimeout);"), set);
        assertFalse(set.contains("reviewsBuilder.asyncTimeout(asyncTimeout);"), set);

        assertEquals("PENDING-CAPTURE->CAPTURED DEPOSIT-DEPOSIT_PAID->BALANCE | BALANCE"
                        + " | Reviews is not an asynchronous machine of Billing"
                        + " | Orders is not an asynchronous machine of Billing",
                run(sources, "builder(Duration.ofSeconds(5), new BookingActions(), ledger)"
                                + ".asyncTimeout(Payments.class, Duration.ofSeconds(2))", """
                        Payment deposit = new Payment("deposit");
                        Booking booking = new Booking("booking", deposit, new Payment("balance"));
                        BookingLookups.register(booking);
                        ledger.register(booking);
                        billing.bookings().book(booking);
                        Outcome<Payment, PaymentStatus, PaymentTransition> captured =
                                billing.payments().capture(deposit);
                        StringBuilder refusals = new StringBuilder();
                        List<Class<? extends Machine<?, ?, ?>>> machines = List.of(Reviews.class, Orders.class);
                        for (Class<? extends Machine<?, ?, ?>> machine : machines) {
                            try {
                                BillingRuntime.builder(Duration.ofSeconds(5), new BookingActions(), ledger)
                                        .asyncTimeout(machine, Duration.ofSeconds(1));
                                refusals.append(" | accepted ").append(machine.getSimpleName());
                            } catch (IllegalArgumentException refused) {
                                refusals.append(" | ").append(refused.getMessage());
                            }
                        }
                        return hops(captured).strip() + " | " + booking.status() + refusals;
                        """));
    }

    /**
     * The child reader of an order and the deposit lookup declare a checked exception, which the companion throws on
     * unchanged: entering {@code PAYING} through the exit of the review aborts with what the reader threw, and so does
     * the binding check of a payment, in which the lookup runs. Nothing is written.
     */
    @Test
    void aChildReaderOrALookupThatDeclaresACheckedExceptionAbortsTheCallCarryingIt() throws Exception {
        Map<String, String> sources = billing();
        sources.put("fx.Order", sources.get("fx.Order").replace(
                "    @GraphProperty public Optional<Payment> payment() { return Optional.of(payment); }",
                "    public static final java.io.IOException FAILURE ="
                        + " new java.io.IOException(\"the store is down\");\n"
                        + "    @GraphProperty public Optional<Payment> payment() throws java.io.IOException {"
                        + " throw FAILURE; }"));
        sources.put("fx.BookingLookups", sources.get("fx.BookingLookups").replace(
                "    @GraphLookup public static Optional<Booking> depositOf(Payment payment) {\n"
                        + "        return Optional.ofNullable(DEPOSITS.get(payment.id()));",
                "    public static final java.io.IOException FAILURE ="
                        + " new java.io.IOException(\"the lookup is down\");\n"
                        + "    @GraphLookup public static Optional<Booking> depositOf(Payment payment)"
                        + " throws java.io.IOException {\n        throw FAILURE;"));

        assertEquals("approve aborted by it, REVIEW PENDING | capture aborted by it, PENDING",
                run(sources, "builder(ledger)", """
                        Payment payment = new Payment("payment");
                        Order order = new Order("order", payment);
                        billing.orders().submit(order);
                        String approve;
                        try {
                            billing.reviews().approve(order);
                            approve = "applied";
                        } catch (TransitionAbortedException aborted) {
                            approve = aborted.getCause() == Order.FAILURE ? "aborted by it" : aborted.toString();
                        }
                        String capture;
                        try {
                            billing.payments().capture(payment);
                            capture = "applied";
                        } catch (TransitionAbortedException aborted) {
                            capture = aborted.getCause() == BookingLookups.FAILURE ? "aborted by it"
                                    : aborted.toString();
                        }
                        return "approve " + approve + ", " + order.status() + " " + order.review() + " | capture "
                                + capture + ", " + payment.status();
                        """));
    }

    /**
     * A lookup that returns null instead of an Optional breaks its contract: the binding check of the payment aborts
     * with the same text as a parentOf of the DSL, naming the binding, and nothing is written.
     */
    @Test
    void aLookupThatReturnsNullAbortsTheCallNamingTheBinding() throws Exception {
        Map<String, String> sources = billing();
        sources.put("fx.BookingLookups", sources.get("fx.BookingLookups").replace(
                "        return Optional.ofNullable(DEPOSITS.get(payment.id()));",
                "        return null;"));

        assertEquals("transition aborted in Payment/PaymentStatus PENDING -CAPTURE-> ?: the parentOf of"
                        + " Booking/BookingStatus.DEPOSIT returned null | PENDING",
                run(sources, "builder(ledger)", """
                        Payment payment = new Payment("payment");
                        try {
                            billing.payments().capture(payment);
                            return "applied";
                        } catch (TransitionAbortedException aborted) {
                            return aborted.getMessage() + " | " + payment.status();
                        }
                        """));
    }

    /** A graph method of the facade with the parameters of the companion's own is refused as SG504. */
    @Test
    void sg504AGraphMethodWithTheDefinitionsTheCompanionTakes() {
        Map<String, String> sources = billing();
        sources.put("fx.Bookings", sources.get("fx.Bookings").replace(
                "    Outcome<Booking, BookingStatus, BookingTransition> book(Booking booking);\n",
                "    Outcome<Booking, BookingStatus, BookingTransition> book(Booking booking);\n"
                        + "    default String graph(io.github.graphmaquina.api.GraphDefinition<Payment, PaymentStatus,"
                        + " PaymentTransition> payments, Ledger ledger) { return \"bookings\"; }\n"
                        + "    default String graph(Ledger ledger) { return \"overload\"; }\n"));
        sources.put("fx.Orders", sources.get("fx.Orders").replace(
                "    Outcome<Order, OrderStatus, OrderTransition> submit(Order order);\n",
                "    Outcome<Order, OrderStatus, OrderTransition> submit(Order order);\n"
                        + "    default String graph(io.github.graphmaquina.api.GraphDefinition<?, ?, ?> reviews,"
                        + " io.github.graphmaquina.api.GraphDefinition<?, ?, ?> payments) { return \"orders\"; }\n"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(List.of("[SG504] graph(GraphDefinition, Ledger) is the static graph(GraphDefinition, Ledger)"
                        + " the generated BookingsRuntime declares, which takes the definition of each machine it binds"
                        + " as a sub-machine, then every class with instance members the machine binds: give the method"
                        + " another name",
                "[SG504] graph(GraphDefinition, GraphDefinition) is the static graph(GraphDefinition,"
                        + " GraphDefinition) the generated OrdersRuntime declares, which takes the definition of each"
                        + " machine it binds as a sub-machine: give the method another name"), result.errors(),
                result.describe());
    }

    /** A lookup on an instance host is a dependency of the set's builder, so its signature takes that host. */
    @Test
    void sg510ABuilderMethodWithTheLookupHostTheCompanionTakes() {
        Map<String, String> sources = billing();
        sources.put("fx.Billing", sources.get("fx.Billing").replace("    Reviews reviews();\n",
                "    Reviews reviews();\n    default String builder(Ledger ledger) { return \"billing\"; }\n"));

        Compilation.Result result = Compilation.compileTyped(sources);

        assertEquals(List.of("[SG510] builder(Ledger) is the static builder(Ledger) the generated BillingRuntime"
                + " declares, which takes every class with instance members the set's machines use: give the method"
                + " another name"), result.errors(), result.describe());
    }

    /** A caller's own entry starts a terminal-initial grandchild and must bound its asynchronous exit. */
    @Test
    void anOwnEntryInstallsAndOverridesTheInitialDescendantsTimeout() throws Exception {
        assertEquals("ACTION NONE TimeoutException OPEN WAIT DONE OPEN 0 false false 1",
                runInitialDescendants(false, true));
    }

    /** An implicit parent exit that enters another binding must also bound its terminal-initial descendants. */
    @Test
    void anImplicitEnteredBindingInstallsAndOverridesTheInitialDescendantsTimeout() throws Exception {
        assertEquals("ACTION NONE TimeoutException OPEN WAIT DONE OPEN 0 false false 1",
                runInitialDescendants(true, true));
    }

    /** Unrelated asynchronous child work behind a nonterminal initial state does not make the root asynchronous. */
    @Test
    void aNonterminalInitialDescendantDoesNotInstallAnUnrelatedTimeout() throws Exception {
        Map<String, String> sources = initialDescendants(false);
        sources.put("fx.Scenario", descendantScenario("""
                    try {
                        FlowsRuntime.builder(Duration.ofSeconds(5), actions)
                                .asyncTimeout(Root.class, Duration.ofMillis(30));
                        return "accepted";
                    } catch (IllegalArgumentException refused) {
                        try (Flows flows = FlowsRuntime.builder(Duration.ofSeconds(5), actions).build()) {
                            Flow entity = new Flow();
                            Outcome<Flow, RootState, RootId> applied = flows.root().async(
                                    () -> flows.root().enter(entity)).get(2, TimeUnit.SECONDS);
                            return refused.getMessage() + " | " + applied.status() + " " + entity.root()
                                    + " " + entity.mid() + " " + entity.leaf() + " " + actions.calls;
                        }
                    }
                    """));
        assertEquals("Root is not an asynchronous machine of Flows | APPLIED BOUND WAIT OPEN 0",
                execute(sources));
    }

    /** Qualified facade identity keeps equal display labels from hiding the second parent's asynchronous exit. */
    @Test
    void equalSimpleParentLabelsKeepTheirDistinctQualifiedAsynchronousExits() throws Exception {
        Map<String, String> sources = qualifiedParents();
        assertEquals("true APPLIED DONE DONE 1", execute(sources));
    }

    /** Runs the generated root or starter through a finite root timeout, with a bounded outer wait. */
    private static Object runInitialDescendants(boolean implicit, boolean terminal) throws Exception {
        Map<String, String> sources = initialDescendants(terminal);
        String caller = implicit ? "Starter" : "Root";
        String accessor = implicit ? "starter" : "root";
        String operation = implicit ? "finish" : "enter";
        sources.put("fx.Scenario", descendantScenario("""
                    try (Flows defaults = FlowsRuntime.builder(Duration.ofSeconds(5), actions).build()) {
                        defaults.root().definition();
                    }
                    try (Flows flows = FlowsRuntime.builder(Duration.ofSeconds(5), actions)
                            .asyncTimeout(%s.class, Duration.ofMillis(30)).build()) {
                        Flow entity = new Flow();
                        try {
                            flows.%s().async(() -> flows.%s().%s(entity)).get(2, TimeUnit.SECONDS);
                            return "applied";
                        } catch (ExecutionException failed) {
                            TransitionAbortedException aborted = (TransitionAbortedException) failed.getCause();
                            return aborted.phase() + " " + aborted.statesWritten() + " "
                                    + aborted.getCause().getClass().getSimpleName() + " " + entity.root() + " "
                                    + entity.mid() + " " + entity.leaf() + " " + entity.starter() + " " + entity.writes
                                    + " " + actions.pending.isDone() + " " + actions.pending.isCancelled()
                                    + " " + actions.calls;
                        } finally {
                            actions.pending.complete(ActionOutcome.ok());
                        }
                    }
                    """.formatted(caller, accessor, accessor, operation)));
        return execute(sources);
    }

    /** Compiles and executes a fixture whose scenario uses bounded waits for every unfinished operation. */
    private static Object execute(Map<String, String> sources) throws Exception {
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result.describe());
        return result.load("fx.Scenario").getMethod("run").invoke(null);
    }

    /** Source of the bounded initial-descendant scenario, with a fresh unresolved action stage. */
    private static String descendantScenario(String body) {
        return """
                package fx;
                import io.github.graphmaquina.api.*;
                import io.github.graphmaquina.error.TransitionAbortedException;
                import java.time.Duration;
                import java.util.concurrent.ExecutionException;
                import java.util.concurrent.TimeUnit;
                /** Executes the generated initial-descendant fixture with bounded waits. */
                public final class Scenario {
                    public static Object run() throws Exception {
                        Actions actions = new Actions();
                %s
                    }
                }
                """.formatted(body.indent(8).stripTrailing());
    }

    /** A synchronous root and starter that can reach the intermediate child's asynchronous initial-descendant exit. */
    private static Map<String, String> initialDescendants(boolean terminal) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String machine : List.of("Root", "Mid", "Leaf", "Starter")) {
            String states = machine.equals("Root") ? "OPEN, BOUND, DONE"
                    : machine.equals("Mid") ? "WAIT, DONE" : "OPEN, DONE";
            String ids = machine.equals("Root") ? "ENTER, ACCEPT, FINISH" : "FINISH";
            sources.put("fx." + machine + "State", "package fx;\n/** States of the descendant fixture. */\npublic enum "
                    + machine + "State { " + states + " }\n");
            sources.put("fx." + machine + "Id", "package fx;\n/** Identifiers of the descendant fixture. */\npublic enum "
                    + machine + "Id { " + ids + " }\n");
        }
        sources.put("fx.Flow", """
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                /** Shared entity with independently writable states for the descendant fixture. */
                public final class Flow {
                    @GraphProperty private RootState root = RootState.OPEN;
                    @GraphProperty private MidState mid = MidState.WAIT;
                    @GraphProperty private LeafState leaf = LeafState.OPEN;
                    @GraphProperty private StarterState starter = StarterState.OPEN;
                    public int writes;
                    @GraphProperty public String id() { return "flow"; }
                    public RootState root() { return root; }
                    public void root(RootState state) { root = state; writes++; }
                    public MidState mid() { return mid; }
                    public void mid(MidState state) { mid = state; writes++; }
                    public LeafState leaf() { return leaf; }
                    public void leaf(LeafState state) { leaf = state; writes++; }
                    public StarterState starter() { return starter; }
                    public void starter(StarterState state) { starter = state; writes++; }
                }
                """);
        sources.put("fx.Actions", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.*;
                import java.util.concurrent.*;
                /** Unresolved descendant action whose stage remains owned by the application. */
                public final class Actions {
                    public final CompletableFuture<ActionOutcome> pending = new CompletableFuture<>();
                    public int calls;
                    @GraphAction public CompletionStage<ActionOutcome> waitForExit(
                            TransitionContext<Flow, MidState, MidId> context) {
                        calls++;
                        return pending;
                    }
                }
                """);
        sources.put("fx.Root", """
                package fx;
                import io.github.graphmaquina.annotation.*;
                import io.github.graphmaquina.api.*;
                import static fx.RootState.*;
                import static fx.RootId.*;
                /** Root whose own entry and implicit starter exit start the intermediate child. */
                @GenerateGraphApi
                @GraphPeers({Mid.class, Starter.class})
                @RootGraph.Definition(initial = OPEN, terminals = DONE, state = FlowRefs.Root.class,
                    id = FlowRefs.Id.class)
                @RootGraph.StatePolicy(state = OPEN, otherwise = "enter or finish the starter")
                @RootGraph.StatePolicy(state = BOUND, otherwise = "the child decides")
                @RootGraph.Transition(id = ACCEPT, from = OPEN, to = BOUND)
                @RootGraph.Transition(id = FINISH, from = BOUND, to = DONE)
                @RootStarter.Submachine(state = OPEN,
                    exits = @RootStarter.Exit(on = StarterState.DONE, transition = ACCEPT))
                @RootMid.Submachine(state = BOUND,
                    exits = @RootMid.Exit(on = MidState.DONE, transition = FINISH))
                public interface Root extends Machine<Flow, RootState, RootId> {
                    @RootGraph.Transition(id = ENTER, from = OPEN, to = BOUND)
                    Outcome<Flow, RootState, RootId> enter(Flow entity);
                }
                """);
        sources.put("fx.Mid", """
                package fx;
                import io.github.graphmaquina.annotation.*;
                import io.github.graphmaquina.api.*;
                import static fx.MidState.*;
                import static fx.MidId.*;
                /** Intermediate child whose exit is selected by its terminal-initial leaf. */
                @GenerateGraphApi
                @GraphPeers(Leaf.class)
                @MidGraph.Definition(initial = WAIT, terminals = DONE, state = FlowRefs.Mid.class,
                    id = FlowRefs.Id.class)
                @MidGraph.StatePolicy(state = WAIT, otherwise = "the leaf decides")
                @MidGraph.Transition(id = FINISH, from = WAIT, to = DONE, action = ActionsRefs.WaitForExit.class)
                @MidLeaf.Submachine(state = WAIT,
                    exits = @MidLeaf.Exit(on = LeafState.DONE, transition = FINISH))
                public interface Mid extends Machine<Flow, MidState, MidId> { }
                """);
        for (String machine : List.of("Leaf", "Starter")) {
            String initial = machine.equals("Leaf") && terminal ? "DONE" : "OPEN";
            sources.put("fx." + machine, """
                    package fx;
                    import io.github.graphmaquina.annotation.*;
                    import io.github.graphmaquina.api.*;
                    import static fx.%1$sState.*;
                    import static fx.%1$sId.*;
                    /** Finite child of the initial-descendant fixture. */
                    @GenerateGraphApi
                    @%1$sGraph.Definition(initial = %2$s, terminals = DONE, state = FlowRefs.%1$s.class,
                        id = FlowRefs.Id.class)
                    @%1$sGraph.StatePolicy(state = OPEN, otherwise = "finish")
                    public interface %1$s extends Machine<Flow, %1$sState, %1$sId> {
                        @%1$sGraph.Transition(id = FINISH, from = OPEN, to = DONE)
                        Outcome<Flow, %1$sState, %1$sId> finish(Flow entity);
                    }
                    """.formatted(machine, initial));
        }
        if (terminal) {
            sources.put("fx.LeafState", "package fx;\n/** Terminal-initial leaf state. */\n"
                    + "public enum LeafState { DONE }\n");
            sources.put("fx.Flow", sources.get("fx.Flow").replace("LeafState.OPEN", "LeafState.DONE"));
            sources.put("fx.Leaf", sources.get("fx.Leaf")
                    .replace("@LeafGraph.StatePolicy(state = OPEN, otherwise = \"finish\")\n", "")
                    .replace("    @LeafGraph.Transition(id = FINISH, from = OPEN, to = DONE)\n"
                            + "    Outcome<Flow, LeafState, LeafId> finish(Flow entity);\n", ""));
        }
        sources.put("fx.Flows", """
                package fx;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                /** Set deliberately declaring parents before children. */
                @StateGraphs
                public interface Flows extends Machines {
                    Root root();
                    Mid mid();
                    Leaf leaf();
                    Starter starter();
                }
                """);
        return sources;
    }

    /** Two distinct qualified parents have equal simple entity/state/id names but distinct exit semantics. */
    private static Map<String, String> qualifiedParents() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.ChildState", "package fx;\n/** Child states. */\npublic enum ChildState { OPEN, DONE }\n");
        sources.put("fx.ChildId", "package fx;\n/** Child identifiers. */\npublic enum ChildId { FINISH }\n");
        sources.put("fx.Child", """
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                import java.util.Optional;
                /** Child claimed only by the second qualified parent. */
                public final class Child {
                    @GraphProperty private ChildState state = ChildState.OPEN;
                    public Optional<fx.a.Entity> first = Optional.empty();
                    public Optional<fx.b.Entity> second = Optional.empty();
                    @GraphProperty public String id() { return "child"; }
                    public ChildState state() { return state; }
                    public void state(ChildState value) { state = value; }
                }
                """);
        sources.put("fx.Children", """
                package fx;
                import io.github.graphmaquina.annotation.*;
                import io.github.graphmaquina.api.*;
                import static fx.ChildState.*;
                import static fx.ChildId.*;
                /** Shared synchronous child of two distinct qualified parents. */
                @GenerateGraphApi
                @ChildrenGraph.Definition(initial = OPEN, terminals = DONE, state = ChildRefs.State.class,
                    id = ChildRefs.Id.class)
                @ChildrenGraph.StatePolicy(state = OPEN, otherwise = "finish")
                public interface Children extends Machine<Child, ChildState, ChildId> {
                    @ChildrenGraph.Transition(id = FINISH, from = OPEN, to = DONE)
                    Outcome<Child, ChildState, ChildId> finish(Child child);
                }
                """);
        for (String pkg : List.of("a", "b")) {
            sources.put("fx." + pkg + ".State", "package fx." + pkg
                    + ";\n/** Parent states with equal simple names. */\npublic enum State { BOUND, DONE }\n");
            sources.put("fx." + pkg + ".Id", "package fx." + pkg
                    + ";\n/** Parent identifiers with equal simple names. */\npublic enum Id { FINISH }\n");
            sources.put("fx." + pkg + ".Entity", """
                    package fx.%s;
                    import io.github.graphmaquina.annotation.GraphProperty;
                    import java.util.Optional;
                    /** Parent entity with the same simple name in each package. */
                    public final class Entity {
                        @GraphProperty private State state = State.BOUND;
                        private final fx.Child child;
                        public Entity(fx.Child child) { this.child = child; }
                        @GraphProperty public String id() { return "parent"; }
                        public State state() { return state; }
                        public void state(State value) { state = value; }
                        @GraphProperty public Optional<fx.Child> child() { return Optional.of(child); }
                    }
                    """.formatted(pkg));
            sources.put("fx." + pkg + ".Lookups", """
                    package fx.%s;
                    import io.github.graphmaquina.annotation.GraphLookup;
                    import java.util.Optional;
                    /** Qualified inverse lookup for one parent's binding. */
                    public final class Lookups {
                        @GraphLookup public static Optional<Entity> parent(fx.Child child) { return child.%s; }
                    }
                    """.formatted(pkg, pkg.equals("a") ? "first" : "second"));
            sources.put("fx." + pkg + ".Parents", """
                    package fx.%s;
                    import fx.Children;
                    import io.github.graphmaquina.annotation.*;
                    import io.github.graphmaquina.api.*;
                    import static fx.%s.State.*;
                    import static fx.%s.Id.*;
                    /** Qualified parent whose shared label must not define semantic edge identity. */
                    @GenerateGraphApi
                    @GraphPeers(Children.class)
                    @ParentsGraph.Definition(initial = BOUND, terminals = DONE, state = EntityRefs.State.class,
                        id = EntityRefs.Id.class)
                    @ParentsGraph.StatePolicy(state = BOUND, otherwise = "the child decides")
                    @ParentsGraph.Transition(id = FINISH, from = BOUND, to = DONE%s)
                    @ParentsChildren.Submachine(state = BOUND, child = EntityRefs.Child.class,
                        parentLookup = LookupsRefs.Parent.class,
                        exits = @ParentsChildren.Exit(on = fx.ChildState.DONE, transition = FINISH))
                    public interface Parents extends Machine<Entity, State, Id> { }
                    """.formatted(pkg, pkg, pkg, pkg.equals("b") ? ", action = fx.ActionsRefs.Finish.class" : ""));
        }
        sources.put("fx.Actions", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.*;
                import java.util.concurrent.*;
                /** Completed asynchronous exit of only the second qualified parent. */
                public final class Actions {
                    public int calls;
                    @GraphAction public CompletionStage<ActionOutcome> finish(
                            TransitionContext<fx.b.Entity, fx.b.State, fx.b.Id> context) {
                        calls++;
                        return CompletableFuture.completedFuture(ActionOutcome.ok());
                    }
                }
                """);
        sources.put("fx.Flows", """
                package fx;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                /** Set whose first equal-label exit is synchronous and second is asynchronous. */
                @StateGraphs
                public interface Flows extends Machines {
                    fx.a.Parents first();
                    fx.b.Parents second();
                    Children children();
                }
                """);
        sources.put("fx.Scenario", """
                package fx;
                import io.github.graphmaquina.api.*;
                import java.time.Duration;
                import java.util.Optional;
                import java.util.concurrent.TimeUnit;
                /** Executes the second qualified parent's asynchronous exit through its synchronous child. */
                public final class Scenario {
                    public static Object run() throws Exception {
                        Actions actions = new Actions();
                        try (Flows flows = FlowsRuntime.builder(Duration.ofSeconds(5), actions)
                                .asyncTimeout(Children.class, Duration.ofSeconds(1)).build()) {
                            Child child = new Child();
                            fx.b.Entity parent = new fx.b.Entity(child);
                            child.second = Optional.of(parent);
                            Outcome<Child, ChildState, ChildId> outcome = flows.children().async(
                                    () -> flows.children().finish(child)).get(2, TimeUnit.SECONDS);
                            boolean equalLabels = flows.first().definition().key().toString().equals(
                                    flows.second().definition().key().toString());
                            return equalLabels + " " + outcome.status() + " " + child.state() + " "
                                    + parent.state() + " " + actions.calls;
                        }
                    }
                }
                """);
        return sources;
    }

    /**
     * Compiles {@code sources} with a scenario whose {@code run()} builds the set with the call {@code builder} on
     * {@code BillingRuntime}, which may read a new {@code ledger}, runs {@code body} and returns what it returns.
     */
    private static Object run(Map<String, String> sources, String builder, String body) throws Exception {
        sources.put("fx.Scenario", """
                package fx;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import io.github.graphmaquina.api.SubmachineStart;
                import io.github.graphmaquina.api.TransitionRecord;
                import io.github.graphmaquina.error.TransitionAbortedException;
                import java.time.Duration;
                import java.util.List;
                /** Scenario of the billing fixture. */
                public final class Scenario {
                    public static Object run() {
                        Ledger ledger = new Ledger();
                        try (Billing billing = BillingRuntime.%s.build()) {
                %s
                        }
                    }
                    static String describe(Outcome<?, ?, ?> outcome) {
                        return outcome.status() + hops(outcome) + " starts" + starts(outcome);
                    }
                    static String hops(Outcome<?, ?, ?> outcome) {
                        StringBuilder text = new StringBuilder();
                        for (TransitionRecord<?, ?, ?> hop : outcome.records()) {
                            text.append(' ').append(hop.from()).append('-').append(hop.transition()).append("->")
                                    .append(hop.to());
                        }
                        return text.toString();
                    }
                    static String starts(Outcome<?, ?, ?> outcome) {
                        StringBuilder text = new StringBuilder();
                        for (SubmachineStart<?, ?, ?> start : outcome.starts()) {
                            text.append(' ').append(start.node()).append(':').append(start.initial());
                        }
                        return text.toString();
                    }
                }
                """.formatted(builder, body.indent(12).stripTrailing()));
        Compilation.Result result = Compilation.compileTyped(sources);
        assertTrue(result.success(), result.describe());
        return result.load("fx.Scenario").getMethod("run").invoke(null);
    }

    /**
     * {@link #billing()} whose exit {@code DEPOSIT_PAID} runs an asynchronous action of {@code BookingActions}, an
     * instance host.
     */
    private static Map<String, String> asynchronous() {
        Map<String, String> sources = billing();
        sources.put("fx.BookingActions", """
                package fx;
                import io.github.graphmaquina.annotation.GraphAction;
                import io.github.graphmaquina.api.ActionOutcome;
                import io.github.graphmaquina.api.TransitionContext;
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.CompletionStage;
                /** BookingActions of the billing fixture. */
                public final class BookingActions {
                    @GraphAction public CompletionStage<ActionOutcome> confirm(
                            TransitionContext<Booking, BookingStatus, BookingTransition> context) {
                        return CompletableFuture.supplyAsync(ActionOutcome::ok);
                    }
                }
                """);
        sources.put("fx.Bookings", sources.get("fx.Bookings").replace(
                "@BookingsGraph.Transition(id = DEPOSIT_PAID, from = DEPOSIT, to = BALANCE)",
                "@BookingsGraph.Transition(id = DEPOSIT_PAID, from = DEPOSIT, to = BALANCE,"
                        + " action = BookingActionsRefs.Confirm.class)"));
        return sources;
    }

    /**
     * Four machines in package {@code fx} and the set {@code Billing} that holds them, the children declared after
     * their parents.
     */
    static Map<String, String> billing() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fx.PaymentStatus", "package fx;\n/** PaymentStatus of the billing fixture. */\npublic enum PaymentStatus { PENDING, CAPTURED, DECLINED }\n");
        sources.put("fx.PaymentTransition", "package fx;\n/** PaymentTransition of the billing fixture. */\npublic enum PaymentTransition { CAPTURE, DECLINE }\n");
        sources.put("fx.BookingStatus",
                "package fx;\n/** BookingStatus of the billing fixture. */\npublic enum BookingStatus { REQUESTED, DEPOSIT, BALANCE, CONFIRMED, CANCELLED }\n");
        sources.put("fx.BookingTransition",
                "package fx;\n/** BookingTransition of the billing fixture. */\npublic enum BookingTransition { BOOK, DEPOSIT_PAID, BALANCE_PAID, DECLINE, CANCEL }\n");
        sources.put("fx.OrderStatus",
                "package fx;\n/** OrderStatus of the billing fixture. */\npublic enum OrderStatus { NEW, REVIEW, PAYING, SETTLED, UNPAID }\n");
        sources.put("fx.OrderTransition",
                "package fx;\n/** OrderTransition of the billing fixture. */\npublic enum OrderTransition { SUBMIT, ACCEPT, SETTLE, FAIL }\n");
        sources.put("fx.ReviewStatus", "package fx;\n/** ReviewStatus of the billing fixture. */\npublic enum ReviewStatus { PENDING, APPROVED }\n");
        sources.put("fx.ReviewTransition", "package fx;\n/** ReviewTransition of the billing fixture. */\npublic enum ReviewTransition { APPROVE }\n");
        sources.put("fx.Payment", """
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                import java.util.Optional;
                /** Payment of the billing fixture. */
                public final class Payment {
                    private final String id;
                    @GraphProperty private PaymentStatus status = PaymentStatus.PENDING;
                    private Optional<Order> order = Optional.empty();
                    public Payment(String id) { this.id = id; }
                    @GraphProperty public String id() { return id; }
                    public PaymentStatus status() { return status; }
                    public void status(PaymentStatus value) { status = value; }
                    @GraphProperty public Optional<Order> order() { return order; }
                    void attach(Order value) { order = Optional.of(value); }
                }
                """);
        sources.put("fx.Booking", """
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                import java.util.Optional;
                /** Booking of the billing fixture. */
                public final class Booking {
                    private final String id;
                    @GraphProperty private BookingStatus status = BookingStatus.REQUESTED;
                    private final Payment deposit;
                    private final Payment balance;
                    public Booking(String id, Payment deposit, Payment balance) {
                        this.id = id;
                        this.deposit = deposit;
                        this.balance = balance;
                    }
                    @GraphProperty public String id() { return id; }
                    public BookingStatus status() { return status; }
                    public void status(BookingStatus value) { status = value; }
                    @GraphProperty public Optional<Payment> deposit() { return Optional.of(deposit); }
                    @GraphProperty public Optional<Payment> balance() { return Optional.of(balance); }
                }
                """);
        sources.put("fx.Order", """
                package fx;
                import io.github.graphmaquina.annotation.GraphProperty;
                import java.util.Optional;
                /** Order of the billing fixture. */
                public final class Order {
                    private final String id;
                    @GraphProperty private OrderStatus status = OrderStatus.NEW;
                    @GraphProperty private ReviewStatus review = ReviewStatus.PENDING;
                    private final Payment payment;
                    public Order(String id, Payment payment) {
                        this.id = id;
                        this.payment = payment;
                        payment.attach(this);
                    }
                    @GraphProperty public String id() { return id; }
                    public OrderStatus status() { return status; }
                    public void status(OrderStatus value) { status = value; }
                    public ReviewStatus review() { return review; }
                    public void review(ReviewStatus value) { review = value; }
                    @GraphProperty public Optional<Payment> payment() { return Optional.of(payment); }
                }
                """);
        sources.put("fx.BookingLookups", """
                package fx;
                import io.github.graphmaquina.annotation.GraphLookup;
                import java.util.HashMap;
                import java.util.Map;
                import java.util.Optional;
                /** BookingLookups of the billing fixture. */
                public final class BookingLookups {
                    private static final Map<String, Booking> DEPOSITS = new HashMap<>();
                    public static void register(Booking booking) {
                        DEPOSITS.put(booking.deposit().orElseThrow().id(), booking);
                    }
                    @GraphLookup public static Optional<Booking> depositOf(Payment payment) {
                        return Optional.ofNullable(DEPOSITS.get(payment.id()));
                    }
                }
                """);
        sources.put("fx.Ledger", """
                package fx;
                import io.github.graphmaquina.annotation.GraphLookup;
                import java.util.HashMap;
                import java.util.Map;
                import java.util.Optional;
                /** Ledger of the billing fixture. */
                public final class Ledger {
                    private final Map<String, Booking> balances = new HashMap<>();
                    public void register(Booking booking) {
                        balances.put(booking.balance().orElseThrow().id(), booking);
                    }
                    @GraphLookup public Optional<Booking> balanceOf(Payment payment) {
                        return Optional.ofNullable(balances.get(payment.id()));
                    }
                }
                """);
        sources.put("fx.Payments", """
                package fx;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import static fx.PaymentStatus.*;
                import static fx.PaymentTransition.*;
                /** Machine of the billing fixture. */
                @GenerateGraphApi
                @PaymentsGraph.Definition(initial = PENDING, terminals = {CAPTURED, DECLINED},
                    state = PaymentRefs.Status.class, id = PaymentRefs.Id.class)
                @PaymentsGraph.StatePolicy(state = PENDING, otherwise = "a payment is captured or declined")
                public interface Payments extends Machine<Payment, PaymentStatus, PaymentTransition> {
                    @PaymentsGraph.Transition(id = CAPTURE, from = PENDING, to = CAPTURED)
                    Outcome<Payment, PaymentStatus, PaymentTransition> capture(Payment payment);
                    @PaymentsGraph.Transition(id = DECLINE, from = PENDING, to = DECLINED)
                    Outcome<Payment, PaymentStatus, PaymentTransition> decline(Payment payment);
                }
                """);
        sources.put("fx.Bookings", """
                package fx;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.annotation.GraphPeers;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import static fx.BookingStatus.*;
                import static fx.BookingTransition.*;
                /** Machine of the billing fixture. */
                @GenerateGraphApi
                @GraphPeers(Payments.class)
                @BookingsGraph.Definition(initial = REQUESTED, terminals = {CONFIRMED, CANCELLED},
                    state = BookingRefs.Status.class, id = BookingRefs.Id.class)
                @BookingsGraph.StatePolicy(state = REQUESTED, otherwise = "a booking is requested first")
                @BookingsGraph.StatePolicy(state = DEPOSIT, otherwise = "the deposit decides")
                @BookingsGraph.StatePolicy(state = BALANCE, otherwise = "the balance decides")
                @BookingsGraph.Transition(id = DEPOSIT_PAID, from = DEPOSIT, to = BALANCE)
                @BookingsGraph.Transition(id = BALANCE_PAID, from = BALANCE, to = CONFIRMED)
                @BookingsGraph.Transition(id = DECLINE, from = {DEPOSIT, BALANCE}, to = CANCELLED)
                @BookingsPayments.Submachine(state = DEPOSIT, child = BookingRefs.Deposit.class,
                    parentLookup = BookingLookupsRefs.DepositOf.class,
                    exits = {@BookingsPayments.Exit(on = PaymentStatus.CAPTURED, transition = DEPOSIT_PAID),
                             @BookingsPayments.Exit(on = PaymentStatus.DECLINED, transition = DECLINE)})
                @BookingsPayments.Submachine(state = BALANCE, child = BookingRefs.Balance.class,
                    parentLookup = LedgerRefs.BalanceOf.class,
                    exits = {@BookingsPayments.Exit(on = PaymentStatus.CAPTURED, transition = BALANCE_PAID),
                             @BookingsPayments.Exit(on = PaymentStatus.DECLINED, transition = DECLINE)})
                public interface Bookings extends Machine<Booking, BookingStatus, BookingTransition> {
                    @BookingsGraph.Transition(id = BOOK, from = REQUESTED, to = DEPOSIT)
                    Outcome<Booking, BookingStatus, BookingTransition> book(Booking booking);
                    @BookingsGraph.Transition(id = CANCEL, from = {DEPOSIT, BALANCE}, to = CANCELLED)
                    Outcome<Booking, BookingStatus, BookingTransition> cancel(Booking booking);
                }
                """);
        sources.put("fx.Reviews", """
                package fx;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import static fx.ReviewStatus.*;
                import static fx.ReviewTransition.*;
                /** Machine of the billing fixture. */
                @GenerateGraphApi
                @ReviewsGraph.Definition(initial = PENDING, terminals = APPROVED,
                    state = OrderRefs.Review.class, id = OrderRefs.Id.class)
                @ReviewsGraph.StatePolicy(state = PENDING, otherwise = "an order is approved")
                public interface Reviews extends Machine<Order, ReviewStatus, ReviewTransition> {
                    @ReviewsGraph.Transition(id = APPROVE, from = PENDING, to = APPROVED)
                    Outcome<Order, ReviewStatus, ReviewTransition> approve(Order order);
                }
                """);
        sources.put("fx.Orders", """
                package fx;
                import io.github.graphmaquina.annotation.GenerateGraphApi;
                import io.github.graphmaquina.annotation.GraphPeers;
                import io.github.graphmaquina.api.Machine;
                import io.github.graphmaquina.api.Outcome;
                import static fx.OrderStatus.*;
                import static fx.OrderTransition.*;
                /** Machine of the billing fixture. */
                @GenerateGraphApi
                @GraphPeers({Reviews.class, Payments.class})
                @OrdersGraph.Definition(initial = NEW, terminals = {SETTLED, UNPAID},
                    state = OrderRefs.Status.class, id = OrderRefs.Id.class)
                @OrdersGraph.StatePolicy(state = NEW, otherwise = "an order is submitted first")
                @OrdersGraph.StatePolicy(state = REVIEW, otherwise = "the review decides")
                @OrdersGraph.StatePolicy(state = PAYING, otherwise = "the payment decides")
                @OrdersGraph.Transition(id = ACCEPT, from = REVIEW, to = PAYING)
                @OrdersGraph.Transition(id = SETTLE, from = PAYING, to = SETTLED)
                @OrdersGraph.Transition(id = FAIL, from = PAYING, to = UNPAID)
                @OrdersReviews.Submachine(state = REVIEW,
                    exits = @OrdersReviews.Exit(on = ReviewStatus.APPROVED, transition = ACCEPT))
                @OrdersPayments.Submachine(state = PAYING, child = OrderRefs.Payment.class,
                    parent = PaymentRefs.Order.class,
                    exits = {@OrdersPayments.Exit(on = PaymentStatus.CAPTURED, transition = SETTLE),
                             @OrdersPayments.Exit(on = PaymentStatus.DECLINED, transition = FAIL)})
                public interface Orders extends Machine<Order, OrderStatus, OrderTransition> {
                    @OrdersGraph.Transition(id = SUBMIT, from = NEW, to = REVIEW)
                    Outcome<Order, OrderStatus, OrderTransition> submit(Order order);
                }
                """);
        sources.put("fx.Billing", """
                package fx;
                import io.github.graphmaquina.annotation.StateGraphs;
                import io.github.graphmaquina.api.Machines;
                /** Generated set of the billing fixture. */
                @StateGraphs
                public interface Billing extends Machines {
                    Bookings bookings();
                    Orders orders();
                    Payments payments();
                    Reviews reviews();
                }
                """);
        return sources;
    }
}
