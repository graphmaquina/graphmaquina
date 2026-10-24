package consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compiles the Java fragments of the guide's "Handle transition outcomes" page, its failed-call handler included,
 * plus the async completion callback of its "Asynchronous work and timeouts" page and the observer example of its
 * "Commit, hooks, observers and persistence" page, against the published core jar. A second test compiles every
 * fragment of the "Recover by failure kind and exception type" section of its "Actions, guards and failure routes"
 * page the same way, as a compilation unit of its own, a third one every fragment of its "Compose machines" page, and
 * a fourth one every fragment of its "The call's flow" page. This test runs no generator, so the annotated declarations of the recovery and
 * composition sections compile against stand-ins shaped like what the generator and the processor emit: the
 * {@code Transition} and {@code Recovery} annotations of the recovery section, and the billing example's graph and
 * pair vocabulary, symbols and set builder, beside stand-ins for its entities, enums and hosts, for the composition
 * section; the flow section's actions, hook, handler, registry factory and trail printer compile on stand-in hosts,
 * beside stand-ins for the shop's order, its items, their enums and facades, and its restock record. The fragments
 * are cut out of those pages of {@code docs} verbatim and wrapped only by stand-in types and methods, so a renamed
 * accessor in the guide breaks this test the way it would break a reader.
 * A heading or block that cannot be found fails the test instead of compiling nothing. The pages' directory, the
 * repository and the release come from the {@code docsPath}, {@code artifactRepository} and {@code artifactVersion}
 * system properties that {@code publishedArtifactTest} sets.
 */
class GuideOutcomeFragmentsTest {

    private static final String SECTION_HEADING = "# Handle transition outcomes";
    private static final String RECOVERY_HEADING = "## Recover by failure kind and exception type";
    private static final String COMPOSITION_HEADING = "# Compose machines";
    private static final String FLOW_HEADING = "# The call's flow";
    private static final String FUTURE_PREFIX =
            "CompletableFuture<Outcome<Ticket, TicketState, TicketTransition>> pending =";
    private static final String OBSERVER_PREFIX =
            "TicketMachines machines = TicketMachinesRuntime.builder(new TicketActions())";

    private static final String HEADER = """
            import io.github.graphmaquina.api.CallFailure;
            import io.github.graphmaquina.api.MachineCall;
            import io.github.graphmaquina.api.MachineObserver;
            import io.github.graphmaquina.api.Outcome;
            import io.github.graphmaquina.api.OutcomeCode;
            import io.github.graphmaquina.api.OutcomeStatus;
            import io.github.graphmaquina.error.CallFailedException;
            import java.time.Duration;
            import java.util.Optional;
            import java.util.concurrent.CompletableFuture;

            /** Every outcome fragment of docs/outcomes.md, extracted verbatim, compiled against core. */
            public final class GuideFragments {
                private GuideFragments() { }

                enum TicketState { OPEN, REVIEW, DONE, FAILED }
                enum TicketTransition { SUBMIT, APPROVE, DECLINE, RETOUCH, REJECT, AUTO_APPROVE }
                static final class Ticket { }
                static final class TicketActions { }
                interface TicketMachines { }
                static final class TicketMachinesRuntime {
                    static Builder builder(TicketActions actions) { return new Builder(); }
                    static final class Builder {
                        Builder observer(MachineObserver observer) { return this; }
                        TicketMachines build() { return new TicketMachines() { }; }
                    }
                }

                // --- "Handle transition outcomes", block 1 (describe)
            """;

    private static final String STUBS = """
                interface StubTickets {
                    Outcome<Ticket, TicketState, TicketTransition> approve(Ticket ticket);
                    <R> CompletableFuture<R> async(java.util.function.Supplier<R> call);
                }
            """;

