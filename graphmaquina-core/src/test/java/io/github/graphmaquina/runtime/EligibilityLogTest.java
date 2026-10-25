package io.github.graphmaquina.runtime;

import static io.github.graphmaquina.fixtures.Billing.BookingStatus.CANCELLED;
import static io.github.graphmaquina.fixtures.Billing.BookingStatus.DEPOSIT;
import static io.github.graphmaquina.fixtures.Billing.PAYMENTS;
import static io.github.graphmaquina.fixtures.Billing.PaymentStatus.AUTHORIZING;
import static io.github.graphmaquina.fixtures.Billing.PaymentTransition.CAPTURE;
import static io.github.graphmaquina.fixtures.DocGraphs.docs;
import static io.github.graphmaquina.fixtures.DocMachines.registry;
import static io.github.graphmaquina.fixtures.DocStatus.DRAFT;
import static io.github.graphmaquina.fixtures.DocTransition.PUBLISH;
import static io.github.graphmaquina.fixtures.DocTransition.SUBMIT;
import static io.github.graphmaquina.fixtures.Keys.DOCS;
import static io.github.graphmaquina.fixtures.LogLines.lines;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.graphmaquina.api.ActionOutcome;
import io.github.graphmaquina.api.GraphDefinition;
import io.github.graphmaquina.api.GuardContext;
import io.github.graphmaquina.api.GuardResult;
import io.github.graphmaquina.api.MachineKey;
import io.github.graphmaquina.api.StateMachine;
import io.github.graphmaquina.api.TransitionContext;
import io.github.graphmaquina.dsl.StateGraph;
import io.github.graphmaquina.dsl.TransitionBuilder;
import io.github.graphmaquina.error.EligibilityCheckException;
import io.github.graphmaquina.error.TransitionAbortedException;
import io.github.graphmaquina.fixtures.Billing;
import io.github.graphmaquina.fixtures.Billing.Booking;
import io.github.graphmaquina.fixtures.Billing.Payment;
import io.github.graphmaquina.fixtures.Doc;
import io.github.graphmaquina.fixtures.DocStatus;
import io.github.graphmaquina.fixtures.DocTransition;
import io.github.graphmaquina.fixtures.LibraryLog;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The lines an eligibility check logs on the runtime logger, read through {@code java.util.logging}: at {@code FINE} a
 * {@code start} line and a verdict line, or a {@code failed} one, and at {@code FINER} the lines of the step it admits,
 * all of them under {@code check=<id>} in place of {@code call=<id>}. A check is no call, so it logs no line of a
 * call, and it takes its id from the counter of the calls. A backend that fails on these lines is read in
 * {@link EligibilityLogBackendTest}.
 */
class EligibilityLogTest {

    static final String NEEDS_TITLE = "a draft needs a title";

    /** States of the tickets the logs page shows. */
    enum TicketState { OPEN, REVIEW, FAILED }

    /** Identifiers of the ticket machine. */
    enum TicketTransition { SUBMIT, DECLINE }

    private static final MachineKey<Ticket, TicketState, TicketTransition> TICKETS =
            MachineKey.of(Ticket.class, TicketState.class, TicketTransition.class);

    /** A ticket of the logs page: an id, a title that may be empty, and a state. */
    static final class Ticket {

        private final String id;
        private final String title;
        private TicketState state = TicketState.OPEN;

        Ticket(String id, String title) {
            this.id = id;
            this.title = title;
        }

        String id() { return id; }

        String title() { return title; }

        TicketState state() { return state; }

        void setState(TicketState state) { this.state = state; }
    }

    /**
     * The guard {@code hasTitle} of SUBMIT: it rejects the doc d2, throws for d3 and passes any other.
     */
    private static GuardResult hasTitle(GuardContext<Doc, DocStatus, DocTransition> ctx) {
        String id = ctx.entity().id();
        if (id.equals("d3")) {
            throw new IllegalStateException("no title list");
        }
        return id.equals("d2") ? GuardResult.reject(NEEDS_TITLE) : GuardResult.pass();
    }

    /** The doc graph whose SUBMIT edge has the guard {@code hasTitle}. */
    static GraphDefinition<Doc, DocStatus, DocTransition> titled() {
        return docs((TransitionBuilder<Doc, DocStatus, DocTransition> submit) ->
                submit.guard("hasTitle", EligibilityLogTest::hasTitle));
    }

    /** What one check of {@code id} for the doc {@code docId} logs at {@code level}, as {@link #lines} reads it. */
    private static List<String> logged(Level level, String docId, DocTransition id) {
        try (MachineRegistry registry = registry(titled()); LibraryLog log = LibraryLog.runtime(level)) {
            registry.of(DOCS).eligibility(new Doc(docId, DRAFT), id);
            return lines(log);
        }
    }

