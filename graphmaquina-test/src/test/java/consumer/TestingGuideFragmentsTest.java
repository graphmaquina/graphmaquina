package consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compiles the Java samples of the testing guide's "Failure kinds and recovery rules", "Sub-machines" and "The call's
 * flow and its trail" sections against the published core and test jars, then runs them in a separate JVM whose class
 * path holds those two jars and the compiled samples, nothing else. The page promises that its samples run, and these
 * are cut out of {@code docs/testing.md} verbatim. They are wrapped only by the types and helpers each section names
 * and by one method per sample, or per run of samples that share their variables, so an expectation the page shows has
 * to hold for this test to pass: the publication types and the {@code graph}, {@code asyncGraph} and {@code machine}
 * helpers for the first section; for the second the order, payment and invoice types, their keys {@code ORDERS},
 * {@code PAYMENTS}, {@code INVOICES} and {@code CHARGES}, and the {@code orders}, {@code asyncOrders},
 * {@code chargedOrders}, {@code invoices}, {@code registry}, {@code registryWithInvoices} and {@code chargeRegistry}
 * helpers; and for the third the publication types and the {@code graph} helper again. A heading or block that cannot
 * be found fails the test instead of compiling nothing. The page, the repository and the release come from the
 * {@code testingGuidePath}, {@code artifactRepository} and {@code artifactVersion} system properties that
 * {@code publishedArtifactTest} sets.
 */
class TestingGuideFragmentsTest {

    private static final String RECOVERY = "### Failure kinds and recovery rules";

    private static final String SUBMACHINES = "### Sub-machines";

    private static final String FLOW = "### The call's flow and its trail";