    private static final String RECOVERY_HEADER = """
            import io.github.graphmaquina.annotation.Timeout;
            import io.github.graphmaquina.api.ActionOutcome;
            import io.github.graphmaquina.api.FailureKind;
            import io.github.graphmaquina.api.GraphDefinition;
            import io.github.graphmaquina.api.MachineKey;
            import io.github.graphmaquina.api.Outcome;
            import io.github.graphmaquina.api.StateMachine;
            import io.github.graphmaquina.api.TransitionContext;
            import io.github.graphmaquina.dsl.StateGraph;
            import io.github.graphmaquina.dsl.TransitionBuilder;
            import io.github.graphmaquina.symbol.ActionSymbol;
            import io.github.graphmaquina.symbol.GuardSymbol;
            import io.github.graphmaquina.symbol.HookSymbol;
            import java.io.IOException;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            import java.net.SocketTimeoutException;
            import java.util.Optional;

            /** Every recovery fragment of docs/actions-and-failures.md, extracted verbatim, compiled against core. */
            public final class GuideRecoveryFragments {
                private GuideRecoveryFragments() { }

                /** Publication entity stand-in. */
                static final class Publication { }
                /** Adapter that returns the action result. */
                interface PublicationAdapter {
                    ActionOutcome publish(Publication publication);
                }
                /** Provider availability failure. */
                static final class ServiceUnavailableException extends RuntimeException {
                    ServiceUnavailableException(String message) { super(message); }
                }
                /** Provider rejection failure. */
                static final class ProviderRejectedException extends RuntimeException {
                    ProviderRejectedException(String message) { super(message); }
                }
                /** Invalid provider response failure. */
                static final class InvalidResponseException extends RuntimeException {
                    InvalidResponseException(String message) { super(message); }
                }
                /** Action symbol names used by the annotation example. */
                static final class PublicationActionsRefs {
                    private PublicationActionsRefs() { }
                    /** Symbol for the publish action. */
                    static final class Publish implements ActionSymbol {
                        private Publish() { }
                    }
                }
                // Shaped like the vocabulary the generator emits for a facade named Publications.
                /** Vocabulary stand-in for the generated publication graph. */
                static final class PublicationsGraph {
                    private PublicationsGraph() { }
                    /** A generated recovery annotation stand-in. */
                    @Target({})
                    @Retention(RetentionPolicy.CLASS)
                    @interface Recovery {
                        Class<? extends FailureKind> kind();
                        PublicationChange transition();
                        Class<? extends Throwable>[] failsOn() default {};
                    }
                    /** A generated transition annotation stand-in. */
                    @Target({ElementType.TYPE, ElementType.METHOD})
                    @Retention(RetentionPolicy.CLASS)
                    @interface Transition {
                        PublicationChange id();
                        PublicationState[] from();
                        PublicationState to();
                        Class<? extends ActionSymbol>[] action() default {};
                        Class<? extends GuardSymbol>[] guards() default {};
                        PublicationChange[] onFailure() default {};
                        Class<? extends Throwable>[] failsOn() default {};
                        Recovery[] recoveries() default {};
                        Timeout[] timeout() default {};
                        Class<? extends HookSymbol>[] afterCommit() default {};
                    }
                }

                // --- "Recover by failure kind and exception type", block 1 (states, identifiers, failure kinds)
            """;