    @Test
    void debugLinesOfAnEligibleCheck() {
        assertEquals(List.of(
                "FINE check=A start Doc/DocStatus SUBMIT",
                "FINE check=A end verdict=ELIGIBLE transition=SUBMIT state=DRAFT entity=Doc/DocStatus#d1 elapsed=<us>"),
                logged(Level.FINE, "d1", SUBMIT));
    }

    @Test
    void debugLinesOfAGuardRejection() {
        assertEquals(List.of(
                "FINE check=A start Doc/DocStatus SUBMIT",
                "FINE check=A end verdict=INELIGIBLE transition=SUBMIT kind=GUARD guard=hasTitle state=DRAFT"
                        + " entity=Doc/DocStatus#d2 reason=\"a draft needs a title\" elapsed=<us>"),
                logged(Level.FINE, "d2", SUBMIT));
    }

    @Test
    void debugLinesOfARequestWithoutAnEdge() {
        assertEquals(List.of(
                "FINE check=A start Doc/DocStatus PUBLISH",
                "FINE check=A end verdict=INELIGIBLE transition=PUBLISH kind=NO_TRANSITION state=DRAFT"
                        + " entity=Doc/DocStatus#d1 reason=\"a draft is submitted or discarded\" elapsed=<us>"),
                logged(Level.FINE, "d1", PUBLISH));
    }

    @ParameterizedTest
    @CsvSource({"d1, SUBMIT", "d2, SUBMIT", "d1, PUBLISH"})
    void traceLinesUseTheCheckPrefix(String docId, DocTransition id) {
        List<String> trace = logged(Level.FINER, docId, id).stream()
                .filter((String line) -> line.startsWith("FINER ")).toList();
        assertFalse(trace.isEmpty());
        assertTrue(trace.stream().allMatch((String line) -> line.startsWith("FINER check=A depth=0 ")),
                trace::toString);
    }

    @Test
    void traceLinesNameTheEdgeAndTheGuards() {
        assertEquals(List.of(
                "FINE check=A start Doc/DocStatus SUBMIT",
                "FINER check=A depth=0 resolved Doc/DocStatus#d1 DRAFT -SUBMIT-> REVIEW kind=MOVE",
                "FINER check=A depth=0 guard hasTitle PASS elapsed=<us>",
                "FINE check=A end verdict=ELIGIBLE transition=SUBMIT state=DRAFT entity=Doc/DocStatus#d1 elapsed=<us>"),
                logged(Level.FINER, "d1", SUBMIT));
        assertEquals(List.of(
                "FINE check=A start Doc/DocStatus SUBMIT",
                "FINER check=A depth=0 resolved Doc/DocStatus#d2 DRAFT -SUBMIT-> REVIEW kind=MOVE",
                "FINER check=A depth=0 guard hasTitle REJECT reason=\"a draft needs a title\" elapsed=<us>",
                "FINE check=A end verdict=INELIGIBLE transition=SUBMIT kind=GUARD guard=hasTitle state=DRAFT"
                        + " entity=Doc/DocStatus#d2 reason=\"a draft needs a title\" elapsed=<us>"),
                logged(Level.FINER, "d2", SUBMIT));
    }

    @Test
    void traceLinesNameARequestWithoutAnEdge() {
        assertEquals(List.of(
                "FINE check=A start Doc/DocStatus PUBLISH",
                "FINER check=A depth=0 no-edge Doc/DocStatus#d1 DRAFT PUBLISH",
                "FINE check=A end verdict=INELIGIBLE transition=PUBLISH kind=NO_TRANSITION state=DRAFT"
                        + " entity=Doc/DocStatus#d1 reason=\"a draft is submitted or discarded\" elapsed=<us>"),
                logged(Level.FINER, "d1", PUBLISH));
    }

    /** The lines of one check of CAPTURE for the payment p1, whose booking b1 is in {@code parent}. */
    private static List<String> loggedPayment(Billing.BookingStatus parent) {
        Payment payment = new Payment("p1", AUTHORIZING);
        new Booking("b1", parent).attachDeposit(payment);
        try (MachineRegistry registry = new Billing().registry(); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            registry.of(PAYMENTS).eligibility(payment, CAPTURE);
            return lines(log);
        }
    }

    @Test
    void traceLinesNameTheBindingOfASubMachine() {
        assertEquals(List.of(
                "FINE check=A start Payment/PaymentStatus CAPTURE",
                "FINER check=A depth=0 binding Payment/PaymentStatus#p1 parent=Booking/BookingStatus#b1 node=DEPOSIT",
                "FINER check=A depth=0 resolved Payment/PaymentStatus#p1 AUTHORIZING -CAPTURE-> CAPTURED kind=ACTION",
                "FINE check=A end verdict=ELIGIBLE transition=CAPTURE state=AUTHORIZING"
                        + " entity=Payment/PaymentStatus#p1 elapsed=<us>"),
                loggedPayment(DEPOSIT));
    }