    private static final String HEADER = """
            import static io.github.graphmaquina.test.ExitExpectation.exit;
            import static io.github.graphmaquina.test.GraphAssertions.assertThat;
            import static io.github.graphmaquina.test.GraphAssertions.captureFailure;

            import io.github.graphmaquina.api.ActionOutcome;
            import io.github.graphmaquina.api.AsyncTransitionAction;
            import io.github.graphmaquina.api.CallPhase;
            import io.github.graphmaquina.api.CommitContext;
            import io.github.graphmaquina.api.FailureKind;
            import io.github.graphmaquina.api.FlowEntry;
            import io.github.graphmaquina.api.FlowKey;
            import io.github.graphmaquina.api.GraphDefinition;
            import io.github.graphmaquina.api.GuardResult;
            import io.github.graphmaquina.api.HookRef;
            import io.github.graphmaquina.api.MachineKey;
            import io.github.graphmaquina.api.MachineObserver;
            import io.github.graphmaquina.api.Outcome;
            import io.github.graphmaquina.api.RecoveryRule;
            import io.github.graphmaquina.api.RejectionKind;
            import io.github.graphmaquina.api.StateMachine;
            import io.github.graphmaquina.api.StatesWritten;
            import io.github.graphmaquina.api.TransitionAction;
            import io.github.graphmaquina.api.TransitionContext;
            import io.github.graphmaquina.api.TransitionKind;
            import io.github.graphmaquina.dsl.GraphBuilder;
            import io.github.graphmaquina.dsl.StateGraph;
            import io.github.graphmaquina.dsl.TransitionBuilder;
            import io.github.graphmaquina.error.AmbiguousParentException;
            import io.github.graphmaquina.runtime.MachineRegistry;
            import io.github.graphmaquina.runtime.StateMachines;
            import io.github.graphmaquina.test.AsyncAssertions;
            import io.github.graphmaquina.test.StateSnapshot;
            import io.github.graphmaquina.test.TrailAssert;
            import io.github.graphmaquina.test.TransitionRecorder;
            import io.github.graphmaquina.test.probe.ActionCall;
            import io.github.graphmaquina.test.probe.ActionProbe;
            import io.github.graphmaquina.test.probe.CallTrace;
            import io.github.graphmaquina.test.probe.ControlledAsyncAction;
            import io.github.graphmaquina.test.probe.GuardProbe;
            import io.github.graphmaquina.test.probe.HookProbe;
            import io.github.graphmaquina.test.probe.PendingInvocation;
            import java.time.Duration;
            import java.time.Instant;
            import java.time.InstantSource;
            import java.util.List;
            import java.util.Optional;
            import java.util.concurrent.CompletableFuture;
            import java.util.function.UnaryOperator;

            /** Samples of docs/testing.md, extracted verbatim, run against the published jars. */
            public final class TestingFragments {
                private TestingFragments() { }

                /** The states of the publication example. */
                enum PublicationState {
                    READY, PUBLISHED, RETRY_PENDING, REJECTED_BY_PROVIDER, INTEGRATION_FAILED, CLOSED
                }

                /** The transition identifiers of the publication example. */
                enum PublicationChange {
                    PUBLISH, SCHEDULE_RETRY, RECORD_REJECTION, MARK_INTEGRATION_FAILED, RETRY, CLOSE
                }

                /** Named failure kinds of the publication provider. */
                static final class PublicationFailures {
                    private PublicationFailures() { }

                    /** The provider is temporarily unavailable. */
                    record Unavailable() implements FailureKind { }

                    /** The provider rejected the content. */
                    record Rejected() implements FailureKind { }
                }

                /** The provider is unavailable. */
                static final class ServiceUnavailableException extends RuntimeException {
                    ServiceUnavailableException(String message) {
                        super(message);
                    }
                }

                /** The provider rejected the content. */
                static final class ProviderRejectedException extends RuntimeException {
                    ProviderRejectedException(String message) {
                        super(message);
                    }
                }

                /** The provider returned an invalid response. */
                static final class InvalidResponseException extends RuntimeException {
                    InvalidResponseException(String message) {
                        super(message);
                    }
                }

                /** An entity in the publication example. */
                static final class Publication {
                    final String id;
                    PublicationState state = PublicationState.READY;

                    Publication(String id) {
                        this.id = id;
                    }
                }

                static final MachineKey<Publication, PublicationState, PublicationChange> KEY =
                        MachineKey.of(Publication.class, PublicationState.class, PublicationChange.class);

                /** Builds the publication graph with a synchronous action. */
                static GraphDefinition<Publication, PublicationState, PublicationChange> graph(
                        TransitionAction<Publication, PublicationState, PublicationChange> action) {
                    return declare((TransitionBuilder<Publication, PublicationState, PublicationChange> publish) ->
                            publish.action("publish", action));
                }

                /** Builds the same publication graph with an asynchronous action. */
                static GraphDefinition<Publication, PublicationState, PublicationChange> asyncGraph(
                        AsyncTransitionAction<Publication, PublicationState, PublicationChange> action) {
                    return declare((TransitionBuilder<Publication, PublicationState, PublicationChange> publish) ->
                            publish.asyncAction("publish", action));
                }

                /** The publication graph of the guide's recovery section, PUBLISH given its action by withAction. */
                private static GraphDefinition<Publication, PublicationState, PublicationChange> declare(
                        UnaryOperator<TransitionBuilder<Publication, PublicationState, PublicationChange>> withAction) {
                    GraphBuilder<Publication, PublicationState, PublicationChange> builder = StateGraph.define(KEY)
                            .initial(PublicationState.READY)
                            .terminal(PublicationState.PUBLISHED)
                            .terminal(PublicationState.CLOSED)
                            .allowsCycle(PublicationState.READY)
                            .statePolicy(PublicationState.READY, "publish the article first")
                            .statePolicy(PublicationState.RETRY_PENDING, "retry the publication explicitly")
                            .statePolicy(PublicationState.REJECTED_BY_PROVIDER, "close the rejected publication")
                            .statePolicy(PublicationState.INTEGRATION_FAILED, "close after manual review");
                    return withAction.apply(builder.transition(PublicationChange.PUBLISH, PublicationState.READY,
                                    PublicationState.PUBLISHED))
                            .recover(PublicationFailures.Unavailable.class, PublicationChange.SCHEDULE_RETRY,
                                    ServiceUnavailableException.class)
                            .recover(PublicationFailures.Rejected.class, PublicationChange.RECORD_REJECTION,
                                    ProviderRejectedException.class)
                            .failsOn(InvalidResponseException.class)
                            .onFailure(PublicationChange.MARK_INTEGRATION_FAILED)
                            .endTransition()
                            .transition(PublicationChange.SCHEDULE_RETRY, PublicationState.READY,
                                    PublicationState.RETRY_PENDING)
                            .endTransition()
                            .transition(PublicationChange.RECORD_REJECTION, PublicationState.READY,
                                    PublicationState.REJECTED_BY_PROVIDER)
                            .endTransition()
                            .transition(PublicationChange.MARK_INTEGRATION_FAILED, PublicationState.READY,
                                    PublicationState.INTEGRATION_FAILED)
                            .endTransition()
                            .transition(PublicationChange.RETRY, PublicationState.RETRY_PENDING,
                                    PublicationState.READY)
                            .endTransition()
                            .transition(PublicationChange.CLOSE, PublicationState.REJECTED_BY_PROVIDER,
                                    PublicationState.CLOSED)
                            .endTransition()
                            .transition(PublicationChange.CLOSE, PublicationState.INTEGRATION_FAILED,
                                    PublicationState.CLOSED)
                            .endTransition()
                            .build();
                }

                /** Builds a standalone machine for either declaration. */
                static StateMachine<Publication, PublicationState, PublicationChange> machine(
                        GraphDefinition<Publication, PublicationState, PublicationChange> graph) {
                    return StateMachines.from(graph)
                            .state((Publication publication) -> publication.state,
                                    (Publication publication, PublicationState state) -> publication.state = state)
                            .identity((Publication publication) -> publication.id)
                            .asyncTimeout(Duration.ofSeconds(5))
                            .build();
                }

                /** The states of an order. */
                enum OrderState { CART, PAYING, PAID, REFUSED, ABANDONED }

                /** The transition identifiers of an order. */
                enum OrderChange { CHECKOUT, PAY, REFUSE, ABANDON }

                /** The states of a payment. */
                enum PaymentState { AUTHORIZING, CAPTURED, DECLINED }

                /** The transition identifiers of a payment. */
                enum PaymentChange { CAPTURE, DECLINE }

                /** A payment, which knows its order and its invoice once they are created. */
                static final class Payment {
                    final String id;
                    PaymentState state = PaymentState.AUTHORIZING;
                    Optional<Order> order = Optional.empty();
                    Optional<Invoice> invoice = Optional.empty();

                    Payment(String id) {
                        this.id = id;
                    }
                }

                /** An order, which its payment pays while the order is PAYING, or which charges itself. */
                static final class Order {
                    final String id;
                    final Payment payment;
                    OrderState state = OrderState.CART;
                    PaymentState charge = PaymentState.AUTHORIZING;

                    Order(String id, Payment payment) {
                        this.id = id;
                        this.payment = payment;
                        payment.order = Optional.of(this);
                    }
                }

                static final MachineKey<Order, OrderState, OrderChange> ORDERS =
                        MachineKey.of(Order.class, OrderState.class, OrderChange.class);

                static final MachineKey<Payment, PaymentState, PaymentChange> PAYMENTS =
                        MachineKey.of(Payment.class, PaymentState.class, PaymentChange.class);

                /** The key of an order's own charge: a second state of the order, over the payment states. */
                static final MachineKey<Order, PaymentState, PaymentChange> CHARGES =
                        MachineKey.of(Order.class, PaymentState.class, PaymentChange.class);

                /** Built once: a child's machine must be built from the very definition its parent binds. */
                static final GraphDefinition<Payment, PaymentState, PaymentChange> PAYMENT_GRAPH =
                        paymentGraph(PAYMENTS);

                /** The same payment graph over the order itself, which governs the order's charge. */
                static final GraphDefinition<Order, PaymentState, PaymentChange> CHARGE_GRAPH =
                        paymentGraph(CHARGES);

                /** The payment graph over the entity of {@code key}. */
                private static <P> GraphDefinition<P, PaymentState, PaymentChange> paymentGraph(
                        MachineKey<P, PaymentState, PaymentChange> key) {
                    return StateGraph.define(key)
                            .initial(PaymentState.AUTHORIZING)
                            .terminal(PaymentState.CAPTURED)
                            .terminal(PaymentState.DECLINED)
                            .statePolicy(PaymentState.AUTHORIZING, "capture or decline the authorization")
                            .transition(PaymentChange.CAPTURE, PaymentState.AUTHORIZING, PaymentState.CAPTURED)
                            .endTransition()
                            .transition(PaymentChange.DECLINE, PaymentState.AUTHORIZING, PaymentState.DECLINED)
                            .endTransition()
                            .build();
                }

                /** The order graph, whose PAYING holds the payment of the order. */
                static GraphDefinition<Order, OrderState, OrderChange> orders() {
                    return orders(UnaryOperator.identity());
                }

                /** The same order graph, {@code pay} applied to the declaration of the exit PAY. */
                static GraphDefinition<Order, OrderState, OrderChange> orders(
                        UnaryOperator<TransitionBuilder<Order, OrderState, OrderChange>> pay) {
                    return declareOrders(pay, (GraphBuilder<Order, OrderState, OrderChange> builder) ->
                            builder.submachine(OrderState.PAYING, PAYMENT_GRAPH,
                                            (Order order) -> Optional.of(order.payment),
                                            (Payment payment) -> payment.order)
                                    .exit(PaymentState.CAPTURED, OrderChange.PAY)
                                    .exit(PaymentState.DECLINED, OrderChange.REFUSE)
                                    .endSubmachine());
                }

                /** The same order graph, with {@code action} as the asynchronous action of the exit PAY. */
                static GraphDefinition<Order, OrderState, OrderChange> asyncOrders(
                        AsyncTransitionAction<Order, OrderState, OrderChange> action) {
                    return orders((TransitionBuilder<Order, OrderState, OrderChange> pay) ->
                            pay.asyncAction("pay", action));
                }

                /** The order graph whose PAYING holds the order's own charge instead of its payment. */
                static GraphDefinition<Order, OrderState, OrderChange> chargedOrders() {
                    return declareOrders(UnaryOperator.identity(),
                            (GraphBuilder<Order, OrderState, OrderChange> builder) ->
                                    builder.submachine(OrderState.PAYING, CHARGE_GRAPH)
                                            .exit(PaymentState.CAPTURED, OrderChange.PAY)
                                            .exit(PaymentState.DECLINED, OrderChange.REFUSE)
                                            .endSubmachine());
                }

                /** The order graph of the guide's sub-machine section, PAY given by pay and PAYING bound by bind. */
                private static GraphDefinition<Order, OrderState, OrderChange> declareOrders(
                        UnaryOperator<TransitionBuilder<Order, OrderState, OrderChange>> pay,
                        UnaryOperator<GraphBuilder<Order, OrderState, OrderChange>> bind) {
                    GraphBuilder<Order, OrderState, OrderChange> builder = StateGraph.define(ORDERS)
                            .initial(OrderState.CART)
                            .terminal(OrderState.PAID)
                            .terminal(OrderState.REFUSED)
                            .terminal(OrderState.ABANDONED)
                            .statePolicy(OrderState.CART, "check the cart out first")
                            .statePolicy(OrderState.PAYING, "the payment decides")
                            .transition(OrderChange.CHECKOUT, OrderState.CART, OrderState.PAYING).endTransition();
                    return bind.apply(pay.apply(builder.transition(OrderChange.PAY, OrderState.PAYING, OrderState.PAID))
                                    .endTransition()
                                    .transition(OrderChange.REFUSE, OrderState.PAYING, OrderState.REFUSED)
                                    .endTransition()
                                    .transition(OrderChange.ABANDON, OrderState.CART, OrderState.ABANDONED)
                                    .endTransition()
                                    .transition(OrderChange.ABANDON, OrderState.PAYING, OrderState.ABANDONED)
                                    .endTransition())
                            .build();
                }

                /** The states of an invoice. */
                enum InvoiceState { OPEN, SETTLING, SETTLED, UNPAID }

                /** The transition identifiers of an invoice. */
                enum InvoiceChange { SETTLE, CLEAR, DEFAULT }

                /** An invoice, which its payment settles while the invoice is SETTLING: a second parent of payments. */
                static final class Invoice {
                    final String id;
                    final Payment payment;
                    InvoiceState state = InvoiceState.OPEN;

                    Invoice(String id, Payment payment) {
                        this.id = id;
                        this.payment = payment;
                        payment.invoice = Optional.of(this);
                    }
                }

                static final MachineKey<Invoice, InvoiceState, InvoiceChange> INVOICES =
                        MachineKey.of(Invoice.class, InvoiceState.class, InvoiceChange.class);

                /** The invoice graph, whose SETTLING holds the payment of the invoice. */
                static GraphDefinition<Invoice, InvoiceState, InvoiceChange> invoices() {
                    return StateGraph.define(INVOICES)
                            .initial(InvoiceState.OPEN)
                            .terminal(InvoiceState.SETTLED)
                            .terminal(InvoiceState.UNPAID)
                            .statePolicy(InvoiceState.OPEN, "settle the invoice first")
                            .statePolicy(InvoiceState.SETTLING, "the payment decides")
                            .transition(InvoiceChange.SETTLE, InvoiceState.OPEN, InvoiceState.SETTLING)
                            .endTransition()
                            .transition(InvoiceChange.CLEAR, InvoiceState.SETTLING, InvoiceState.SETTLED)
                            .endTransition()
                            .transition(InvoiceChange.DEFAULT, InvoiceState.SETTLING, InvoiceState.UNPAID)
                            .endTransition()
                            .submachine(InvoiceState.SETTLING, PAYMENT_GRAPH,
                                    (Invoice invoice) -> Optional.of(invoice.payment),
                                    (Payment payment) -> payment.invoice)
                            .exit(PaymentState.CAPTURED, InvoiceChange.CLEAR)
                            .exit(PaymentState.DECLINED, InvoiceChange.DEFAULT)
                            .endSubmachine()
                            .build();
                }

                /** A registry of the order machine built from {@code orders} and of the payment machine. */
                static MachineRegistry registry(GraphDefinition<Order, OrderState, OrderChange> orders) {
                    return orderAndPayment(orders).build();
                }

                /** The same registry, with {@code observer} told of every call. */
                static MachineRegistry registry(GraphDefinition<Order, OrderState, OrderChange> orders,
                        MachineObserver observer) {
                    return orderAndPayment(orders).observer(observer).build();
                }

                /** The same registry, with the invoice machine as a second parent of payments. */
                static MachineRegistry registryWithInvoices(GraphDefinition<Order, OrderState, OrderChange> orders) {
                    return orderAndPayment(orders)
                            .add(StateMachines.from(invoices())
                                    .state((Invoice invoice) -> invoice.state,
                                            (Invoice invoice, InvoiceState state) -> invoice.state = state)
                                    .identity((Invoice invoice) -> invoice.id))
                            .build();
                }

                /** A registry of the charged order machine and of the charge machine, both over the order. */
                static MachineRegistry chargeRegistry() {
                    return MachineRegistry.builder()
                            .add(StateMachines.from(chargedOrders())
                                    .state((Order order) -> order.state,
                                            (Order order, OrderState state) -> order.state = state)
                                    .identity((Order order) -> order.id))
                            .add(StateMachines.from(CHARGE_GRAPH)
                                    .state((Order order) -> order.charge,
                                            (Order order, PaymentState state) -> order.charge = state)
                                    .identity((Order order) -> order.id))
                            .build();
                }

                /** The order machine built from {@code orders} and the payment machine, both with a timeout. */
                private static MachineRegistry.Builder orderAndPayment(
                        GraphDefinition<Order, OrderState, OrderChange> orders) {
                    return MachineRegistry.builder()
                            .add(StateMachines.from(orders)
                                    .state((Order order) -> order.state,
                                            (Order order, OrderState state) -> order.state = state)
                                    .identity((Order order) -> order.id)
                                    .asyncTimeout(Duration.ofSeconds(5)))
                            .add(StateMachines.from(PAYMENT_GRAPH)
                                    .state((Payment payment) -> payment.state,
                                            (Payment payment, PaymentState state) -> payment.state = state)
                                    .identity((Payment payment) -> payment.id)
                                    .asyncTimeout(Duration.ofSeconds(5)));
                }

                /** Runs the extracted assertions and prints a marker only after all pass. */
                public static void main(String[] args) {
                    syncRouting();
                    actionResults();
                    asyncRouting();
                    submachineDefinition();
                    submachineStarts();
                    submachineRefusals();
                    ambiguousParent();
                    exitProbes();
                    asyncExit();
                    sameEntity();
                    flowAndTrail();
                    System.out.println("TESTING_GUIDE_OK");
                }

            """;