    private static final String COMPOSITION_HEADER = """
            package guide;

            import static guide.GuideCompositionFragments.BookingStatus.BALANCE;
            import static guide.GuideCompositionFragments.BookingStatus.CANCELLED;
            import static guide.GuideCompositionFragments.BookingStatus.CONFIRMED;
            import static guide.GuideCompositionFragments.BookingStatus.DEPOSIT;
            import static guide.GuideCompositionFragments.BookingStatus.REQUESTED;
            import static guide.GuideCompositionFragments.SubscriptionStatus.RENEWAL;

            import io.github.graphmaquina.annotation.GenerateGraphApi;
            import io.github.graphmaquina.annotation.GraphAction;
            import io.github.graphmaquina.annotation.GraphLookup;
            import io.github.graphmaquina.annotation.GraphPeers;
            import io.github.graphmaquina.annotation.Timeout;
            import io.github.graphmaquina.api.ActionOutcome;
            import io.github.graphmaquina.api.GraphDefinition;
            import io.github.graphmaquina.api.Machine;
            import io.github.graphmaquina.api.MachineKey;
            import io.github.graphmaquina.api.Machines;
            import io.github.graphmaquina.api.Outcome;
            import io.github.graphmaquina.api.TransitionContext;
            import io.github.graphmaquina.dsl.StateGraph;
            import io.github.graphmaquina.runtime.MachineRegistry;
            import io.github.graphmaquina.runtime.StateMachines;
            import io.github.graphmaquina.symbol.ActionSymbol;
            import io.github.graphmaquina.symbol.GuardSymbol;
            import io.github.graphmaquina.symbol.HookSymbol;
            import io.github.graphmaquina.symbol.LookupSymbol;
            import io.github.graphmaquina.symbol.PropertySymbol;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Repeatable;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            import java.time.Duration;
            import java.util.Map;
            import java.util.Optional;
            import java.util.concurrent.CompletionStage;
            import java.util.concurrent.ConcurrentHashMap;

            /** Every composition fragment of docs/composition.md, extracted verbatim, compiled against core. */
            public final class GuideCompositionFragments {
                private GuideCompositionFragments() { }

                /** States of a payment. */
                enum PaymentStatus { AUTHORIZING, CAPTURED, DECLINED, VOIDED }
                /** Identifiers of a payment. */
                enum PaymentTransition { CAPTURE, DECLINE, VOID }
                /** States of a booking. */
                enum BookingStatus { REQUESTED, DEPOSIT, BALANCE, CONFIRMED, CANCELLED }
                /** Identifiers of a booking. */
                enum BookingTransition {
                    BOOK, DEPOSIT_PAID, DEPOSIT_DECLINED, BALANCE_PAID, BALANCE_DECLINED, PAYMENT_VOIDED, NO_ROOM,
                    CANCEL
                }
                /** States of a subscription. */
                enum SubscriptionStatus { ACTIVE, RENEWAL, LAPSED }
                /** Identifiers of a subscription. */
                enum SubscriptionTransition { START_RENEWAL, RENEWED, LAPSE }

                /** Payment entity stand-in. */
                static final class Payment {
                    private final String id;
                    Payment(String id) { this.id = id; }
                    String id() { return id; }
                }
                /** Booking entity stand-in, which holds its deposit and its balance. */
                static final class Booking {
                    private final Payment deposit;
                    private final Payment balance;
                    Booking(String id, Payment deposit, Payment balance) {
                        this.deposit = deposit;
                        this.balance = balance;
                    }
                    Optional<Payment> deposit() { return Optional.of(deposit); }
                    Optional<Payment> balance() { return Optional.of(balance); }
                }
                /** Subscription entity stand-in. */
                static final class Subscription { }

                /** Payment facade stand-in. */
                interface Payments extends Machine<Payment, PaymentStatus, PaymentTransition> {
                    Outcome<Payment, PaymentStatus, PaymentTransition> capture(Payment payment);
                }
                /** The port the asynchronous exit asks for a room. */
                interface RoomDesk {
                    CompletionStage<ActionOutcome> holdRoom(
                            TransitionContext<Booking, BookingStatus, BookingTransition> context);
                }

                // Shaped like the vocabulary, symbols and set builder the generator and the processor emit.
                /** Graph vocabulary stand-in of Bookings. */
                static final class BookingsGraph {
                    private BookingsGraph() { }
                    /** A generated definition annotation stand-in. */
                    @Target(ElementType.TYPE)
                    @Retention(RetentionPolicy.CLASS)
                    @interface Definition {
                        BookingStatus initial();
                        BookingStatus[] terminals() default {};
                        BookingStatus[] allowsCycle() default {};
                        Class<? extends PropertySymbol> state();
                        Class<? extends PropertySymbol> id();
                        Class<? extends HookSymbol>[] afterCommit() default {};
                    }
                    /** A generated state policy annotation stand-in. */
                    @Target(ElementType.TYPE)
                    @Retention(RetentionPolicy.CLASS)
                    @Repeatable(StatePolicies.class)
                    @interface StatePolicy {
                        BookingStatus state();
                        String otherwise();
                    }
                    /** Its container. */
                    @Target(ElementType.TYPE)
                    @Retention(RetentionPolicy.CLASS)
                    @interface StatePolicies {
                        StatePolicy[] value();
                    }
                    /** A generated transition annotation stand-in, without recovery rules. */
                    @Target({ElementType.TYPE, ElementType.METHOD})
                    @Retention(RetentionPolicy.CLASS)
                    @Repeatable(Transitions.class)
                    @interface Transition {
                        BookingTransition id();
                        BookingStatus[] from();
                        BookingStatus to();
                        Class<? extends ActionSymbol>[] action() default {};
                        Class<? extends GuardSymbol>[] guards() default {};
                        BookingTransition[] onFailure() default {};
                        Class<? extends Throwable>[] failsOn() default {};
                        Timeout[] timeout() default {};
                        Class<? extends HookSymbol>[] afterCommit() default {};
                    }
                    /** Its container. */
                    @Target({ElementType.TYPE, ElementType.METHOD})
                    @Retention(RetentionPolicy.CLASS)
                    @interface Transitions {
                        Transition[] value();
                    }
                }
                /** Pair vocabulary stand-in of Bookings over Payments. */
                static final class BookingsPayments {
                    private BookingsPayments() { }
                    /** A generated sub-machine annotation stand-in. */
                    @Target(ElementType.TYPE)
                    @Retention(RetentionPolicy.CLASS)
                    @Repeatable(Submachines.class)
                    @interface Submachine {
                        BookingStatus state();
                        Class<? extends PropertySymbol>[] child() default {};
                        Class<? extends PropertySymbol>[] parent() default {};
                        Class<? extends LookupSymbol>[] parentLookup() default {};
                        Exit[] exits();
                    }
                    /** Its container. */
                    @Target(ElementType.TYPE)
                    @Retention(RetentionPolicy.CLASS)
                    @interface Submachines {
                        Submachine[] value();
                    }
                    /** A generated exit annotation stand-in. */
                    @Target({})
                    @Retention(RetentionPolicy.CLASS)
                    @interface Exit {
                        PaymentStatus on();
                        BookingTransition transition();
                    }
                }
                /** Pair vocabulary stand-in of Subscriptions over Payments. */
                static final class SubscriptionsPayments {
                    private SubscriptionsPayments() { }
                    /** A generated sub-machine annotation stand-in. */
                    @Target(ElementType.TYPE)
                    @Retention(RetentionPolicy.CLASS)
                    @Repeatable(Submachines.class)
                    @interface Submachine {
                        SubscriptionStatus state();
                        Class<? extends PropertySymbol>[] child() default {};
                        Class<? extends PropertySymbol>[] parent() default {};
                        Class<? extends LookupSymbol>[] parentLookup() default {};
                        Exit[] exits();
                    }
                    /** Its container. */
                    @Target(ElementType.TYPE)
                    @Retention(RetentionPolicy.CLASS)
                    @interface Submachines {
                        Submachine[] value();
                    }
                    /** A generated exit annotation stand-in. */
                    @Target({})
                    @Retention(RetentionPolicy.CLASS)
                    @interface Exit {
                        PaymentStatus on();
                        SubscriptionTransition transition();
                    }
                }
                /** Property symbols of Booking. */
                static final class BookingRefs {
                    private BookingRefs() { }
                    /** The state property. */
                    static final class Status implements PropertySymbol { private Status() { } }
                    /** The identity property. */
                    static final class Id implements PropertySymbol { private Id() { } }
                    /** The deposit property. */
                    static final class Deposit implements PropertySymbol { private Deposit() { } }
                    /** The balance property. */
                    static final class Balance implements PropertySymbol { private Balance() { } }
                }
                /** Property symbols of Subscription. */
                static final class SubscriptionRefs {
                    private SubscriptionRefs() { }
                    /** The renewal property. */
                    static final class Renewal implements PropertySymbol { private Renewal() { } }
                }
                /** Lookup symbols of BillingLookups. */
                static final class BillingLookupsRefs {
                    private BillingLookupsRefs() { }
                    /** The booking lookup. */
                    static final class BookingOf implements LookupSymbol { private BookingOf() { } }
                    /** The subscription lookup. */
                    static final class SubscriptionOf implements LookupSymbol { private SubscriptionOf() { } }
                }
                /** Action symbols of BookingActions. */
                static final class BookingActionsRefs {
                    private BookingActionsRefs() { }
                    /** The room action. */
                    static final class HoldRoom implements ActionSymbol { private HoldRoom() { } }
                    /** The cancel action. */
                    static final class Cancel implements ActionSymbol { private Cancel() { } }
                }
                /** Set stand-in. */
                interface BillingMachines extends Machines {
                    Bookings bookings();
                    Subscriptions subscriptions();
                    Payments payments();
                }
                /** Generated set builder stand-in. */
                static final class BillingMachinesRuntime {
                    private BillingMachinesRuntime() { }
                    static Builder builder(Duration asyncTimeout, BookingActions bookingActions,
                            BillingLookups billingLookups) {
                        return new Builder();
                    }
                    /** The builder. */
                    static final class Builder {
                        Builder asyncTimeout(Class<? extends Machine<?, ?, ?>> machine, Duration timeout) {
                            return this;
                        }
                        BillingMachines build() {
                            throw new UnsupportedOperationException("compiled, never run");
                        }
                    }
                }

                /** States of a stay. */
                enum StayStatus { REQUESTED, PAYING, CONFIRMED, CANCELLED }
                /** Identifiers of a stay. */
                enum StayTransition { BOOK, CONFIRM, CANCEL }
                /** A stay, which is its own payment. */
                static final class Stay {
                    final String id;
                    StayStatus status = StayStatus.REQUESTED;
                    PaymentStatus paymentStatus = PaymentStatus.AUTHORIZING;
                    Stay(String id) { this.id = id; }
                }
                static final MachineKey<Stay, StayStatus, StayTransition> STAYS =
                        MachineKey.of(Stay.class, StayStatus.class, StayTransition.class);
                static final MachineKey<Stay, PaymentStatus, PaymentTransition> STAY_PAYMENTS =
                        MachineKey.of(Stay.class, PaymentStatus.class, PaymentTransition.class);

                // --- "Compose machines", block 1 (the Bookings interface)
            """;