    @Test
    void traceLinesNameWhyASubMachineIsDetached() {
        assertEquals(List.of(
                "FINE check=A start Payment/PaymentStatus CAPTURE",
                "FINER check=A depth=0 detached Payment/PaymentStatus#p1"
                        + " reason=\"no parent holds it in DEPOSIT or BALANCE\"",
                "FINE check=A end verdict=INELIGIBLE transition=CAPTURE kind=DETACHED state=AUTHORIZING"
                        + " entity=Payment/PaymentStatus#p1 reason=\"no parent holds it in DEPOSIT or BALANCE\""
                        + " elapsed=<us>"),
                loggedPayment(CANCELLED));
    }

    private static void assertNoLineContains(List<String> lines, String text) {
        assertFalse(lines.stream().anyMatch((String line) -> line.contains(text)), text);
    }

    @Test
    void noCallLineIsEverLogged() {
        try (MachineRegistry registry = registry(titled()); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            StateMachine<Doc, DocStatus, DocTransition> machine = registry.of(DOCS);
            machine.eligibility(new Doc("d1", DRAFT), SUBMIT);
            machine.eligibility(new Doc("d2", DRAFT), SUBMIT);
            machine.eligibility(new Doc("d1", DRAFT), PUBLISH);
            assertThrows(EligibilityCheckException.class, () -> machine.eligibility(new Doc("d3", DRAFT), SUBMIT));
            List<String> lines = lines(log);
            assertEquals(14, lines.size(), lines::toString);
            assertNoLineContains(lines, "call=");
            assertNoLineContains(lines, "committed");
            assertNoLineContains(lines, "started");
        }
    }

    @Test
    void failedCheckLogsFailed() {
        try (MachineRegistry registry = registry(titled()); LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            EligibilityCheckException error = assertThrows(EligibilityCheckException.class,
                    () -> registry.of(DOCS).eligibility(new Doc("d3", DRAFT), SUBMIT));
            assertEquals(List.of(
                    "FINE check=A start Doc/DocStatus SUBMIT",
                    "FINE check=A failed error=" + error + " elapsed=<us>"), lines(log));
        }
    }

    /** The ids that follow {@code label=} in the records of {@code log}, in order, each once. */
    private static List<Long> ids(LibraryLog log, String label) {
        Pattern pattern = Pattern.compile(label + "=(\\d+)");
        List<Long> ids = new ArrayList<>();
        log.records().forEach((LogRecord record) -> {
            Matcher found = pattern.matcher(record.getMessage());
            if (found.find() && !ids.contains(Long.valueOf(found.group(1)))) {
                ids.add(Long.valueOf(found.group(1)));
            }
        });
        return ids;
    }

    @Test
    void checksAndCallsShareOneCounter() {
        try (MachineRegistry registry = registry(titled()); LibraryLog log = LibraryLog.runtime(Level.FINE)) {
            registry.of(DOCS).execute(new Doc("d1", DRAFT), SUBMIT);
            registry.of(DOCS).eligibility(new Doc("d1", DRAFT), SUBMIT);
            assertEquals(List.of(
                    "FINE call=A start Doc/DocStatus SUBMIT",
                    "FINE call=A committed Doc/DocStatus#d1 DRAFT -SUBMIT-> REVIEW depth=0",
                    "FINE call=A end code=APPLIED state=REVIEW entity=Doc/DocStatus#d1 records=1 starts=0"
                            + " reactions=0 aborted=0 elapsed=<us>",
                    "FINE check=B start Doc/DocStatus SUBMIT",
                    "FINE check=B end verdict=ELIGIBLE transition=SUBMIT state=DRAFT entity=Doc/DocStatus#d1"
                            + " elapsed=<us>"),
                    lines(log));
            List<Long> calls = ids(log, "call");
            List<Long> checks = ids(log, "check");
            assertEquals(List.of(calls.get(0) + 1), checks);
        }
    }

    @Test
    void aCheckThatFindsBothLevelsOffLogsNothing() {
        try (MachineRegistry registry = registry(titled()); LibraryLog log = LibraryLog.runtime(Level.INFO)) {
            registry.of(DOCS).eligibility(new Doc("d1", DRAFT), SUBMIT);
            registry.of(DOCS).eligibility(new Doc("d2", DRAFT), SUBMIT);
            assertEquals(List.of(), log.records());
        }
    }

    @Test
    void aCheckRefusedBeforeItStartsLogsNothing() {
        try (MachineRegistry registry = registry(titled()); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            assertThrows(NullPointerException.class, () -> registry.of(DOCS).eligibility(null, SUBMIT));
            assertEquals(List.of(), log.records());
        }
    }