    /** Compiles and runs every sample of the three sections with a preemptive watchdog and a retained transcript. */
    @Test
    @Timeout(value = 150, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theSamplesOfTheTestingGuideRunAgainstThePublishedJars(
            @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path workDir)
            throws IOException, InterruptedException {
        List<String> page = Files.readAllLines(Path.of(property("testingGuidePath")), UTF_8);
        String jars = publishedJar("graphmaquina-core") + File.pathSeparator + publishedJar("graphmaquina-test");

        assertBlockCount(page, RECOVERY, 4);
        assertBlockCount(page, SUBMACHINES, 7);
        assertBlockCount(page, FLOW, 4);

        StringBuilder source = new StringBuilder(HEADER);
        appendRecoverySamples(source, page);
        appendSubmachineSamples(source, page);
        appendFlowSample(source, page);

        Path classes = Files.createDirectories(workDir.resolve("classes"));
        List<String> diagnostics = compile(workDir, source.toString(), jars, classes);
        assertTrue(diagnostics.isEmpty(), "the testing guide's samples must compile against the published "
                + "jars:\n" + String.join("\n", diagnostics) + "\n--- generated TestingFragments.java ---\n" + source);

        runSamples(workDir, jars, classes, source);
    }

    /** Appends the samples of the failure kinds and recovery rules section, wrapped in one method per run. */
    private static void appendRecoverySamples(StringBuilder source, List<String> page) {
        source.append("    // --- blocks 1 and 2: the routes a synchronous call took, then the declared rules\n");
        source.append("    static void syncRouting() {\n");
        source.append(blockAfter(page, RECOVERY, 1)).append(blockAfter(page, RECOVERY, 2)).append("    }\n\n");
        source.append("    // --- block 3: an action result on its own\n");
        source.append("    static void actionResults() {\n");
        source.append(blockAfter(page, RECOVERY, 3)).append("    }\n\n");
        source.append("    // --- block 4: the routes an asynchronous action took\n");
        source.append("    static void asyncRouting() {\n");
        source.append(blockAfter(page, RECOVERY, 4)).append("    }\n\n");
    }

    /** Appends the samples of the sub-machines section, wrapped in one method per block. */
    private static void appendSubmachineSamples(StringBuilder source, List<String> page) {
        source.append("    // --- sub-machines, block 1: the binding of a state\n");
        source.append("    static void submachineDefinition() {\n");
        source.append(blockAfter(page, SUBMACHINES, 1)).append("    }\n\n");
        source.append("    // --- sub-machines, block 2: the start of a child, then its exit\n");
        source.append("    static void submachineStarts() {\n");
        source.append(blockAfter(page, SUBMACHINES, 2)).append("    }\n\n");
        source.append("    // --- sub-machines, block 3: a reserved exit and a detached child\n");
        source.append("    static void submachineRefusals() {\n");
        source.append(blockAfter(page, SUBMACHINES, 3)).append("    }\n\n");
        source.append("    // --- sub-machines, block 4: two active parents\n");
        source.append("    static void ambiguousParent() {\n");
        source.append(blockAfter(page, SUBMACHINES, 4)).append("    }\n\n");
        source.append("    // --- sub-machines, block 5: probes on an exit, and the recorded calls\n");
        source.append("    static void exitProbes() {\n");
        source.append(blockAfter(page, SUBMACHINES, 5)).append("    }\n\n");
        source.append("    // --- sub-machines, block 6: an asynchronous exit, held open\n");
        source.append("    static void asyncExit() {\n");
        source.append(blockAfter(page, SUBMACHINES, 6)).append("    }\n\n");
        source.append("    // --- sub-machines, block 7: a child over its parent's own entity\n");
        source.append("    static void sameEntity() {\n");
        source.append(blockAfter(page, SUBMACHINES, 7)).append("    }\n\n");
    }

    /** Appends the samples of the call's flow and trail section, wrapped in one method, and closes the class. */
    private static void appendFlowSample(StringBuilder source, List<String> page) {
        source.append("    // --- the call's flow, blocks 1 to 4: an outcome's, a failure's, a hook's, a bare trail\n");
        source.append("    static void flowAndTrail() {\n");
        source.append(blockAfter(page, FLOW, 1)).append(blockAfter(page, FLOW, 2)).append(blockAfter(page, FLOW, 3))
                .append(blockAfter(page, FLOW, 4)).append("    }\n}\n");
    }

    /** Runs the compiled samples in a separate JVM and expects them to reach their marker before they end. */
    private static void runSamples(Path workDir, String jars, Path classes, StringBuilder source)
            throws IOException, InterruptedException {
        Path transcript = workDir.resolve("samples-output.txt");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-classpath", classes + File.pathSeparator + jars, "TestingFragments")
                .redirectErrorStream(true)
                .redirectOutput(transcript.toFile())
                .start();
        Optional<Throwable> originalFailure = Optional.empty();
        try {
            assertSamplesPrintTheirMarker(process, transcript, source);
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            originalFailure = Optional.of(failure);
            throw failure;
        } finally {
            ChildProcesses.reap(process, originalFailure,
                    "the testing guide child remained alive; transcript: " + transcript);
        }
    }

    private static void assertSamplesPrintTheirMarker(Process process, Path transcript, StringBuilder source)
            throws IOException, InterruptedException {
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            fail("the testing guide's samples did not terminate; transcript: " + transcript);
        }
        String output = Files.readString(transcript, UTF_8);
        assertEquals(0, process.exitValue(), "the testing guide's samples failed: " + output
                + "\n--- generated TestingFragments.java ---\n" + source);
        assertTrue(output.contains("TESTING_GUIDE_OK"), "the samples did not reach their last line: " + output);
    }