    private static final String FLOW_HEADER = """
            package guide;

            import io.github.graphmaquina.annotation.GraphAction;
            import io.github.graphmaquina.annotation.GraphHook;
            import io.github.graphmaquina.api.ActionOutcome;
            import io.github.graphmaquina.api.CascadePolicy;
            import io.github.graphmaquina.api.CommitContext;
            import io.github.graphmaquina.api.FlowEntry;
            import io.github.graphmaquina.api.FlowKey;
            import io.github.graphmaquina.api.FlowView;
            import io.github.graphmaquina.api.GraphDefinition;
            import io.github.graphmaquina.api.Machine;
            import io.github.graphmaquina.api.Outcome;
            import io.github.graphmaquina.api.TransitionContext;
            import io.github.graphmaquina.error.CallFailedException;
            import io.github.graphmaquina.runtime.MachineRegistry;
            import io.github.graphmaquina.runtime.StateMachines;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Optional;

            /** Every flow fragment of docs/call-flow.md, extracted verbatim, compiled against core. */
            public final class GuideFlowFragments {
                private GuideFlowFragments() { }

                /** States of an order. */
                enum OrderStatus { NEW, CANCELLED }
                /** Identifiers of an order. */
                enum OrderTransition { CANCEL }
                /** States of an item. */
                enum ItemStatus { PENDING, CANCELLED }
                /** Identifiers of an item. */
                enum ItemTransition { CANCEL }

                /** Order entity stand-in, which holds its id, its status, its items and the items it restocked. */
                static final class Order {
                    private final List<Item> items = new ArrayList<>();
                    private final List<Item> restocked = new ArrayList<>();
                    private OrderStatus status = OrderStatus.NEW;
                    String id() { return "o-1"; }
                    OrderStatus status() { return status; }
                    void setStatus(OrderStatus next) { status = next; }
                    List<Item> items() { return items; }
                    void restocked(Item item) { restocked.add(item); }
                }
                /** Item entity stand-in: its id, its status, and the batch it went back to stock in. */
                static final class Item {
                    private Optional<String> restockedIn = Optional.empty();
                    private ItemStatus status = ItemStatus.PENDING;
                    String id() { return "i-1"; }
                    ItemStatus status() { return status; }
                    void setStatus(ItemStatus next) { status = next; }
                    void restockedIn(String batch) { restockedIn = Optional.of(batch); }
                }
                /** Restock stand-in: the batch an item goes back to stock in and the order it leaves. */
                record Restock(String batch, Order order) { }
                /** Item facade stand-in. */
                interface Items extends Machine<Item, ItemStatus, ItemTransition> { }
                /** Order facade stand-in, whose cancel takes the cancellation identifier as its input. */
                interface Orders extends Machine<Order, OrderStatus, OrderTransition> {
                    Outcome<Order, OrderStatus, OrderTransition> cancel(Order order, String cancellationId);
                }

                // --- "The call's flow", block 1 (the keys)
            """;