    @Test
    void aCheckRefusedAsReentrantLogsNothing() {
        AtomicReference<StateMachine<Doc, DocStatus, DocTransition>> machine = new AtomicReference<>();
        GraphDefinition<Doc, DocStatus, DocTransition> asking = docs(
                (TransitionBuilder<Doc, DocStatus, DocTransition> submit) -> submit.guard("asks",
                        (GuardContext<Doc, DocStatus, DocTransition> ctx) -> {
                            machine.get().eligibility(ctx.entity(), PUBLISH);
                            return GuardResult.pass();
                        }));
        try (MachineRegistry registry = registry(asking); LibraryLog log = LibraryLog.runtime(Level.FINER)) {
            machine.set(registry.of(DOCS));
            assertThrows(TransitionAbortedException.class, () -> machine.get().execute(new Doc("d1", DRAFT), SUBMIT));
            assertFalse(log.records().isEmpty());
            assertFalse(log.records().stream().anyMatch((LogRecord record) -> record.getMessage().contains("check=")));
        }
    }

    /** The ticket machine of the logs page: SUBMIT needs a title, registers the ticket and declines on failure. */
    private static MachineRegistry tickets() {
        GraphDefinition<Ticket, TicketState, TicketTransition> graph = StateGraph.define(TICKETS)
                .initial(TicketState.OPEN).terminal(TicketState.REVIEW).terminal(TicketState.FAILED)
                .statePolicy(TicketState.OPEN, "a ticket is submitted or declined")
                .transition(TicketTransition.SUBMIT, TicketState.OPEN, TicketState.REVIEW)
                    .guard("hasTitle", (GuardContext<Ticket, TicketState, TicketTransition> ctx) ->
                            ctx.entity().title().isEmpty()
                                    ? GuardResult.reject("a ticket needs a title") : GuardResult.pass())
                    .action("register", (TransitionContext<Ticket, TicketState, TicketTransition> ctx) ->
                            ActionOutcome.ok())
                    .onFailure(TicketTransition.DECLINE)
                    .endTransition()
                .transition(TicketTransition.DECLINE, TicketState.OPEN, TicketState.FAILED).endTransition()
                .build();
        return MachineRegistry.builder()
                .add(StateMachines.from(graph).state(Ticket::state, Ticket::setState).identity(Ticket::id))
                .build();
    }

    /** What the logs page shows: SUBMIT checked for T-1, which has a title, then for T-2, which has none. */
    private static List<String> loggedTickets(Level level) {
        try (MachineRegistry registry = tickets(); LibraryLog log = LibraryLog.runtime(level)) {
            registry.of(TICKETS).eligibility(new Ticket("T-1", "Fix the login page"), TicketTransition.SUBMIT);
            registry.of(TICKETS).eligibility(new Ticket("T-2", ""), TicketTransition.SUBMIT);
            return lines(log);
        }
    }

    @Test
    void theDebugExampleOfTheLogsPage() {
        assertEquals(List.of(
                "FINE check=A start Ticket/TicketState SUBMIT",
                "FINE check=A end verdict=ELIGIBLE transition=SUBMIT state=OPEN entity=Ticket/TicketState#T-1"
                        + " elapsed=<us>",
                "FINE check=B start Ticket/TicketState SUBMIT",
                "FINE check=B end verdict=INELIGIBLE transition=SUBMIT kind=GUARD guard=hasTitle state=OPEN"
                        + " entity=Ticket/TicketState#T-2 reason=\"a ticket needs a title\" elapsed=<us>"),
                loggedTickets(Level.FINE));
    }

    @Test
    void theTraceExampleOfTheLogsPage() {
        assertEquals(List.of(
                "FINE check=A start Ticket/TicketState SUBMIT",
                "FINER check=A depth=0 resolved Ticket/TicketState#T-1 OPEN -SUBMIT-> REVIEW kind=ACTION",
                "FINER check=A depth=0 guard hasTitle PASS elapsed=<us>",
                "FINE check=A end verdict=ELIGIBLE transition=SUBMIT state=OPEN entity=Ticket/TicketState#T-1"
                        + " elapsed=<us>",
                "FINE check=B start Ticket/TicketState SUBMIT",
                "FINER check=B depth=0 resolved Ticket/TicketState#T-2 OPEN -SUBMIT-> REVIEW kind=ACTION",
                "FINER check=B depth=0 guard hasTitle REJECT reason=\"a ticket needs a title\" elapsed=<us>",
                "FINE check=B end verdict=INELIGIBLE transition=SUBMIT kind=GUARD guard=hasTitle state=OPEN"
                        + " entity=Ticket/TicketState#T-2 reason=\"a ticket needs a title\" elapsed=<us>"),
                loggedTickets(Level.FINER));
    }
}