    /** Fails unless the section under {@code heading} holds exactly {@code expected} java blocks. */
    private static void assertBlockCount(List<String> page, String heading, int expected) {
        int actual = javaBlocksAfter(page, heading).size();
        assertEquals(expected, actual, "the testing guide's section '" + heading + "' has " + actual
                + " java blocks; this test covers exactly " + expected + ": update the test to match the section");
    }

    /**
     * Returns the {@code n}th java block after {@code heading} and before the next heading of any level. Headings
     * are matched outside code fences only. Fails when the block is missing or blank.
     */
    private static String blockAfter(List<String> page, String heading, int n) {
        List<MarkdownPage.Block> blocks = javaBlocksAfter(page, heading);
        String block = n <= blocks.size() ? blocks.get(n - 1).content() : "";
        assertFalse(block.isBlank(), "no non-blank java block number " + n + " after the heading '"
                + heading + "' of the testing guide");
        return block;
    }

    /**
     * Returns the java blocks after {@code heading} and before the next heading of any level. Headings are matched
     * outside code fences only.
     */
    private static List<MarkdownPage.Block> javaBlocksAfter(List<String> page, String heading) {
        return MarkdownPage.blocksAfter(page, heading, (String line) -> line.startsWith("#"), "java");
    }

    /** Compiles the samples with the published jars as their entire class path; returns what javac reported. */
    private static List<String> compile(Path workDir, String content, String jars, Path classes) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "this test needs a JDK compiler, not a JRE");
        Path source = Files.writeString(workDir.resolve("TestingFragments.java"), content);
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(collector, null, UTF_8)) {
            Iterable<? extends JavaFileObject> units = manager.getJavaFileObjectsFromPaths(List.of(source));
            List<String> options = List.of("--release", "21", "-proc:none", "-Xlint:all,-serial", "-Werror",
                    "-classpath", jars, "-d", classes.toString());
            boolean succeeded = compiler.getTask(null, manager, collector, options, null, units).call();
            List<String> reported = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : collector.getDiagnostics()) {
                reported.add(diagnostic.getKind() + " at line " + diagnostic.getLineNumber() + ": "
                        + diagnostic.getMessage(null));
            }
            if (reported.isEmpty() && !succeeded) {
                reported.add("javac produced no classes and no diagnostic");
            }
            return reported;
        }
    }

    /** Resolves one staged jar without allowing project classes onto the sample class path. */
    private static Path publishedJar(String module) {
        String version = property("artifactVersion");
        Path jar = Path.of(property("artifactRepository"), "io/github/graphmaquina/" + module, version,
                module + "-" + version + ".jar");
        assertTrue(Files.isRegularFile(jar), "the published " + module + " jar is missing: " + jar);
        return jar;
    }

    /** Reads a property supplied by the published-artifact Gradle task. */
    private static String property(String name) {
        String value = System.getProperty(name);
        assertNotNull(value, "run this test through :graphmaquina-test:publishedArtifactTest; missing " + name);
        return value;
    }
}