    @Test
    void everyOutcomeFragmentOfTheGuideCompilesAgainstThePublishedCore(@TempDir Path workDir) throws IOException {
        List<String> guide = page("outcomes.md");
        Path coreJar = publishedCoreJar();

        assertJavaBlockCount(guide, SECTION_HEADING, 5);

        StringBuilder source = new StringBuilder(HEADER);
        source.append(blockAfter(guide, SECTION_HEADING, 1)).append('\n');
        source.append("    // --- block 2 (TransitionResponse and httpStatus)\n");
        source.append(blockAfter(guide, SECTION_HEADING, 2)).append('\n');
        source.append("    // --- block 3 (asX), wrapped in a method\n");
        source.append("    static void narrow(Outcome<Ticket, TicketState, TicketTransition> outcome) {\n");
        source.append(blockAfter(guide, SECTION_HEADING, 3)).append("    }\n\n");
        source.append("    // --- block 4 (detail switch)\n");
        source.append(blockAfter(guide, SECTION_HEADING, 4)).append('\n');
        source.append("    // --- block 5 (handle a failed call)\n");
        source.append(blockAfter(guide, SECTION_HEADING, 5)).append('\n');
        source.append("    // --- the async completion callback block, wrapped in a method\n").append(STUBS);
        source.append("    static void handle(StubTickets tickets, Ticket ticket) {\n");
        source.append(blockStarting(page("async.md"), FUTURE_PREFIX)).append("    }\n\n");
        source.append("    // --- the observer example, wrapped in a method\n");
        source.append("    static void observeTheSet() {\n");
        source.append(blockStarting(page("commit-and-persistence.md"), OBSERVER_PREFIX)).append("    }\n}\n");

        List<String> diagnostics = compile(workDir, "GuideFragments", source.toString(), coreJar);
        assertTrue(diagnostics.isEmpty(), "the guide's outcome fragments must compile against the published core:\n"
                + String.join("\n", diagnostics) + "\n--- generated GuideFragments.java ---\n" + source);
    }

