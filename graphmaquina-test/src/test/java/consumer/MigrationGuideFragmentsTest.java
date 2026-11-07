package consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Builds and runs the Java blocks of the guide's two migration pages against locally staged Maven artifacts. Every
 * block is on the patterns page, {@code migration-patterns.md}; the concept page,
 * {@code migrate-from-another-library.md}, holds none, and {@link #everyJavaBlockIsCompiled()} counts the blocks of
 * both. The blocks are cut out verbatim and
 * written into a temporary Gradle project whose scripts come from the {@code installation.md} and {@code quickstart.md}
 * pages, as {@link ReadmeQuickstartTest} writes them, with one package per pattern. Each file starts with its package,
 * static imports of the constants of the package's other enums, and the imports the blocks leave out. A block that
 * declares whole types is written as it is; a block that is a facade member, a facade annotation or statements is
 * wrapped in the facade or the method its section names. Around the blocks, this test writes the entities, enums,
 * facades and sets that each section describes in prose, and one JUnit test per pattern that checks what the guide says
 * the pattern does, such as the automatic block on an arrival with no deferred request. The GraphMaquina Gradle plugin
 * generates the typed vocabulary, and the project's {@code test} task runs those JUnit tests. A heading or block that
 * cannot be found fails the test instead of building nothing. The pages' directory, the Gradle executable, the
 * repository and the release come from the system properties that {@code publishedArtifactTest} sets.
 */
class MigrationGuideFragmentsTest {

    private static final String CONCEPTS = "migrate-from-another-library.md";
    private static final String PATTERNS = "migration-patterns.md";
    /** The Java blocks of both migration pages, all on the patterns page; each one is built below. */
    private static final int MIGRATION_BLOCKS = 12;

    private static final String ENTRY_EXIT = "## Entry and exit actions";
    private static final String RESUME = "### Resume a flat machine";
    private static final String RESTORE = "### Restore a sub-machine";
    private static final String REGIONS = "## Parallel regions";
    private static final String TIMERS = "## Timers";
    private static final String DEFERRED = "## Deferred events";

    /** A section of the patterns page and the number of Java blocks it holds. */
    private record Section(String heading, int blocks) { }

    private static final List<Section> SECTIONS = List.of(new Section(ENTRY_EXIT, 1), new Section(RESUME, 2),
            new Section(RESTORE, 1), new Section(REGIONS, 2), new Section(TIMERS, 3), new Section(DEFERRED, 3));

    private static final String BASE = "com/example/migration/";
    private static final Pattern TYPE = Pattern.compile(
            "^(?:public )?(?:final )?(?:class|interface|enum|record) (\\w+)", Pattern.MULTILINE);
    private static final Pattern ONE_LINE_ENUM = Pattern.compile("^public enum \\w+ \\{.*}$");
    private static final Pattern TEST_METHOD = Pattern.compile("@Test\\s+void (\\w+)\\(");

    private static final String IMPORTS = """
            import io.github.graphmaquina.annotation.GenerateGraphApi;
            import io.github.graphmaquina.annotation.GraphAction;
            import io.github.graphmaquina.annotation.GraphGuard;
            import io.github.graphmaquina.annotation.GraphHook;
            import io.github.graphmaquina.annotation.GraphPeers;
            import io.github.graphmaquina.annotation.GraphProperty;
            import io.github.graphmaquina.annotation.StateGraphs;
            import io.github.graphmaquina.api.ActionOutcome;
            import io.github.graphmaquina.api.CommitContext;
            import io.github.graphmaquina.api.GuardContext;
            import io.github.graphmaquina.api.GuardResult;
            import io.github.graphmaquina.api.Machine;
            import io.github.graphmaquina.api.Machines;
            import io.github.graphmaquina.api.Outcome;
            import io.github.graphmaquina.api.OutcomeCode;
            import io.github.graphmaquina.api.TransitionContext;
            import java.time.Duration;
            import java.time.Instant;
            import java.time.InstantSource;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import java.util.concurrent.Executors;
            import java.util.concurrent.ScheduledExecutorService;
            import java.util.concurrent.TimeUnit;
            """;

    private static final String TEST_IMPORTS = """
            import static io.github.graphmaquina.test.GraphAssertions.assertThat;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            import static org.junit.jupiter.api.Assertions.assertThrows;
            import static org.junit.jupiter.api.Assertions.assertTrue;

            import io.github.graphmaquina.api.IgnoreReason;
            import io.github.graphmaquina.api.SubmachineStart;
            import io.github.graphmaquina.api.TransitionRecord;
            import io.github.graphmaquina.error.AfterCommitException;
            import io.github.graphmaquina.error.TransitionAbortedException;
            import java.util.concurrent.atomic.AtomicReference;
            import org.junit.jupiter.api.AfterEach;
            import org.junit.jupiter.api.Test;
            """;

    // --- "Entry and exit actions": the document machine around the guide's DocumentLifecycle hook
    private static final String LIFECYCLE_FIXTURES = """
            public enum DocumentState { DRAFT, REVIEW, PUBLISHED }
            public enum DocumentTransition { SUBMIT, COMMENT, PUBLISH, SEND_BACK, WITHDRAW }
            public final class Document {
                @GraphProperty
                private final String id;

                @GraphProperty
                private DocumentState state = DocumentState.DRAFT;

                private final String title;
                private final boolean publishable;

                public Document(String id, String title, boolean publishable) {
                    this.id = id;
                    this.title = title;
                    this.publishable = publishable;
                }

                public String getId() { return id; }
                public DocumentState getState() { return state; }
                public void setState(DocumentState state) { this.state = state; }
                public String getTitle() { return title; }
                public boolean isPublishable() { return publishable; }
            }
            public interface ReviewBoard {
                void assign(Document document);

                void release(Document document);
            }
            public final class DocumentRules {
                private DocumentRules() { }

                @GraphGuard
                public static GuardResult hasTitle(GuardContext<Document, DocumentState, DocumentTransition> ctx) {
                    return ctx.entity().getTitle().isBlank()
                            ? GuardResult.reject("a document needs a title")
                            : GuardResult.pass();
                }

                @GraphAction
                public static ActionOutcome publish(
                        TransitionContext<Document, DocumentState, DocumentTransition> ctx) {
                    return ctx.entity().isPublishable()
                            ? ActionOutcome.ok()
                            : ActionOutcome.failed("the document is not ready");
                }

                // the own hook of WITHDRAW, which replaces the default one
                @GraphHook
                public static void withdrawn(Document document) { }
            }
            @GenerateGraphApi
            @DocumentsGraph.Definition(
                initial = DRAFT,
                terminals = PUBLISHED,
                allowsCycle = REVIEW,
                state = DocumentRefs.State.class,
                id = DocumentRefs.Id.class,
                afterCommit = DocumentLifecycleRefs.OnHop.class)
            @DocumentsGraph.StatePolicy(state = DRAFT, otherwise = "submit the document")
            @DocumentsGraph.StatePolicy(state = REVIEW, otherwise = "publish, comment on or withdraw the document")
            @DocumentsGraph.Transition(id = SEND_BACK, from = REVIEW, to = DRAFT)
            public interface Documents extends Machine<Document, DocumentState, DocumentTransition> {
                @DocumentsGraph.Transition(id = SUBMIT, from = DRAFT, to = REVIEW,
                    guards = DocumentRulesRefs.HasTitle.class)
                Outcome<Document, DocumentState, DocumentTransition> submit(Document document);

                @DocumentsGraph.Transition(id = COMMENT, from = REVIEW, to = REVIEW)
                Outcome<Document, DocumentState, DocumentTransition> comment(Document document);

                @DocumentsGraph.Transition(id = PUBLISH, from = REVIEW, to = PUBLISHED,
                    action = DocumentRulesRefs.Publish.class, onFailure = SEND_BACK)
                Outcome<Document, DocumentState, DocumentTransition> publish(Document document);

                @DocumentsGraph.Transition(id = WITHDRAW, from = REVIEW, to = DRAFT,
                    afterCommit = DocumentRulesRefs.Withdrawn.class)
                Outcome<Document, DocumentState, DocumentTransition> withdraw(Document document);
            }
            @StateGraphs
            public interface DocumentMachines extends Machines {
                Documents documents();
            }
            """;

    private static final String LIFECYCLE_TEST = """
            final class RecordingBoard implements ReviewBoard {
                final List<String> calls = new ArrayList<>();
                boolean busy;

                @Override
                public void assign(Document document) {
                    if (busy) {
                        throw new IllegalStateException("no reviewer is free");
                    }
                    calls.add("assign " + document.getId());
                }

                @Override
                public void release(Document document) {
                    calls.add("release " + document.getId());
                }
            }
            class DocumentLifecycleTest {
                private final RecordingBoard board = new RecordingBoard();
                private final DocumentMachines machines =
                        DocumentMachinesRuntime.builder(new DocumentLifecycle(board)).build();
                private final Documents documents = machines.documents();

                @AfterEach
                void close() {
                    machines.close();
                }

                @Test
                void theHookRunsTheExitAndEntryActionsOfEachCommittedHop() {
                    Document document = new Document("D1", "Spec", true);
                    assertEquals(List.of(), board.calls);
                    documents.submit(document);
                    documents.publish(document);
                    assertEquals(List.of("assign D1", "release D1"), board.calls);
                }

                @Test
                void aRefusedRequestRunsNothing() {
                    Document untitled = new Document("D2", "", true);
                    assertThat(documents.submit(untitled)).isRejected().isRejectedByGuardNamed("hasTitle");
                    assertThat(documents.publish(untitled)).isRejected().hasNoTransition();
                    assertEquals(List.of(), board.calls);
                }

                @Test
                void aFailureRouteRunsTheExitActionToo() {
                    Document document = new Document("D3", "Spec", false);
                    documents.submit(document);
                    assertThat(documents.publish(document)).isFailed().hasFailureTransition(SEND_BACK).movedTo(DRAFT);
                    assertEquals(List.of("assign D3", "release D3"), board.calls);
                }

                @Test
                void theDispatcherSkipsASelfLoop() {
                    Document document = new Document("D4", "Spec", true);
                    documents.submit(document);
                    assertThat(documents.comment(document)).isApplied().movedTo(REVIEW);
                    assertEquals(List.of("assign D4"), board.calls);
                }

                @Test
                void anEdgeWithItsOwnHookDoesNotRunTheDefaultOne() {
                    Document document = new Document("D5", "Spec", true);
                    documents.submit(document);
                    assertThat(documents.withdraw(document)).isApplied().movedTo(DRAFT);
                    assertEquals(List.of("assign D5"), board.calls);
                }

                @Test
                void aThrowingEntryActionCannotVetoTheMove() {
                    board.busy = true;
                    Document document = new Document("D6", "Spec", true);
                    assertThrows(AfterCommitException.class, () -> documents.submit(document));
                    assertEquals(REVIEW, document.getState());
                }
            }
            """;

    // --- "Resume a flat machine": the wizard entity, its enums and its set around the guide's machine
    private static final String WIZARD_FIXTURES = """
            public enum WizardState { STEP_ONE, STEP_TWO, PAUSED, RESUMING, DONE }
            public enum WizardTransition { NEXT, FINISH, PAUSE, RESUME, BACK_TO_ONE, BACK_TO_TWO }
            public final class Wizard {
                @GraphProperty
                private final String id;

                @GraphProperty
                private WizardState state = WizardState.STEP_ONE;

                private WizardState resumeAt = WizardState.STEP_ONE;

                public Wizard(String id) {
                    this.id = id;
                }

                public String getId() { return id; }
                public WizardState getState() { return state; }
                public void setState(WizardState state) { this.state = state; }
                public WizardState getResumeAt() { return resumeAt; }
                public void setResumeAt(WizardState resumeAt) { this.resumeAt = resumeAt; }
            }
            @StateGraphs
            public interface WizardMachines extends Machines {
                Wizards wizards();
            }
            """;

    private static final String WIZARD_TEST = """
            class WizardResumeTest {
                private final WizardMachines machines = WizardMachinesRuntime.builder(new WizardActions()).build();
                private final Wizards wizards = machines.wizards();

                @AfterEach
                void close() {
                    machines.close();
                }

                @Test
                void resumeReportsTheIntermediateStateAndReturnsToStepTwo() {
                    Wizard wizard = new Wizard("W1");
                    wizards.next(wizard);
                    wizards.pause(wizard);
                    assertEquals(STEP_TWO, wizard.getResumeAt());
                    assertThat(wizards.resume(wizard)).isApplied().movedTo(RESUMING)
                            .hasEntityState(Wizard::getState, STEP_TWO)
                            .automaticSteps().forEntity(WizardsRuntime.KEY, wizard).single()
                            .isDone().outcome().isApplied().requestedTransition(BACK_TO_TWO);
                }

                @Test
                void resumeReturnsToStepOne() {
                    Wizard wizard = new Wizard("W2");
                    wizards.pause(wizard);
                    assertThat(wizards.resume(wizard)).isApplied().movedTo(RESUMING)
                            .hasEntityState(Wizard::getState, STEP_ONE)
                            .automaticSteps().forEntity(WizardsRuntime.KEY, wizard).single()
                            .isDone().outcome().isApplied().requestedTransition(BACK_TO_ONE);
                }
            }
            """;

    // --- "Restore a sub-machine": the application, its checks sub-machine and its set around the guide's host
    private static final String RESTORE_FIXTURES = """
            public enum ApplicationStatus { DRAFT, CHECKS, ON_HOLD, APPROVED }
            public enum ApplicationTransition { START, HOLD, RESUME, CHECKED }
            public enum CheckStatus { IDENTITY, INCOME, DONE }
            public enum CheckTransition { VERIFY_IDENTITY, VERIFY_INCOME, RESTORE_INCOME }
            public final class Application {
                @GraphProperty
                private final String id;

                @GraphProperty
                private ApplicationStatus status = ApplicationStatus.DRAFT;

                @GraphProperty
                private CheckStatus check = CheckStatus.IDENTITY;

                private CheckStatus resumeCheckAt = CheckStatus.IDENTITY;
                private boolean incomeProofKept = true;

                public Application(String id) {
                    this.id = id;
                }

                public String getId() { return id; }
                public ApplicationStatus getStatus() { return status; }
                public void setStatus(ApplicationStatus status) { this.status = status; }
                public CheckStatus getCheck() { return check; }
                public void setCheck(CheckStatus check) { this.check = check; }
                public CheckStatus getResumeCheckAt() { return resumeCheckAt; }
                public void setResumeCheckAt(CheckStatus resumeCheckAt) { this.resumeCheckAt = resumeCheckAt; }
                public boolean isIncomeProofKept() { return incomeProofKept; }
                public void setIncomeProofKept(boolean incomeProofKept) { this.incomeProofKept = incomeProofKept; }
            }
            public final class CheckRules {
                private CheckRules() { }

                @GraphGuard
                public static GuardResult incomeProofKept(
                        GuardContext<Application, CheckStatus, CheckTransition> ctx) {
                    return ctx.entity().isIncomeProofKept()
                            ? GuardResult.pass()
                            : GuardResult.reject("the income proof is gone");
                }
            }
            @GenerateGraphApi
            @ChecksGraph.Definition(
                initial = IDENTITY,
                terminals = DONE,
                state = ApplicationRefs.Check.class,
                id = ApplicationRefs.Id.class)
            @ChecksGraph.StatePolicy(state = IDENTITY, otherwise = "verify the identity")
            @ChecksGraph.StatePolicy(state = INCOME, otherwise = "verify the income")
            @ChecksGraph.Transition(id = RESTORE_INCOME, from = IDENTITY, to = INCOME,
                guards = CheckRulesRefs.IncomeProofKept.class)
            public interface Checks extends Machine<Application, CheckStatus, CheckTransition> {
                @ChecksGraph.Transition(id = VERIFY_IDENTITY, from = IDENTITY, to = INCOME)
                Outcome<Application, CheckStatus, CheckTransition> verifyIdentity(Application application);

                @ChecksGraph.Transition(id = VERIFY_INCOME, from = INCOME, to = DONE)
                Outcome<Application, CheckStatus, CheckTransition> verifyIncome(Application application);
            }
            @GenerateGraphApi
            @GraphPeers(Checks.class)
            @ApplicationsGraph.Definition(
                initial = DRAFT,
                terminals = APPROVED,
                allowsCycle = ON_HOLD,
                state = ApplicationRefs.Status.class,
                id = ApplicationRefs.Id.class)
            @ApplicationsGraph.StatePolicy(state = DRAFT, otherwise = "start the checks")
            @ApplicationsGraph.StatePolicy(state = CHECKS, otherwise = "the checks decide, or hold the application")
            @ApplicationsGraph.StatePolicy(state = ON_HOLD, otherwise = "resume the application")
            @ApplicationsGraph.Transition(id = CHECKED, from = CHECKS, to = APPROVED)
            @ApplicationsChecks.Submachine(state = CHECKS,
                exits = @ApplicationsChecks.Exit(on = DONE, transition = CHECKED))
            public interface Applications extends Machine<Application, ApplicationStatus, ApplicationTransition> {
                @ApplicationsGraph.Transition(id = START, from = DRAFT, to = CHECKS)
                Outcome<Application, ApplicationStatus, ApplicationTransition> start(Application application);

                @ApplicationsGraph.Transition(id = HOLD, from = CHECKS, to = ON_HOLD,
                    afterCommit = CheckHistoryRefs.RememberCheck.class)
                Outcome<Application, ApplicationStatus, ApplicationTransition> hold(Application application);

                @ApplicationsGraph.Transition(id = RESUME, from = ON_HOLD, to = CHECKS,
                    action = CheckHistoryRefs.RestoreCheck.class)
                Outcome<Application, ApplicationStatus, ApplicationTransition> resume(Application application);
            }
            @StateGraphs
            public interface ApplicationMachines extends Machines {
                Applications applications();

                Checks checks();
            }
            """;

    private static final String RESTORE_TEST = """
            class CheckRestoreTest {
                private final ApplicationMachines machines = ApplicationMachinesRuntime.builder().build();

                @AfterEach
                void close() {
                    machines.close();
                }

                private Application heldAtIncome(String id) {
                    Application application = new Application(id);
                    machines.applications().start(application);
                    machines.checks().verifyIdentity(application);
                    machines.applications().hold(application);
                    return application;
                }

                private static List<String> hops(Outcome<?, ?, ?> outcome) {
                    return outcome.records().stream()
                            .map((TransitionRecord<?, ?, ?> hop) -> hop.transition() + "@" + hop.depth())
                            .toList();
                }

                @Test
                void resumeRestartsTheChecksAndRestoresTheirStepInTheSameCommit() {
                    Application application = heldAtIncome("A1");
                    assertEquals(INCOME, application.getResumeCheckAt());
                    Outcome<Application, ApplicationStatus, ApplicationTransition> resumed =
                            machines.applications().resume(application);
                    assertThat(resumed).isApplied().movedTo(CHECKS);
                    assertEquals(INCOME, application.getCheck());
                    assertEquals(1, resumed.starts().size());
                    SubmachineStart<?, ?, ?> start = resumed.starts().getFirst();
                    assertEquals(List.of(INCOME, IDENTITY), List.<Object>of(start.previous(), start.initial()));
                    assertEquals(List.of("RESUME@0", "RESTORE_INCOME@1"), hops(resumed));
                }

                @Test
                void withoutARememberedStepTheChecksRestartAtTheirInitialState() {
                    Application application = heldAtIncome("A2");
                    application.setResumeCheckAt(IDENTITY);
                    Outcome<Application, ApplicationStatus, ApplicationTransition> resumed =
                            machines.applications().resume(application);
                    assertEquals(IDENTITY, application.getCheck());
                    assertEquals(List.of("RESUME@0"), hops(resumed));
                }

                @Test
                void aRefusedRestoreAbortsTheResume() {
                    Application application = heldAtIncome("A3");
                    application.setIncomeProofKept(false);
                    assertThrows(TransitionAbortedException.class, () -> machines.applications().resume(application));
                    assertEquals(ON_HOLD, application.getStatus());
                    assertEquals(INCOME, application.getCheck());
                }
            }
            """;

    // --- "Parallel regions": the order and its enums around the guide's two regions, plus a third region
    private static final String REGION_FIXTURES = """
            public enum PaymentStatus { UNPAID, PAID }
            public enum PaymentTransition { PAY }
            public enum PackingStatus { PACKING, PACKED, SHIPPED }
            public enum PackingTransition { PACK, SHIP }
            public enum InvoiceStatus { OPEN, SENT }
            public enum InvoiceTransition { SEND }
            public final class Order {
                @GraphProperty
                private final String id;

                @GraphProperty
                private PaymentStatus payment = PaymentStatus.UNPAID;

                @GraphProperty
                private PackingStatus packing = PackingStatus.PACKING;

                @GraphProperty
                private InvoiceStatus invoice = InvoiceStatus.OPEN;

                public Order(String id) {
                    this.id = id;
                }

                public String getId() { return id; }
                public PaymentStatus getPayment() { return payment; }
                public void setPayment(PaymentStatus payment) { this.payment = payment; }
                public PackingStatus getPacking() { return packing; }
                public void setPacking(PackingStatus packing) { this.packing = packing; }
                public InvoiceStatus getInvoice() { return invoice; }
                public void setInvoice(InvoiceStatus invoice) { this.invoice = invoice; }
            }
            // a third region, which only this test declares, to move the order without the two others
            @GenerateGraphApi
            @OrderInvoicesGraph.Definition(
                initial = OPEN,
                terminals = SENT,
                state = OrderRefs.Invoice.class,
                id = OrderRefs.Id.class)
            @OrderInvoicesGraph.StatePolicy(state = OPEN, otherwise = "send the invoice")
            public interface OrderInvoices extends Machine<Order, InvoiceStatus, InvoiceTransition> {
                @OrderInvoicesGraph.Transition(id = SEND, from = OPEN, to = SENT)
                Outcome<Order, InvoiceStatus, InvoiceTransition> send(Order order);
            }
            @StateGraphs
            public interface OrderRegionsWithInvoices extends Machines {
                OrderPayments payments();

                OrderPacking packing();

                OrderInvoices invoices();
            }
            """;

    private static final String REGION_TEST = """
            class OrderRegionsTest {
                private final OrderRegions machines = OrderRegionsRuntime.builder().build();

                @AfterEach
                void close() {
                    machines.close();
                }

                private static List<String> hops(Outcome<?, ?, ?> outcome) {
                    return outcome.records().stream()
                            .map((TransitionRecord<?, ?, ?> hop) -> hop.transition() + "@" + hop.depth())
                            .toList();
                }

                @Test
                void thePaymentArrivingLastJoinsThroughItsCascade() {
                    Order order = new Order("O1");
                    assertThat(machines.packing().pack(order)).isApplied().movedTo(PACKED).automaticBlock()
                            .hasReason(IgnoreReason.NO_CANDIDATE_ADMITTED).rejections().single()
                            .transition(SHIP).isRejectedByGuardNamed("paid").hasReason("the order is not paid");
                    Outcome<Order, PaymentStatus, PaymentTransition> paid = machines.payments().pay(order);
                    assertThat(paid).isApplied().movedTo(PAID).automaticSteps().isEmpty();
                    assertEquals(SHIPPED, order.getPacking());
                    assertEquals(List.of("PAY@0", "SHIP@1"), hops(paid));
                }

                @Test
                void thePackingArrivingLastJoinsThroughItsAutomaticEdge() {
                    Order order = new Order("O2");
                    assertEquals(List.of("PAY@0"), hops(machines.payments().pay(order)));
                    assertEquals(PACKING, order.getPacking());
                    assertThat(machines.packing().pack(order)).isApplied().movedTo(PACKED)
                            .hasEntityState(Order::getPacking, SHIPPED)
                            .automaticSteps().forEntity(OrderPackingRuntime.KEY, order).single()
                            .isDone().outcome().isApplied().requestedTransition(SHIP);
                }

                @Test
                void aMoveOfAnotherRegionDoesNotTryTheJoinButAdvanceDoes() {
                    try (OrderRegionsWithInvoices regions = OrderRegionsWithInvoicesRuntime.builder().build()) {
                        Order order = new Order("O3");
                        regions.packing().pack(order);
                        // paid outside any call, as when the order is loaded
                        order.setPayment(PaymentStatus.PAID);
                        assertThat(regions.invoices().send(order)).isApplied().movedTo(SENT);
                        assertEquals(PACKED, order.getPacking());
                        assertThat(regions.packing().advance(order)).isApplied().requestedTransition(SHIP)
                                .hasEntityState(Order::getPacking, SHIPPED);
                    }
                }
            }
            """;

    // --- "Timers": the reservation, its operations and both variants' sets around the guide's blocks
    private static final String TIMER_FIXTURES = """
            public enum ReservationState { HELD, CONFIRMED, EXPIRED }
            public enum ReservationTransition { CONFIRM, EXTEND, EXPIRE }
            public final class Reservation {
                @GraphProperty
                private final String id;

                @GraphProperty
                private ReservationState state = ReservationState.HELD;

                private Instant expiresAt;

                public Reservation(String id, Instant expiresAt) {
                    this.id = id;
                    this.expiresAt = expiresAt;
                }

                public String getId() { return id; }
                public ReservationState getState() { return state; }
                public void setState(ReservationState state) { this.state = state; }
                public Instant getExpiresAt() { return expiresAt; }
                public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
            }
            public final class ReservationActions {
                private ReservationActions() { }

                // EXTEND keeps the reservation fifteen minutes longer
                @GraphAction
                public static ActionOutcome extend(
                        TransitionContext<Reservation, ReservationState, ReservationTransition> ctx) {
                    Reservation reservation = ctx.entity();
                    reservation.setExpiresAt(reservation.getExpiresAt().plus(Duration.ofMinutes(15)));
                    return ActionOutcome.ok();
                }
            }
            // the automatic variant, which the guide describes in prose: the scheduler calls advance
            @GenerateGraphApi
            @TimedReservationsGraph.Definition(
                initial = HELD,
                terminals = {CONFIRMED, EXPIRED},
                allowsCycle = HELD,
                state = ReservationRefs.State.class,
                id = ReservationRefs.Id.class)
            @TimedReservationsGraph.StatePolicy(state = HELD, otherwise = "confirm or extend the reservation")
            @TimedReservationsGraph.Transition(id = EXPIRE, from = HELD, to = EXPIRED, automatic = true,
                guards = ReservationGuardsRefs.Expired.class)
            public interface TimedReservations extends Machine<Reservation, ReservationState, ReservationTransition> {
                @TimedReservationsGraph.Transition(id = CONFIRM, from = HELD, to = CONFIRMED)
                Outcome<Reservation, ReservationState, ReservationTransition> confirm(Reservation reservation);

                @TimedReservationsGraph.Transition(id = EXTEND, from = HELD, to = HELD,
                    action = ReservationActionsRefs.Extend.class)
                Outcome<Reservation, ReservationState, ReservationTransition> extend(Reservation reservation);
            }
            @StateGraphs
            public interface ReservationMachines extends Machines {
                Reservations reservations();
            }
            @StateGraphs
            public interface TimedReservationMachines extends Machines {
                TimedReservations reservations();
            }
            """;

    /** The facade that the timer section's operation block goes into, up to that block. */
    private static final String RESERVATIONS_HEAD = """
            @GenerateGraphApi
            @ReservationsGraph.Definition(
                initial = HELD,
                terminals = {CONFIRMED, EXPIRED},
                allowsCycle = HELD,
                state = ReservationRefs.State.class,
                id = ReservationRefs.Id.class)
            @ReservationsGraph.StatePolicy(state = HELD, otherwise = "confirm, extend or expire the reservation")
            public interface Reservations extends Machine<Reservation, ReservationState, ReservationTransition> {
                @ReservationsGraph.Transition(id = CONFIRM, from = HELD, to = CONFIRMED)
                Outcome<Reservation, ReservationState, ReservationTransition> confirm(Reservation reservation);

                @ReservationsGraph.Transition(id = EXTEND, from = HELD, to = HELD,
                    action = ReservationActionsRefs.Extend.class)
                Outcome<Reservation, ReservationState, ReservationTransition> extend(Reservation reservation);

            """;

    /** The method that the timer section's scheduler statements go into, which this test compiles but never calls. */
    private static final String SCHEDULING_HEAD = """
            public final class Scheduling {
                private Scheduling() { }

                public static void schedule(Reservations reservations, Reservation reservation) {
            """;

    private static final String TIMER_TEST = """
            class ReservationTimerTest {
                private static final Instant HELD_AT = Instant.parse("2026-11-07T10:00:00Z");
                private final AtomicReference<Instant> now = new AtomicReference<>(HELD_AT);
                private final ReservationGuards guards = new ReservationGuards(now::get);
                private final ReservationMachines machines = ReservationMachinesRuntime.builder(guards).build();
                private final TimedReservationMachines timed = TimedReservationMachinesRuntime.builder(guards).build();
                private final Reservations reservations = machines.reservations();

                @AfterEach
                void close() {
                    machines.close();
                    timed.close();
                }

                private static Reservation heldFifteenMinutes(String id) {
                    return new Reservation(id, HELD_AT.plus(Duration.ofMinutes(15)));
                }

                @Test
                void theScheduledCallIsRefusedBeforeTheDeadline() {
                    Reservation reservation = heldFifteenMinutes("R1");
                    now.set(HELD_AT.plus(Duration.ofMinutes(14)));
                    assertThat(reservations.expire(reservation)).isRejected().isRejectedByGuardNamed("expired")
                            .hasReason("the reservation has not expired yet");
                    assertEquals(HELD, reservation.getState());
                }

                @Test
                void nothingFiresUntilTheScheduledCall() {
                    Reservation reservation = heldFifteenMinutes("R2");
                    now.set(HELD_AT.plus(Duration.ofMinutes(16)));
                    assertEquals(HELD, reservation.getState());
                    assertThat(reservations.expire(reservation)).isApplied().movedTo(EXPIRED);
                }

                @Test
                void aStaleCallIsRefusedByTheStateOrByTheGuard() {
                    Reservation confirmed = heldFifteenMinutes("R3");
                    reservations.confirm(confirmed);
                    Reservation extended = heldFifteenMinutes("R4");
                    reservations.extend(extended);
                    now.set(HELD_AT.plus(Duration.ofMinutes(16)));
                    assertThat(reservations.expire(confirmed)).isRejected().hasNoTransition();
                    assertThat(reservations.expire(extended)).isRejected().isRejectedByGuardNamed("expired");
                }

                @Test
                void theAutomaticVariantReportsABlockOnAnArrivalBeforeTheDeadline() {
                    Reservation reservation = heldFifteenMinutes("R5");
                    assertThat(timed.reservations().extend(reservation)).isApplied().movedTo(HELD).automaticBlock()
                            .hasReason(IgnoreReason.NO_CANDIDATE_ADMITTED).rejections().single()
                            .transition(EXPIRE).isRejectedByGuardNamed("expired");
                    now.set(HELD_AT.plus(Duration.ofMinutes(31)));
                    assertEquals(HELD, reservation.getState());
                    assertThat(timed.reservations().advance(reservation)).isApplied().requestedTransition(EXPIRE)
                            .hasEntityState(Reservation::getState, EXPIRED);
                }
            }
            """;

    // --- "Deferred events": the order, its enums and its set around the guide's blocks
    private static final String DEFERRED_FIXTURES = """
            public enum OrderStatus { NEW, PROCESSING, READY, SHIPPED, CANCELLED }
            public enum OrderTransition { START, CANCEL, FINISH, SHIP, CANCEL_WHEN_READY }
            public final class Order {
                @GraphProperty
                private final String id;

                @GraphProperty
                private OrderStatus status = OrderStatus.NEW;

                private boolean cancellationRequested;

                public Order(String id) {
                    this.id = id;
                }

                public String getId() { return id; }
                public OrderStatus getStatus() { return status; }
                public void setStatus(OrderStatus status) { this.status = status; }
                public boolean isCancellationRequested() { return cancellationRequested; }
                public void requestCancellation() { cancellationRequested = true; }
            }
            @StateGraphs
            public interface OrderMachines extends Machines {
                Orders orders();
            }
            """;

    /** The annotations of the facade before the deferred section's annotation block. */
    private static final String ORDERS_HEAD = """
            @GenerateGraphApi
            @OrdersGraph.Definition(
                initial = NEW,
                terminals = {SHIPPED, CANCELLED},
                state = OrderRefs.Status.class,
                id = OrderRefs.Id.class)
            @OrdersGraph.StatePolicy(state = NEW, otherwise = "start or cancel the order")
            @OrdersGraph.StatePolicy(state = PROCESSING, otherwise = "the order is being processed")
            @OrdersGraph.StatePolicy(state = READY, otherwise = "ship or cancel the order")
            """;

    /** The facade after the deferred section's annotation block. */
    private static final String ORDERS_BODY = """
            public interface Orders extends Machine<Order, OrderStatus, OrderTransition> {
                @OrdersGraph.Transition(id = START, from = NEW, to = PROCESSING)
                Outcome<Order, OrderStatus, OrderTransition> start(Order order);

                @OrdersGraph.Transition(id = CANCEL, from = {NEW, READY}, to = CANCELLED)
                Outcome<Order, OrderStatus, OrderTransition> cancel(Order order);

                @OrdersGraph.Transition(id = FINISH, from = PROCESSING, to = READY)
                Outcome<Order, OrderStatus, OrderTransition> finish(Order order);

                @OrdersGraph.Transition(id = SHIP, from = READY, to = SHIPPED)
                Outcome<Order, OrderStatus, OrderTransition> ship(Order order);
            }
            """;

    /** The method that the deferred section's application statements go into. */
    private static final String CANCELLATIONS_HEAD = """
            public final class Cancellations {
                private Cancellations() { }

                public static void cancel(Orders orders, Order order) {
            """;

    private static final String DEFERRED_TEST = """
            class DeferredCancellationTest {
                private final OrderMachines machines = OrderMachinesRuntime.builder().build();
                private final Orders orders = machines.orders();

                @AfterEach
                void close() {
                    machines.close();
                }

                @Test
                void aCancellationRefusedWhileProcessingRunsOnTheArrivalInReady() {
                    Order order = new Order("O1");
                    orders.start(order);
                    Cancellations.cancel(orders, order);
                    assertTrue(order.isCancellationRequested());
                    assertEquals(PROCESSING, order.getStatus());
                    assertThat(orders.finish(order)).isApplied().movedTo(READY)
                            .hasEntityState(Order::getStatus, CANCELLED)
                            .automaticSteps().forEntity(OrdersRuntime.KEY, order).single()
                            .isDone().outcome().isApplied().requestedTransition(CANCEL_WHEN_READY);
                }

                @Test
                void anArrivalWithNoRequestReportsABlock() {
                    Order order = new Order("O2");
                    orders.start(order);
                    assertThat(orders.finish(order)).isApplied().movedTo(READY).automaticBlock()
                            .hasReason(IgnoreReason.NO_CANDIDATE_ADMITTED).rejections().single()
                            .transition(CANCEL_WHEN_READY).isRejectedByGuardNamed("cancellationRequested")
                            .hasReason("no cancellation is pending");
                    assertEquals(READY, order.getStatus());
                }

                @Test
                void aRequestRecordedInReadyWaitsForAdvanceWhoseOutcomeIsTheAttempt() {
                    Order order = new Order("O3");
                    orders.start(order);
                    orders.finish(order);
                    order.requestCancellation();
                    assertEquals(READY, order.getStatus());
                    assertThat(orders.advance(order)).isApplied().requestedTransition(CANCEL_WHEN_READY)
                            .hasEntityState(Order::getStatus, CANCELLED).automaticSteps().isEmpty();
                }
            }
            """;

    @TempDir
    static Path project;

    /** The class files of the types that hold the guide's blocks, relative to the project's main classes. */
    private static final List<String> BLOCK_CLASSES = new ArrayList<>();
    /** The project's JUnit test classes, by fully qualified name, and the names of their test methods. */
    private static final Map<String, List<String>> TESTS = new LinkedHashMap<>();
    private static Path transcript;
    private static int exitValue;

    @BeforeAll
    static void buildThePatternsAndRunTheirTests() throws IOException, InterruptedException {
        List<String> patterns = page(PATTERNS);
        for (Section section : SECTIONS) {
            assertBlockCount(patterns, section.heading(), section.blocks());
        }
        writeBuildScripts(page("installation.md"), page("quickstart.md"));
        write("lifecycle", LIFECYCLE_FIXTURES, blockAfter(patterns, ENTRY_EXIT, 1), LIFECYCLE_TEST);
        write("wizard", WIZARD_FIXTURES, blockAfter(patterns, RESUME, 1) + blockAfter(patterns, RESUME, 2),
                WIZARD_TEST);
        write("restore", RESTORE_FIXTURES, blockAfter(patterns, RESTORE, 1), RESTORE_TEST);
        write("regions", REGION_FIXTURES, blockAfter(patterns, REGIONS, 1) + blockAfter(patterns, REGIONS, 2),
                REGION_TEST);
        write("timers", TIMER_FIXTURES, timerBlocks(patterns), TIMER_TEST);
        write("deferred", DEFERRED_FIXTURES, deferredBlocks(patterns), DEFERRED_TEST);
        runGradle();
    }

    @Test
    void everyJavaBlockIsCompiled() throws IOException {
        int onThePages = MarkdownPage.allBlocks(page(CONCEPTS), "java").size()
                + MarkdownPage.allBlocks(page(PATTERNS), "java").size();
        int built = SECTIONS.stream().mapToInt(Section::blocks).sum();
        assertEquals(MIGRATION_BLOCKS, onThePages, "the migration pages hold " + onThePages
                + " java blocks; this test builds exactly " + MIGRATION_BLOCKS + ": update it to match the pages");
        assertEquals(MIGRATION_BLOCKS, built, "the sections this test builds hold " + built + " java blocks");
    }

    @Test
    void everyJavaBlockCompiles() throws IOException {
        String output = Files.readString(transcript, UTF_8);
        Path classes = project.resolve("build/classes/java/main");
        assertFalse(BLOCK_CLASSES.isEmpty(), "no type of the guide's blocks was written");
        for (String type : BLOCK_CLASSES) {
            assertTrue(Files.isRegularFile(classes.resolve(type)),
                    "the guide's block that declares " + type + " did not compile:\n" + output);
        }
    }

    @Test
    void eachPatternBehavesAsTheGuideSays() throws Exception {
        String output = Files.readString(transcript, UTF_8);
        assertTrue(output.lines().anyMatch((String line) -> line.startsWith("> Task :test")), output);
        for (Map.Entry<String, List<String>> suite : TESTS.entrySet()) {
            assertSuitePassed(suite.getKey(), suite.getValue());
        }
        assertEquals(0, exitValue, output);
    }

    /** The timer section's guard host, its operation inside its facade, and its scheduler statements in a method. */
    private static String timerBlocks(List<String> patterns) {
        return blockAfter(patterns, TIMERS, 1)
                + RESERVATIONS_HEAD + blockAfter(patterns, TIMERS, 2).indent(4) + "}\n"
                + SCHEDULING_HEAD + blockAfter(patterns, TIMERS, 3).indent(8) + "    }\n}\n";
    }

    /** The deferred section's annotation on its facade, its guard host, and its application statements in a method. */
    private static String deferredBlocks(List<String> patterns) {
        return ORDERS_HEAD + blockAfter(patterns, DEFERRED, 1) + ORDERS_BODY
                + blockAfter(patterns, DEFERRED, 2)
                + CANCELLATIONS_HEAD + blockAfter(patterns, DEFERRED, 3).indent(8) + "    }\n}\n";
    }

    /**
     * Writes the package {@code name}: each top-level type of the fixtures and the blocks to a main source file, and
     * each one of the test to a test source file. Records the types the blocks declare and the test's methods.
     */
    private static void write(String name, String fixtures, String blocks, String test) throws IOException {
        Map<String, String> main = topLevelTypes(fixtures + blocks);
        List<String> enums = main.entrySet().stream()
                .filter((Map.Entry<String, String> type) -> type.getValue().contains("public enum "))
                .map(Map.Entry::getKey)
                .toList();
        writeFiles(project.resolve("src/main/java/" + BASE + name), name, enums, IMPORTS, main);
        Map<String, String> tests = topLevelTypes(test);
        writeFiles(project.resolve("src/test/java/" + BASE + name), name, enums, TEST_IMPORTS + IMPORTS, tests);
        topLevelTypes(blocks).keySet().forEach((String type) -> BLOCK_CLASSES.add(BASE + name + "/" + type + ".class"));
        tests.forEach((String type, String source) -> {
            List<String> methods = TEST_METHOD.matcher(source).results()
                    .map((MatchResult method) -> method.group(1))
                    .toList();
            if (!methods.isEmpty()) {
                TESTS.put("com.example.migration." + name + "." + type, methods);
            }
        });
    }

    /** Writes each type to its own file, after the package and static imports of the other enums' constants. */
    private static void writeFiles(Path directory, String name, List<String> enums, String imports,
            Map<String, String> types) throws IOException {
        Files.createDirectories(directory);
        for (Map.Entry<String, String> type : types.entrySet()) {
            StringBuilder source = new StringBuilder("package com.example.migration." + name + ";\n\n");
            for (String constants : enums) {
                if (!constants.equals(type.getKey())) {
                    source.append("import static com.example.migration.").append(name).append('.')
                            .append(constants).append(".*;\n");
                }
            }
            source.append('\n').append(imports).append('\n').append(type.getValue());
            Files.writeString(directory.resolve(type.getKey() + ".java"), source, UTF_8);
        }
    }

    /**
     * Splits Java source written in the guide's formatting into its top-level types, in order, by name. A type ends at
     * a line that is a lone closing brace, the only kind a top-level type closes with, or at a one-line enum. Fails on
     * source left after the last type.
     */
    private static Map<String, String> topLevelTypes(String source) {
        Map<String, String> types = new LinkedHashMap<>();
        StringBuilder current = new StringBuilder();
        for (String line : source.lines().toList()) {
            current.append(line).append('\n');
            if (line.equals("}") || ONE_LINE_ENUM.matcher(line).matches()) {
                Matcher type = TYPE.matcher(current);
                assertTrue(type.find(), "no type declaration in:\n" + current);
                types.put(type.group(1), current.toString());
                current.setLength(0);
            }
        }
        assertTrue(current.toString().isBlank(), "source after the last type:\n" + current);
        return types;
    }

    /** Runs the project's {@code test} task and keeps its exit value; the transcript is kept in the project. */
    private static void runGradle() throws IOException, InterruptedException {
        transcript = project.resolve("gradle-output.txt");
        ProcessBuilder builder = new ProcessBuilder(property("gradleExecutable"), "--no-daemon", "--offline",
                "--console=plain", "--max-workers=2", "test")
                .directory(project.toFile()).redirectErrorStream(true).redirectOutput(transcript.toFile());
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process process = builder.start();
        Optional<Throwable> originalFailure = Optional.empty();
        try {
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                fail("the migration patterns' build timed out; transcript: " + transcript);
            }
            exitValue = process.exitValue();
        } catch (InterruptedException | RuntimeException | Error failure) {
            originalFailure = Optional.of(failure);
            throw failure;
        } finally {
            ChildProcesses.reap(process, originalFailure,
                    "the migration patterns' build remained alive; transcript: " + transcript);
        }
    }

    /**
     * Fails unless the JUnit report of {@code suite} lists exactly {@code methods}, each one passed; a failure quotes
     * the messages of the report's failed tests.
     */
    private static void assertSuitePassed(String suite, List<String> methods) throws Exception {
        Path report = project.resolve("build/test-results/test/TEST-" + suite + ".xml");
        assertTrue(Files.isRegularFile(report), "no JUnit report for " + suite + ": " + report);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Element root = factory.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
        assertEquals(String.valueOf(methods.size()), root.getAttribute("tests"), "tests run in " + report);
        String failures = failureMessages(root);
        for (String attribute : List.of("failures", "errors", "skipped")) {
            assertEquals("0", root.getAttribute(attribute), "unexpected " + attribute + " in " + suite + failures);
        }
        NodeList cases = root.getElementsByTagName("testcase");
        TreeSet<String> ran = new TreeSet<>();
        for (int index = 0; index < cases.getLength(); index++) {
            ran.add(((Element) cases.item(index)).getAttribute("name").replace("()", ""));
        }
        assertEquals(new TreeSet<>(methods), ran, "the test methods of " + suite);
    }

    /** The messages of the failed and erroneous tests of a JUnit report, each on its own line. */
    private static String failureMessages(Element root) {
        StringBuilder messages = new StringBuilder();
        for (String tag : List.of("failure", "error")) {
            NodeList failed = root.getElementsByTagName(tag);
            for (int index = 0; index < failed.getLength(); index++) {
                Element testCase = (Element) failed.item(index).getParentNode();
                messages.append("\n").append(testCase.getAttribute("name")).append(": ")
                        .append(((Element) failed.item(index)).getAttribute("message"));
            }
        }
        return messages.toString();
    }

    /**
     * Writes the installation page's settings and build scripts, the build script followed by the quickstart's test
     * dependencies, and {@code gradle.properties} with the staged version, the way {@link ReadmeQuickstartTest} does.
     */
    private static void writeBuildScripts(List<String> installation, List<String> quickstart) throws IOException {
        String repository = Path.of(property("artifactRepository")).toUri().toASCIIString();
        String settings = fenceAfter(installation, "### settings.gradle.kts", "kotlin")
                .replace("mavenCentral()", "maven { url = uri(\"" + repository + "\") }\n        mavenCentral()");
        Files.writeString(project.resolve("settings.gradle.kts"), settings, UTF_8);
        Files.writeString(project.resolve("gradle.properties"),
                "graphmaquinaVersion=" + property("artifactVersion") + "\n", UTF_8);
        String build = fenceAfter(installation, "### build.gradle.kts", "kotlin");
        String testDependencies = fenceAfter(quickstart, "## Test your machines", "kotlin");
        Files.writeString(project.resolve("build.gradle.kts"), build + "\n" + testDependencies, UTF_8);
    }

    /** Returns the first {@code language} fence of the section that the line {@code context} opens. */
    private static String fenceAfter(List<String> lines, String context, String language) {
        int section = MarkdownPage.lineAfter(lines, context);
        assertTrue(section >= 0, "build script context missing: " + context);
        MarkdownPage.Block block = MarkdownPage.blocksFrom(lines, section, (String line) -> line.startsWith("#"),
                language).stream().findFirst().orElse(null);
        assertNotNull(block, "build script missing in section: " + context);
        assertTrue(block.closed(), "build script not closed after: " + context);
        return block.content();
    }

    /** Fails unless the section under {@code heading} holds exactly {@code expected} java blocks. */
    private static void assertBlockCount(List<String> page, String heading, int expected) {
        int actual = MarkdownPage.sectionBlocks(page, heading, "java").size();
        assertEquals(expected, actual, "the patterns page's section '" + heading + "' has " + actual
                + " java blocks; this test builds exactly " + expected + ": update the test to match the section");
    }

    /**
     * Returns the {@code n}th java block after {@code heading} and before the next heading of its level or higher.
     * Headings are matched outside code fences only. Fails when the block is missing or blank.
     */
    private static String blockAfter(List<String> page, String heading, int n) {
        List<MarkdownPage.Block> blocks = MarkdownPage.sectionBlocks(page, heading, "java");
        String block = n <= blocks.size() ? blocks.get(n - 1).content() : "";
        assertFalse(block.isBlank(), "no non-blank java block number " + n + " after the heading '"
                + heading + "' of the patterns page");
        return block;
    }

    /** Reads the guide's page {@code name} from the directory the {@code docsPath} property names. */
    private static List<String> page(String name) throws IOException {
        return MarkdownPage.read(Path.of(property("docsPath")), name);
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        assertNotNull(value, "run this test through :graphmaquina-test:publishedArtifactTest; missing " + name);
        return value;
    }
}