    /** Compiles the recovery section's five Java blocks against the staged core jar. */
    @Test
    void everyRecoveryFragmentOfTheGuideCompilesAgainstThePublishedCore(@TempDir Path workDir) throws IOException {
        List<String> guide = page("actions-and-failures.md");
        Path coreJar = publishedCoreJar();

        assertJavaBlockCount(guide, RECOVERY_HEADING, 5);

        StringBuilder source = new StringBuilder(RECOVERY_HEADER);
        source.append(blockAfter(guide, RECOVERY_HEADING, 1)).append('\n');
        source.append("    // --- block 2 (the DSL declaration), wrapped in a method\n");
        source.append("    static void declare(MachineKey<Publication, PublicationState, PublicationChange> key,\n");
        source.append("            PublicationAdapter adapter) {\n");
        source.append(blockAfter(guide, RECOVERY_HEADING, 2)).append("    }\n\n");
        source.append("    // --- block 3 (the annotated operation), on a facade\n");
        source.append("    /** Publication facade stand-in for the annotation sample. */\n");
        source.append("    interface Publications {\n");
        source.append(blockAfter(guide, RECOVERY_HEADING, 3)).append("    }\n\n");
        source.append("    // --- block 4 (the most specific type), applied to an edge\n");
        source.append("    static void mostSpecific(\n");
        source.append("            TransitionBuilder<Publication, PublicationState, PublicationChange> edge) {\n");
        source.append("        edge\n").append(blockAfter(guide, RECOVERY_HEADING, 4)).append("        ;\n    }\n\n");
        source.append("    // --- block 5 (reading the kind), wrapped in a method\n");
        source.append("    static void read(StateMachine<Publication, PublicationState, PublicationChange> machine,\n");
        source.append("            Publication publication) {\n");
        source.append(blockAfter(guide, RECOVERY_HEADING, 5)).append("    }\n}\n");

        List<String> diagnostics = compile(workDir, "GuideRecoveryFragments", source.toString(), coreJar);
        assertTrue(diagnostics.isEmpty(), "the guide's recovery fragments must compile against the published core:\n"
                + String.join("\n", diagnostics) + "\n--- generated GuideRecoveryFragments.java ---\n" + source);
    }

    /** Compiles the composition section's eight Java blocks against the staged core jar. */
    @Test
    void everyCompositionFragmentOfTheGuideCompilesAgainstThePublishedCore(@TempDir Path workDir) throws IOException {
        List<String> guide = page("composition.md");
        Path coreJar = publishedCoreJar();

        assertJavaBlockCount(guide, COMPOSITION_HEADING, 8);

        StringBuilder source = new StringBuilder(COMPOSITION_HEADER);
        source.append(blockAfter(guide, COMPOSITION_HEADING, 1)).append('\n');
        source.append("    // --- block 2 (the lookups), on their host\n");
        source.append("    /** Lookup host stand-in. */\n");
        source.append("    static final class BillingLookups {\n");
        source.append("        private final Map<String, Booking> bookings = new ConcurrentHashMap<>();\n");
        source.append("        private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();\n");
        source.append("        void register(Booking booking) { }\n");
        source.append(blockAfter(guide, COMPOSITION_HEADING, 2)).append("    }\n\n");
        source.append("    // --- blocks 3 and 4 (the set, then a booking and its deposit), wrapped in a method\n");
        source.append("    static void book(RoomDesk desk) {\n");
        source.append(blockAfter(guide, COMPOSITION_HEADING, 3));
        source.append(blockAfter(guide, COMPOSITION_HEADING, 4)).append("    }\n\n");
        source.append("    // --- block 5 (the interrupting action), on its host\n");
        source.append("    /** Action host stand-in. */\n");
        source.append("    static final class BookingActions {\n");
        source.append("        BookingActions(RoomDesk desk) { }\n");
        source.append(blockAfter(guide, COMPOSITION_HEADING, 5)).append("    }\n\n");
        appendSecondParentAndSameEntityBlocks(source, guide);

        List<String> diagnostics = compile(workDir, "GuideCompositionFragments", source.toString(), coreJar);
        assertTrue(diagnostics.isEmpty(), "the guide's composition fragments must compile against the published core:\n"
                + String.join("\n", diagnostics) + "\n--- generated GuideCompositionFragments.java ---\n" + source);
    }

    /** Appends the composition section's blocks 6 to 8 and closes the generated class. */
    private static void appendSecondParentAndSameEntityBlocks(StringBuilder source, List<String> guide) {
        source.append("    // --- block 6 (the second parent's binding), on its facade\n");
        source.append("    /** Subscription facade stand-in, which binds the reusable payment machine. */\n");
        source.append(blockAfter(guide, COMPOSITION_HEADING, 6));
        source.append("    interface Subscriptions\n");
        source.append("            extends Machine<Subscription, SubscriptionStatus, SubscriptionTransition> { }\n\n");
        source.append("    // --- block 7 (a child over the parent's own entity)\n");
        source.append(blockAfter(guide, COMPOSITION_HEADING, 7)).append('\n');
        source.append("    // --- block 8 (both machines over one entity)\n");
        source.append(blockAfter(guide, COMPOSITION_HEADING, 8)).append("}\n");
    }

    /** Compiles the flow section's seven Java blocks against the staged core jar. */
    @Test
    void everyFlowFragmentOfTheGuideCompilesAgainstThePublishedCore(@TempDir Path workDir) throws IOException {
        List<String> guide = page("call-flow.md");
        Path coreJar = publishedCoreJar();

        assertJavaBlockCount(guide, FLOW_HEADING, 7);

        StringBuilder source = new StringBuilder(FLOW_HEADER);
        source.append(blockAfter(guide, FLOW_HEADING, 1)).append('\n');
        source.append("    // --- block 2 (the order's action), on its host\n");
        source.append("    /** Order action host stand-in. */\n");
        source.append("    static final class OrderActions {\n");
        source.append(blockAfter(guide, FLOW_HEADING, 2)).append("    }\n\n");
        source.append("    // --- blocks 3 and 4 (the item's action and its hook), on their host\n");
        source.append("    /** Item action host stand-in. */\n");
        source.append("    static final class ItemActions {\n");
        source.append(blockAfter(guide, FLOW_HEADING, 3)).append('\n');
        source.append(blockAfter(guide, FLOW_HEADING, 4)).append("    }\n\n");
        source.append("    // --- block 5 (reading the flow after the call), on its host\n");
        source.append("    /** Cancellation handler stand-in. */\n");
        source.append("    static final class CancellationHandler {\n");
        source.append(blockAfter(guide, FLOW_HEADING, 5)).append("    }\n\n");
        source.append("    // --- block 6 (a registry whose order machine records its trail), on its host\n");
        source.append("    /** Registry factory stand-in. */\n");
        source.append("    static final class Shop {\n");
        source.append(blockAfter(guide, FLOW_HEADING, 6)).append("    }\n\n");
        source.append("    // --- block 7 (the trail as lines), on its host\n");
        source.append("    /** Trail printer stand-in. */\n");
        source.append("    static final class Timeline {\n");
        source.append(blockAfter(guide, FLOW_HEADING, 7)).append("    }\n}\n");

        List<String> diagnostics = compile(workDir, "GuideFlowFragments", source.toString(), coreJar);
        assertTrue(diagnostics.isEmpty(), "the guide's flow fragments must compile against the published core:\n"
                + String.join("\n", diagnostics) + "\n--- generated GuideFlowFragments.java ---\n" + source);
    }

    /**
     * Fails unless {@code heading} has {@code expected} java blocks after it and before the next heading of its
     * level or higher. Headings are matched outside code fences only.
     */
    private static void assertJavaBlockCount(List<String> guide, String heading, int expected) {
        int count = javaBlocksAfter(guide, heading).size();
        assertEquals(expected, count, "the guide's section '" + heading + "' has " + count
                + " java blocks; this test covers exactly " + expected + ": update the test to match the section");
    }

    /**
     * Returns the {@code n}th java block after {@code heading} and before the next heading of its level or higher.
     * Headings are matched outside code fences only. Fails when the block is missing or blank.
     */
    private static String blockAfter(List<String> guide, String heading, int n) {
        List<MarkdownPage.Block> blocks = javaBlocksAfter(guide, heading);
        String block = n <= blocks.size() ? blocks.get(n - 1).content() : "";
        assertFalse(block.isBlank(), "no non-blank java block number " + n + " after the heading '"
                + heading + "' of the guide");
        return block;
    }

    /**
     * Returns the java blocks after {@code heading} and before the next heading of its level or higher. Headings
     * are matched outside code fences only.
     */
    private static List<MarkdownPage.Block> javaBlocksAfter(List<String> guide, String heading) {
        return MarkdownPage.sectionBlocks(guide, heading, "java");
    }

    /** Reads the guide's page {@code name} from the directory the {@code docsPath} property names. */
    private static List<String> page(String name) throws IOException {
        return MarkdownPage.read(Path.of(property("docsPath")), name);
    }

    /** Returns the first java block whose first line starts with the prefix. Fails when there is none. */
    private static String blockStarting(List<String> guide, String prefix) {
        String block = MarkdownPage.allBlocks(guide, "java").stream()
                .map(MarkdownPage.Block::content)
                .filter((String content) -> content.startsWith(prefix))
                .findFirst().orElse("");
        assertFalse(block.isEmpty(), "no non-empty java block starting with '" + prefix + "' in the guide");
        return block;
    }

    private static List<String> compile(Path workDir, String className, String content, Path coreJar)
            throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "this test needs a JDK compiler, not a JRE");
        Path source = Files.writeString(workDir.resolve(className + ".java"), content);
        Path classes = Files.createDirectories(workDir.resolve("classes"));
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(collector, null, UTF_8)) {
            Iterable<? extends JavaFileObject> units = manager.getJavaFileObjectsFromPaths(List.of(source));
            List<String> options = List.of("--release", "21", "-proc:none", "-Xlint:all,-serial", "-Werror",
                    "-classpath", coreJar.toString(), "-d", classes.toString());
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

    /** Resolves the staged core jar shared by every fragment compilation. */
    private static Path publishedCoreJar() {
        String version = property("artifactVersion");
        Path coreJar = Path.of(property("artifactRepository"), "io/github/graphmaquina/graphmaquina-core", version,
                "graphmaquina-core-" + version + ".jar");
        assertTrue(Files.isRegularFile(coreJar), "the published core jar is missing: " + coreJar);
        return coreJar;
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        assertNotNull(value, "run this test through :graphmaquina-test:publishedArtifactTest; missing " + name);
        return value;
    }
}
